package dev.ujhhgtg.wekit.features.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.items.system.SafeMode
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.DexResolver
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    /** 单个功能 startup() 超过这个耗时就在日志里单独点名（排查「启动特别卡」用）。 */
    private const val SLOW_STARTUP_MS = 200L

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_FEATURES
        allFeatures.filterIsInstance<SwitchFeature>().forEach(SwitchFeature::loadPersistedState)

        val safeMode = SafeMode.isEnabled
        val featuresToStart = if (safeMode) {
            allFeatures.filterIsInstance<ApiFeature>()
        } else {
            allFeatures
        }
        if (safeMode) {
            WeLogger.i(
                TAG,
                "safe mode active: loading only ${featuresToStart.size} ApiFeature(s), " +
                    "skipping ${allFeatures.size - featuresToStart.size} feature(s)",
            )
        }
        val allDexItems = featuresToStart.filterIsInstance<IResolveDex>()

        // 铁律守卫：只有 IResolveDex 的 feature 会被送去 DexKit 解析（见上面的 filterIsInstance）。
        // 若某个 feature 声明了 DexKit 委托却没实现该接口，它的委托永远停在「未解析」状态：
        // descriptor 为 null，而 isPlaceholder 只在 descriptor == PLACEHOLDER 时为 true，
        // 于是 `if (!delegate.isPlaceholder)` 这类保护形同虚设，访问 delegate 会抛
        // IllegalStateException("Method not found for key: …")，被 enable() 的 runCatching 吃掉后
        // 执行 unhookAll()，把该 feature 已装好的 hook 全部摘掉 —— 表现就是「开关打开却完全没生效」。
        // （AutoEnableSendOriginalMedia 2026-09-22 的真实线上故障，这里加日志守卫防止再犯。）
        featuresToStart.forEach { feature ->
            if (feature !is IResolveDex && (feature as BaseFeature).dexDelegates.isNotEmpty()) {
                WeLogger.e(
                    TAG,
                    "守卫：${feature.technicalId} 声明了 ${feature.dexDelegates.size} 个 DexKit 委托" +
                        "却没有实现 IResolveDex —— 解析永远不会发生，访问委托会抛异常并 unhook 整条功能" +
                        "（keys=${feature.dexDelegates.map { it.key }}）",
                )
            }
        }

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        // 延后批次：只包含「用户在启动瞬间绝不可能用到」的按需功能（聊天内悬浮控件、相册选择器、
        // 朋友圈/激励广告等）。这些功能的 hook 安装实测每个 200~800ms，全部塞在启动同步阶段会让
        // 微信主线程在启动时连续阻塞十几秒（实机日志：一次冷缓存启动 `loading all features took
        // 16.379601036s`，全部落在 Instrumentation.callApplicationOnCreate 里，是「整个微信和
        // WeKit 都有一点点卡顿」的主要来源）。其余功能保持原有同步安装顺序不变，避免影响启动期
        // 就需要生效的功能（启动页/开屏广告/莫奈/首页 UI 等）。
        val (syncFeatures, deferredFeatures) = featuresToStart.partition {
            safeMode || it.technicalId !in DEFERRED_STARTUP_IDS
        }

        val elapsed = measureTime {
            syncFeatures.forEach { feature -> runFeatureStartup(feature, allBrokenItems) }
        }
        WeLogger.i(TAG, "loading all features took $elapsed")

        // 主线程空闲（首帧画完）后再用「切片 + 让出」的方式装延后批次，不再阻塞启动关键路径。
        if (deferredFeatures.isNotEmpty()) {
            scheduleDeferredStartup(deferredFeatures, allBrokenItems)
        }

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            val context = LocalizedContextFactory.create(
                HostInfo.application,
                WeKitLocaleController.resolvedLocale,
                LocaleResourceMode.InjectedHost,
            )
            showToast(context, context.getString(R.string.noncompose_features_loaded))
        }
    }

    /**
     * 启动同步阶段结束后才安装的功能（按需触发，启动瞬间用不到）。
     *
     * 判定标准：该功能的效果只会在「用户主动进入聊天 / 打开相册 / 刷朋友圈」等动作之后出现，
     * 微信启动过程中（首页首帧之前）不可能被调用到。**不要**把首页/会话列表/启动页相关功能
     * 放进来 —— 那些必须在启动同步阶段装好，否则首帧就不生效。
     */
    private val DEFERRED_STARTUP_IDS = setOf(
        "@所有人",
        "WeAgent",
        "半屏相册选择器",
        "去除菜单限制",
        "快捷回底",
        "悬浮输入框",
        "朋友圈评论防撤回",
        "跳过激励广告",
        "移除通话时聊天限制",
    )

    /** 延后批次每片最多占用主线程多久，超过就让出一次消息循环（保证首帧之后的 UI 不抖）。 */
    private const val DEFERRED_SLICE_BUDGET_MS = 120L

    private fun runFeatureStartup(feature: BaseFeature, allBrokenItems: List<BaseFeature>) {
        val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

        if (isBroken) {
            WeLogger.w(TAG, "skipping ${feature.technicalId} — incomplete cache, awaiting re-resolution")
            return
        }

        // 逐个功能计时：用户反馈「特别卡」时，日志里只有一条总的
        // "loading all features took N s"，定位不到是哪个功能拖慢启动 —— 慢的单独打出来。
        val startedAt = SystemClock.uptimeMillis()
        feature.startup()
        val cost = SystemClock.uptimeMillis() - startedAt
        if (cost >= SLOW_STARTUP_MS) {
            WeLogger.w(TAG, "slow startup: ${feature.technicalId} took ${cost}ms")
        }
    }

    private fun scheduleDeferredStartup(
        features: List<BaseFeature>,
        allBrokenItems: List<BaseFeature>,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 不在主线程（理论上不会发生）就直接跑完，保持行为可预期。
            val queue = features.toMutableList()
            drainDeferredStartup(queue, allBrokenItems, SystemClock.uptimeMillis())
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val pending = features.toMutableList()
        val totalStartedAt = SystemClock.uptimeMillis()
        Looper.myQueue().addIdleHandler {
            handler.post {
                drainDeferredStartup(pending, allBrokenItems, totalStartedAt)
            }
            false
        }
        WeLogger.i(
            TAG,
            "deferred startup scheduled: ${features.size} feature(s) — ${features.joinToString { it.technicalId }}",
        )
    }

    private fun drainDeferredStartup(
        pending: MutableList<BaseFeature>,
        allBrokenItems: List<BaseFeature>,
        totalStartedAt: Long,
    ) {
        val sliceStartedAt = SystemClock.uptimeMillis()
        while (pending.isNotEmpty()) {
            runFeatureStartup(pending.removeAt(0), allBrokenItems)
            if (SystemClock.uptimeMillis() - sliceStartedAt >= DEFERRED_SLICE_BUDGET_MS) {
                Handler(Looper.getMainLooper()).post {
                    drainDeferredStartup(pending, allBrokenItems, totalStartedAt)
                }
                return
            }
        }
        WeLogger.i(
            TAG,
            "deferred feature startup finished in ${SystemClock.uptimeMillis() - totalStartedAt}ms",
        )
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val failedItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val path = (item as BaseFeature).technicalPath
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                // loadFromCache 逐委托加载；返回未命中的 key 集合
                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    failedItems += item
                    // 已命中的委托此时已经可用；hook 仍然跳过（见 loadFeatures），
                    // 等 DexKit 把缺失的部分补齐、cache 更新后下次启动即完整。
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache((item as BaseFeature).technicalId) }
                failedItems += item
            }
        }

        return failedItems
    }

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                delay(1_000.milliseconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI available for dex-repair dialog; skipping")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                showComposeDialog(boundActivity, directlyDismissable = false) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        MainScope(),
                        onDismiss
                    )
                }
            }
        }
    }
}
