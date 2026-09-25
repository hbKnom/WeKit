package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ContextMessage
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MessageMetadata

/**
 * 潜语扫描器（合并「言外潜台词」+「Jev 聊天决策」后的唯一入口）。
 *
 * 上游 wechatmood 是独立模块，只能自己 DexKit 搜 `MicroMsg.MvvmChattingItem` 的
 * `[onBindView]` 绑定点、再每 700ms 轮询 ListView/Adapter 才能知道「哪条消息现在在屏幕上」。
 * 本模块跑在 WeKit 里，[WeChatMessageViewApi] 已经把这件事做完了：它 hook 同一个绑定点，
 * 维护 `View -> MessageInfo` 绑定表，并在绑定/解绑/回收时回调。因此这里：
 *
 *  - 去掉 DexKit 与 Adapter 搜索，绑定关系直接由 API 给出；
 *  - 去掉 ListView 遍历，可见行通过 [WeChatMessageViewApi.findBoundViews] 获取；
 *  - 保留一个轻量刷新节拍，仅用于把异步分析结果回填到已经挂好的卡片上。
 *
 * 合并后新增一条硬约束（用户实测「有些行永远停在正在分析…」的根因）：
 * **每条已经提交的分析都必须在有限时间内变成「有结果」或「可见失败」**。
 * 为此这里有看门狗 + 自动结清，绝不允许出现"既没结果也没失败、节拍却一直空转"的状态。
 */
object YanwaiScanner : WeChatMessageViewApi.IMessageViewLifecycleListener, WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "YanwaiScanner"

    /** 结果回填节拍：只在仍有「已挂卡片但尚无结论」时继续跑。 */
    private const val TICK_MS = 400L

    /**
     * 看门狗阈值：超过这个时间仍没有结果/失败，就强制结清成一条可重试的失败。
     * 比 [SignalAnalyzer] 的请求超时略大，正常超时由分析侧先报，这里只是最后的兜底。
     */
    private const val WATCHDOG_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private var installed = false

    /** 正在等待结果的行，避免对同一行重复 show。 */
    private val awaiting = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** key -> 提交时刻（elapsedRealtime）。用于看门狗判定，与系统时间跳变无关。 */
    private val submittedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            if (!installed) return
            var pending = false
            val now = SystemClock.elapsedRealtime()
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                val input = bindingOf(view, message) ?: continue
                val key = input.key
                val done = MoodStore.get(key) != null || SignalAnalyzer.failure(key) != null
                if (!done) {
                    // 看门狗：既不回头（无结果）也不认输（无失败）的不允许存在 —— 强制结清
                    val submitted = submittedAt[key]
                    if (submitted != null && now - submitted > WATCHDOG_MS) {
                        submittedAt.remove(key)
                        SignalAnalyzer.clearStuck(key)
                    }
                    // 只有「真的提交过、还在等」的行才继续快跑；没提交过的（未配置 Key、
                    // 不在作用域）绝不能把 400ms 节拍一直挂着空转。
                    if (submitted != null || MoodStore.isPending(key)) pending = true
                    continue
                }
                submittedAt.remove(key)
                runCatching { YanwaiBubble.show(view, input) }
                    .onFailure { MoodLog.w("气泡回填失败：${it.javaClass.simpleName}") }
            }
            YanwaiBubble.prune()
            if (pending) main.postDelayed(this, TICK_MS)
        }
    }

    fun install() {
        if (installed) return
        installed = true
        WeChatMessageViewApi.addLifecycleListener(this)
        WeChatMessageViewApi.addListener(this)
        MoodLog.i("$TAG 已挂载（气泡=${ModulePrefs.displayBubble}，回插会话=${ModulePrefs.displayMessage}，范围=${ModulePrefs.scopeSummary()}）")
    }

    fun uninstall() {
        installed = false
        main.removeCallbacks(tick)
        awaiting.clear()
        submittedAt.clear()
        runCatching {
            WeChatMessageViewApi.removeLifecycleListener(this)
            WeChatMessageViewApi.removeListener(this)
        }
        YanwaiBubble.clearAll()
        MoodLog.i("$TAG 已卸载")
    }

    fun refresh() {
        main.removeCallbacks(tick)
        if (installed) main.post(tick)
    }

    override fun onCreateView(param: HookParam, view: View) {
        if (!installed) return
        val message = WeChatMessageViewApi.getBoundMessage(view) ?: return
        handle(view, message, immediate = false)
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (!installed) return
        handle(view, message, immediate = true)
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo, rebound: Boolean) {
        // 重绑只是换了消息：卡片由紧接着的 show() 按新 key 覆写，这里不要清（清了会闪一下）。
        if (rebound) return
        awaiting.remove(view)
        YanwaiBubble.clear(view)
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        awaiting.remove(view)
        YanwaiBubble.clear(view)
    }

    private fun handle(view: View, message: MessageInfo, immediate: Boolean) {
        if (!ModulePrefs.enabled) {
            YanwaiBubble.clear(view)
            return
        }
        val input = bindingOf(view, message) ?: run {
            YanwaiBubble.clear(view)
            return
        }
        // 不在作用域的聊天：一张卡都不要留（否则会永远显示「正在分析…」——
        // SignalAnalyzer.submit 会拒收，却没人告诉卡片「这次不会分析」）。
        if (!ModulePrefs.inScope(input.talker)) {
            YanwaiBubble.clear(view)
            return
        }
        val key = input.key
        // 提交一次即可（claim 去重）：气泡通道与「回插会话」通道共用这一份请求与结果。
        val submitted = ModulePrefs.canAnalyze && SignalAnalyzer.submit(input) != null
        if (submitted) submittedAt.putIfAbsent(key, SystemClock.elapsedRealtime())
        val settled = MoodStore.get(key) != null || SignalAnalyzer.failure(key) != null
        if (ModulePrefs.displayBubble) {
            runCatching { YanwaiBubble.show(view, input) }
                .onFailure { MoodLog.w("气泡绘制失败：${it.javaClass.simpleName}") }
        }
        if (!settled && (submitted || MoodStore.isPending(key))) {
            awaiting.add(view)
            if (immediate) refresh()
        }
    }

    /** View -> 分析输入。文本消息才产出，其余返回 null。 */
    private fun bindingOf(view: View, message: MessageInfo): AnalysisInput? {
        // 「也分析我发的消息」为开时才把我方消息纳入（默认只分析对方，与上游一致）。
        val text = MessageMetadata.analyzeText(message, ModulePrefs.analyzeSelf) ?: return null
        val talker = message.talker
        if (talker.isBlank()) return null
        return AnalysisInput(
            text = text,
            talker = talker,
            context = collectContext(view, message),
            messageId = message.id,
            speaker = MessageMetadata.speaker(message),
            createdAt = runCatching { message.createTime }.getOrDefault(0L),
        )
    }

    /**
     * 只从**当前已绑定**的可见行里取前文，与上游一致（不读数据库、不扫历史）。
     * 命中不了 10 条时有多少给多少，不做补造。
     *
     * 注意：这里用行在列表里的纵向顺序（而不是 getLocationOnScreen），
     * 既避免在非主线程碰 View 的屏幕坐标，也不受状态栏/输入法偏移影响。
     */
    private fun collectContext(target: View, message: MessageInfo): List<ContextMessage> {
        val limit = ModulePrefs.contextLimit
        if (limit <= 0) return emptyList()
        val rows = WeChatMessageViewApi.findBoundViews { it.talker == message.talker }
        val ordered = rows.sortedBy { (view, _) -> view.top }
        val index = ordered.indexOfFirst { it.first === target }
        if (index <= 0) return emptyList()
        return ordered.subList(maxOf(0, index - limit), index)
            .mapNotNull { (_, previous) ->
                val previousText = MessageMetadata.plainText(previous) ?: return@mapNotNull null
                ContextMessage(MessageMetadata.speaker(previous), previousText)
            }
    }

    /**
     * 气泡点击「分析失败，点击重试」：清掉这条的失败与冷却，**真正重新提交一次**。
     *
     * 之前这里只调 refresh()（仅跑节拍），卡片会停在「正在分析…」而永远没有新请求 ——
     * 点击重试等于没反应，属于必须修掉的逻辑错误。
     */
    fun retryRow(view: View) {
        if (!installed) return
        main.post {
            val message = WeChatMessageViewApi.getBoundMessage(view) ?: return@post
            val input = bindingOf(view, message) ?: return@post
            SignalAnalyzer.retryFailure(input.key)
            MoodStore.release(input.key)
            submittedAt.remove(input.key)
            handle(view, message, immediate = true)
        }
    }

    /**
     * 「重新分析本屏」/「重试全部失败」：清空结果、失败与进度后，对当前所有可见行重新提交。
     * 是一次真实的全量重算（会消耗请求额度），因此只在设置页里由用户主动触发。
     */
    fun reanalyzeVisible() {
        if (!installed) return
        main.post {
            submittedAt.clear()
            awaiting.clear()
            SignalAnalyzer.clearResults()
            YanwaiBubble.clearAll()
            var count = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                if (bindingOf(view, message) == null) continue
                count++
                handle(view, message, immediate = true)
            }
            MoodLog.i("已对 $count 行可见消息重新提交分析")
        }
    }

    /** 「重试全部失败」：只重新提交**还没有结果**的可见行（成功的保持不动，省额度）。 */
    fun reanalyseFailed() {
        if (!installed) return
        main.post {
            SignalAnalyzer.retryAllFailures()
            var count = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                val input = bindingOf(view, message) ?: continue
                if (MoodStore.get(input.key) != null) continue
                submittedAt.remove(input.key)
                MoodStore.release(input.key)
                count++
                handle(view, message, immediate = true)
            }
            MoodLog.i("已重新提交 $count 行失败的可见消息")
        }
    }

    /** 供设置页/看门狗使用：清空计时器（例如用户手动关闭功能后重新打开）。 */
    fun forgetProgress() {
        submittedAt.clear()
    }
}
