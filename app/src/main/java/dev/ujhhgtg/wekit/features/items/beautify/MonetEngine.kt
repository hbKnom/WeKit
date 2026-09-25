package dev.ujhhgtg.wekit.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.loader.ResourcesProvider
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.MonetPaletteFactory
import dev.ujhhgtg.wekit.ui.utils.theme.SeedResolver
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.monet.MonetApkResourceGraphLoader
import dev.ujhhgtg.wekit.utils.monet.MonetBindings
import dev.ujhhgtg.wekit.utils.monet.MonetBubbleStyle
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import dev.ujhhgtg.wekit.utils.monet.MonetDexEvidenceCollector
import dev.ujhhgtg.wekit.utils.monet.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.utils.monet.MonetResourceResolver
import dev.ujhhgtg.wekit.utils.monet.MonetResolveProgress
import dev.ujhhgtg.wekit.utils.monet.MonetResolveResult
import dev.ujhhgtg.wekit.utils.monet.MonetResolveStage
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimeState
import dev.ujhhgtg.wekit.utils.monet.MonetStructureMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.path.div

/**
 * Recolours WeChat's own UI resources by loading a **runtime resource package** into WeChat's
 * `AssetManager`.
 *
 * Rebuilt for 09-25. The previous design generated signed RRO APKs and shipped them inside a Magisk
 * module, which meant rooting the device, a reboot, and a separate "module generator" feature. The
 * current one writes `runtime-<fingerprint>-<options>.apk` under the module cache and hands it to
 * `ResourcesProvider.loadFromApk`, so:
 *
 *  - no root, no Magisk/KernelSU/APatch, no reboot;
 *  - changing an option regenerates the package and re-injects it live;
 *  - we only need `android.content.res.loader`, i.e. Android 11 (API 30) — below that the feature
 *    reports [R.string.monet_unsupported] and stays off.
 *
 * The palette follows the WeKit theme (see [R.string.monet_color_source_summary]); WeChat's own
 * `R.color`/`R.drawable` entries are overridden with references to the platform's Material You
 * colours, so the recolouring tracks the wallpaper the same way the system does.
 *
 * A handful of WeChat components (e.g. `MMSwitchBtn`) hold the brand green as a **compiled constant**
 * rather than a resource, so the legacy in-place swaps are still installed as a fallback.
 */
object MonetEngine : ClickableFeature() {

    override val technicalId = "莫奈引擎"
    override val nameRes = R.string.feature_monet_engine_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_monet_engine_description

    private const val TAG = "MonetEngine"

    /** WeChat's hardcoded brand green — the pixels a resource overlay cannot reach. */
    private const val DEFAULT_COLOR = -16268960 // 0xFF07C160

    /** 把品牌绿编译进 Java 字段/绘制调用的宿主组件。 */
    private const val SWITCH_BTN_CLASS = "com.tencent.mm.ui.widget.MMSwitchBtn"

    /** 注入后多久算「活过来了」（没活到这一刻就重启 = 疑似被注入的包搞崩）。 */
    private const val CONFIRM_DELAY_MS = 30_000L

    /** 应用后多少毫秒内又重启才算「疑似崩溃」：正常手动重开微信不会这么快。 */
    private const val RESTART_WINDOW_MS = 90_000L

    /** 连续这么多次疑似崩溃就自动停用注入（等用户重新解析再放行）。 */
    private const val MAX_FAIL_STREAK = 2

    const val KEY_BUBBLE_STYLE = "monet_bubble_style"
    const val KEY_MULTI_SCENE_CORNERS = "monet_multi_scene_corners"
    const val KEY_ERROR_COLORS = "monet_error_colors"

    private var bubbleStyleName by prefOption(KEY_BUBBLE_STYLE, MonetBubbleStyle.MODERN.name)
    private var multiSceneCornersPref by prefOption(KEY_MULTI_SCENE_CORNERS, false)
    private var errorColorsPref by prefOption(KEY_ERROR_COLORS, false)

    /**
     * DEX 证据提供者：歧义角色交给 [MonetDexEvidenceCollector]（旧版成功运行时就是这么消歧的）。
     *
     * DexKit 没起来（未 root / native 没加载）或扫描失败都只返回空表 —— 调用方
     * [MonetStructureMatcher.resolveCandidateIds] 拿到空表会退化成纯结构消歧，
     * 绝不会因为「拿不到证据」把整次解析打断成「解析出错」。
     */
    private val dexEvidenceProvider = MonetDexEvidenceProvider { candidates ->
        runCatching { MonetDexEvidenceCollector.collect(candidates) }
            .onFailure { WeLogger.w(TAG, "DEX 证据收集失败，改用结构消歧", it) }
            .getOrDefault(emptyList())
    }

    private val bindingsFile: File by lazy { (KnownPaths.moduleData / "monet_bindings.json").toFile() }
    private val runtimeDir: File by lazy { (KnownPaths.moduleCache / "monet").toFile() }

    /**
     * 绑定缓存的第二份副本，和运行时包放在同一个目录。
     *
     * 实机日志（2026-09-25）里 `moduleData/monet_bindings.json` 每次启动都读不回来，导致**每次冷启动
     * 都全量重解析**（单次 100 秒以上，期间主线程被拖到 2.6 秒延迟，用户看到的就是卡顿）。
     * 运行时包写在 `runtimeDir` 里是能被写成功的，所以缓存也放这里，两边都写、任一份都能读。
     */
    private val bindingsCacheFile: File by lazy { File(runtimeDir, "monet_bindings.json") }

    /** 注入自保状态：见 [MonetRuntimeState]。 */
    private val runtimeStateFile: File by lazy { File(runtimeDir, "monet_runtime_state.json") }

    private val _progress = MutableStateFlow<MonetResolveProgress?>(null)
    val progress: StateFlow<MonetResolveProgress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<MonetResolveResult?>(null)
    val result: StateFlow<MonetResolveResult?> = _result.asStateFlow()

    private val _runtimePackage = MutableStateFlow<File?>(null)
    val runtimePackage: StateFlow<File?> = _runtimePackage.asStateFlow()

    @Volatile
    private var resolving = false

    /** Whether the platform exposes the runtime resource loader this engine is built on. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    var bubbleStyle: MonetBubbleStyle
        get() = MonetBubbleStyle.entries.firstOrNull { it.name == bubbleStyleName }
            ?: MonetBubbleStyle.MODERN
        set(value) {
            if (bubbleStyleName == value.name) return
            bubbleStyleName = value.name
            onOptionsChanged()
        }

    var multiSceneCorners: Boolean
        get() = multiSceneCornersPref
        set(value) {
            if (multiSceneCornersPref == value) return
            multiSceneCornersPref = value
            onOptionsChanged()
        }

    var errorColors: Boolean
        get() = errorColorsPref
        set(value) {
            if (errorColorsPref == value) return
            errorColorsPref = value
            onOptionsChanged()
        }

    /** `resolved / total` for the currently loaded binding set, or `null` when never analysed. */
    val resolvedCount: Int?
        get() = cachedBindings()?.let { MonetStructureMatcher.roleIds.size - it.unresolved.size }

    val totalCount: Int
        get() = MonetStructureMatcher.roleIds.size

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (!newState || isSupported) return true
        context.showUnsupportedToast()
        return false
    }

    override fun onEnable() {
        if (!isSupported) {
            WeLogger.w(TAG, "ResourcesLoader needs API 30, not enabling")
            return
        }
        WeLogger.i(
            TAG,
            "monet onEnable build=${HostInfo.versionName} (${HostInfo.versionCode}) " +
                "uid-owner=${Process.myUid() / 100000}",
        )
        runCatching { installBrandColorHooks() }
            .onFailure { WeLogger.w(TAG, "brand-colour fallback hooks unavailable", it) }
        startResolve(force = false)
    }

    override fun onDisable() {
        _progress.value = null
        _result.value = null
        _runtimePackage.value = null
        // 注入界面的配色跟着一起熄火，避免「原生已经回到旧配色、WeKit 组件还是莫奈色」的反向割裂
        MonetColors.applied.value = null
    }

    override fun onClick(context: ComponentActivity) {
        if (!isSupported) {
            context.showUnsupportedDialog()
            return
        }
        showOptionsDialog(context as Activity)
    }

    private fun onOptionsChanged() {
        if (!isActive || !isSupported) return
        startResolve(force = true)
    }

    /**
     * Analyses WeChat's resources (or reuses the cached binding set when the APK set is unchanged)
     * and injects the resulting runtime package.
     */
    fun startResolve(force: Boolean) {
        if (!isSupported) {
            WeLogger.w(TAG, "ResourcesLoader needs API 30, not enabling")
            return
        }
        if (resolving) {
            WeLogger.d(TAG, "resolution already running, skipping")
            return
        }
        resolving = true
        val app = HostInfo.application
        val info = app.applicationInfo
        val paths = buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let(::addAll)
        }
        thread(name = "MonetResolverThread") {
            // 解析全程在后台线程、并降到后台优先级：解析要做 APK 资源表解析，
            // 不能跟微信启动抢 CPU（用户明确要求「不影响微信的流畅运行」）。
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                val startedAt = System.nanoTime()
                val fingerprint = MonetResourceResolver.fingerprint(
                    paths,
                    HostInfo.versionCode,
                    HostInfo.versionName,
                )
                val cached = cachedBindings()?.takeIf { !force && it.fingerprint == fingerprint }
                val packageFile = runtimeFile(fingerprint)
                if (!guardRuntimeInjection(force, packageFile)) return@thread
                if (cached != null && packageFile.isFile) {
                    WeLogger.i(
                        TAG,
                        "reusing cached bindings ${cached.roles.size} roles (unresolved ${cached.unresolved.size})",
                    )
                    applyRuntimePackage(packageFile)
                    recordRuntimeApplied(packageFile)
                    publishPalette()
                    _result.value = MonetResolveResult.Success(cached, packageFile)
                    WeLogger.i(
                        TAG,
                        "缓存命中，本次启动未做资源解析，用时 ${(System.nanoTime() - startedAt) / 1_000_000} ms",
                    )
                    return@thread
                }
                WeLogger.i(
                    TAG,
                    "缓存未命中（bindings=${cached?.fingerprint ?: "无"} 期望=$fingerprint，包存在=${packageFile.isFile}），开始解析",
                )

                _progress.value = MonetResolveProgress(
                    MonetResolveStage.LOADING_APKS,
                    "WeChat resource APKs: ${paths.size}",
                    0,
                    paths.size,
                )
                val graph = MonetApkResourceGraphLoader.load(
                    apkPaths = paths.map(::File),
                    targetPackage = info.packageName,
                ) { detail, completed, total ->
                    _progress.value = MonetResolveProgress(
                        MonetResolveStage.BUILDING_RESOURCE_GRAPH,
                        detail,
                        completed.takeIf { total > 0 },
                        total.takeIf { it > 0 },
                    )
                }
                val resolution = MonetResourceResolver.resolve(
                    graph = graph,
                    resources = app.resources,
                    fingerprint = fingerprint,
                    bubbleStyle = bubbleStyle,
                    multiSceneCorners = multiSceneCorners,
                    errorColors = errorColors,
                    // 平台没有 Material You token 时（安卓 11 / 部分 ROM）用主题种子色板顶上，
                    // 旧实现在这种情况下直接抛错 = 整次解析失败。
                    fallbackPalette = runCatching { MonetPaletteFactory.fromTheme(app) }.getOrNull(),
                    // 「哪些角色该配对哪个资源」的歧义用 DEX 证据消歧（旧版成功运行时的做法）。
                    // DexKit 不可用／扫描失败都只退化成结构消歧，不影响其余角色。
                    dexProvider = dexEvidenceProvider,
                ) { completed, total, detail ->
                    _progress.value = MonetResolveProgress(
                        MonetResolveStage.RESOLVING_ROLES,
                        detail,
                        completed,
                        total,
                    )
                }
                _progress.value = MonetResolveProgress(
                    MonetResolveStage.BUILDING_PACKAGE,
                    "runtime-${packageFile.name}",
                    null,
                    null,
                )
                runtimeDir.mkdirs()
                if (resolution.plan.isEmpty) {
                    // 一个角色都没解析出来：如实报告，而不是写个空包说「成功」
                    error(
                        "没有解析出可应用的资源：微信资源结构可能与当前规则不匹配" +
                            "（可在设置里重新解析）",
                    )
                }
                // 写包时 XML 里的 `@type/name` 需要真实 id：先在本次计划里找（合成资源），
                // 再回到宿主资源图里找，最后才跳过该属性。绝不再用 requireNotNull —— 一个
                // 引用查不到就整包失败，等于莫奈完全失效。
                val hostReference: (String, String) -> Int? = { type, name ->
                    graph.node(MonetResourceKey(type, name))?.id
                }
                if (!MonetRuntimePackageWriter.write(
                        packageFile,
                        info.packageName,
                        resolution.plan,
                        hostReference,
                    )
                ) {
                    // 条目 id 校验没过 = 覆盖会落到别的资源上，写了就是闪退，宁可这次不注入。
                    error(
                        "运行时资源包构建失败（资源 id 校验未通过），已中止本次注入以免影响微信运行" +
                            "（可在设置里重新解析）",
                    )
                }
                persistBindings(resolution.bindings)
                applyRuntimePackage(packageFile)
                recordRuntimeApplied(packageFile)
                publishPalette()
                _progress.value = null
                _result.value = MonetResolveResult.Success(resolution.bindings, packageFile)
                WeLogger.i(
                    TAG,
                    "解析并注入完成，用时 ${(System.nanoTime() - startedAt) / 1_000_000} ms",
                )
            } catch (error: Throwable) {
                val stage = _progress.value?.stage ?: MonetResolveStage.LOADING_APKS
                WeLogger.e(TAG, "resource analysis failed during $stage", error)
                _progress.value = null
                _result.value = MonetResolveResult.Failure(
                    MonetResolveProgress(stage, stage.label),
                    error.message ?: error.toString(),
                )
            } finally {
                resolving = false
            }
        }
    }

    private fun runtimeFile(fingerprint: String): File {
        val options = "${bubbleStyle.name}-$multiSceneCorners-$errorColors"
        val hash = options.hashCode().toUInt().toString(16)
        return File(runtimeDir, "runtime-$fingerprint-$hash.apk")
    }

    private fun applyRuntimePackage(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val resources = HostInfo.application.resources
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            // 运行时包是「resources.arsc + res/*.xml」打出来的 APK（无 AndroidManifest、不安装），
            // 必须走 loadFromApk：loadFromTable 期望的是**裸 .arsc** fd，且要额外传 AssetsProvider。
            val provider = ResourcesProvider.loadFromApk(descriptor)
            val loader = android.content.res.loader.ResourcesLoader().apply { addProvider(provider) }
            resources.addLoaders(loader)
        }
        _runtimePackage.value = file
        WeLogger.i(TAG, "applied ${file.name} (${file.length()} bytes)")
    }

    /**
     * 把引擎色板发布给「WeKit 注入微信界面的组件」使用（见 [MonetColors]）。
     *
     * 色板里存的是 `android.R.color.system_*` 的**资源引用**，注入组件不能用引用，
     * 所以在这里统一解析成具体 ARGB。注入界面读的是 Compose 状态，发布后立即重组。
     */
    private fun publishPalette() {
        val app = HostInfo.application
        runCatching {
            val raw = MonetResourceResolver.overlayPalette(
                app.resources,
                runCatching { MonetPaletteFactory.fromTheme(app) }.getOrNull(),
            )
            MonetColors.applied.value = MonetResourceResolver.resolveArgb(app.resources, raw)
        }.onFailure { WeLogger.w(TAG, "cannot publish monet palette for injected UI", it) }
    }

    private fun cachedBindings(): MonetBindings? {
        val candidates = listOf(bindingsCacheFile, bindingsFile).filter { it.isFile }
        if (candidates.isEmpty()) {
            WeLogger.d(
                TAG,
                "no cached bindings at ${bindingsCacheFile.absolutePath} / ${bindingsFile.absolutePath}",
            )
            return null
        }
        candidates.forEach { file ->
            runCatching {
                return DefaultJson.decodeFromString(MonetBindings.serializer(), file.readText())
            }.onFailure { WeLogger.w(TAG, "cannot read cached bindings ${file.absolutePath}", it) }
        }
        return null
    }

    private fun persistBindings(bindings: MonetBindings) {
        val json = runCatching {
            DefaultJson.encodeToString(MonetBindings.serializer(), bindings)
        }.onFailure { WeLogger.w(TAG, "cannot encode bindings", it) }.getOrNull() ?: return
        listOf(bindingsCacheFile, bindingsFile).forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json)
            }.onFailure { WeLogger.w(TAG, "cannot persist bindings to ${file.absolutePath}", it) }
        }
    }

    /**
     * 注入前的自保闸门：**绝不允许出现「一启动就闪退、重启又应用同一个坏包」的死循环**。
     *
     * 判断依据见 [MonetRuntimeState]：上一个包应用后没等到确认就重启（窗口 [RESTART_WINDOW_MS]）
     * 记为一次疑似；连续 [MAX_FAIL_STREAK] 次就直接不注入，并如实告诉用户，等用户在设置里点
     * 「重新解析」（force=true）再放行。任何异常都当作「没有疑似」，绝不因为自保逻辑本身挡住功能。
     */
    private fun guardRuntimeInjection(force: Boolean, packageFile: File): Boolean {
        if (force) {
            writeRuntimeState(MonetRuntimeState())
            return true
        }
        val state = readRuntimeState()
        if (!state.suspiciousRestart(System.currentTimeMillis(), RESTART_WINDOW_MS, packageFile.name)) {
            return true
        }
        val streak = state.failStreak + 1
        writeRuntimeState(state.copy(failStreak = streak))
        if (streak < MAX_FAIL_STREAK) {
            WeLogger.w(TAG, "上次注入后微信很快就退出了（疑似 $streak 次），本次仍注入但会更谨慎")
            return true
        }
        WeLogger.e(TAG, "莫奈运行时包连续 $streak 次疑似导致微信异常退出，已自动停用注入")
        _progress.value = null
        _result.value = MonetResolveResult.Failure(
            MonetResolveProgress(MonetResolveStage.BUILDING_PACKAGE, "runtime injection suspended"),
            "莫奈取色已在本次启动停用：运行时资源包连续 $streak 次疑似导致微信异常退出。" +
                "请在设置里点「重新解析」重试；若仍然闪退，请先关闭莫奈引擎。",
        )
        return false
    }

    /** 记录「刚应用了哪个包」，作为下次启动判断是否疑似崩溃的依据。 */
    private fun recordRuntimeApplied(packageFile: File) {
        writeRuntimeState(
            MonetRuntimeState(
                packageName = packageFile.name,
                appliedAt = System.currentTimeMillis(),
                failStreak = readRuntimeState().failStreak,
            ),
        )
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    thread(name = "MonetConfirm") {
                        writeRuntimeState(
                            readRuntimeState().copy(confirmedAt = System.currentTimeMillis()),
                        )
                    }
                }
            }, CONFIRM_DELAY_MS)
        }.onFailure { WeLogger.w(TAG, "cannot schedule runtime confirm", it) }
    }

    private fun readRuntimeState(): MonetRuntimeState = runCatching {
        if (runtimeStateFile.isFile) {
            DefaultJson.decodeFromString(MonetRuntimeState.serializer(), runtimeStateFile.readText())
        } else {
            MonetRuntimeState()
        }
    }.getOrElse { MonetRuntimeState() }

    private fun writeRuntimeState(state: MonetRuntimeState) {
        runCatching {
            runtimeDir.mkdirs()
            runtimeStateFile.writeText(
                DefaultJson.encodeToString(MonetRuntimeState.serializer(), state),
            )
        }.onFailure { WeLogger.w(TAG, "cannot persist runtime state", it) }
    }

    // ------------------------------------------------------------------------------------------
    // Legacy fallback: components that compile the brand green in rather than reading a resource.
    // ------------------------------------------------------------------------------------------

    private fun installBrandColorHooks() {
        // MMSwitchBtn caches the brand green in plain int fields at construction time. 其中一部分字段
        // 声明在父类上（旧版引擎是遍历继承链查的，正是它让设置页开关跟着换色），所以这里也走继承链。
        runCatching {
            val switchClass = SWITCH_BTN_CLASS.toClass()
            switchClass.constructors.forEach { ctor ->
                ctor.hookAfter {
                    val btn = thisObject ?: return@hookAfter
                    var type: Class<*>? = switchClass
                    while (type != null && type != Any::class.java) {
                        type.declaredFields.forEach { field ->
                            if (field.type != Int::class.javaPrimitiveType) return@forEach
                            field.isAccessible = true
                            val current = runCatching { field.getInt(btn) }.getOrNull() ?: return@forEach
                            if (current == DEFAULT_COLOR) runCatching { field.setInt(btn, accentColor) }
                        }
                        type = type.superclass
                    }
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook MMSwitchBtn failed", it) }

        // GradientDrawable/PaintDrawable fills (incl. WeChat's green button shapes) go through
        // Paint.setColor, so the brand green can be swapped centrally here.
        runCatching {
            Paint::class.java.reflekt().firstMethod {
                name = "setColor"
                parameters(Int::class)
            }.hookBefore {
                val color = args.getOrNull(0) as? Int ?: return@hookBefore
                if (color == DEFAULT_COLOR) args[0] = accentColor
            }
        }.onFailure { WeLogger.w(TAG, "hook Paint.setColor failed", it) }

        // ColorDrawable paints through Canvas.drawColor, so Paint.setColor never sees it.
        runCatching {
            ColorDrawable::class.java.reflekt().firstMethod {
                name = "setColor"
                parameters(Int::class)
            }.hookBefore {
                val color = args.getOrNull(0) as? Int ?: return@hookBefore
                if (color == DEFAULT_COLOR) args[0] = accentColor
            }
        }.onFailure { WeLogger.w(TAG, "hook ColorDrawable.setColor failed", it) }

        // GradientDrawable 承载微信绝大多数「按钮 / 开关 / 标签」底色，这些 shape 是代码里
        // setColor(品牌绿) 出来的，不是资源引用 —— 覆盖包碰不到，只能在这里换。
        runCatching {
            GradientDrawable::class.java.reflekt().firstMethod {
                name = "setColor"
                parameters(Int::class)
            }.hookBefore {
                val color = args.getOrNull(0) as? Int ?: return@hookBefore
                if (color == DEFAULT_COLOR) args[0] = accentColor
            }
        }.onFailure { WeLogger.w(TAG, "hook GradientDrawable.setColor failed", it) }
    }

    /** Accent applied to the compiled-in brand green; mirrors the primary the engine writes. */
    private val accentColor: Int
        get() = runCatching { HostInfo.application.resources.getColor(android.R.color.system_accent1_500, null) }
            .getOrDefault(0xFF07C160.toInt())

    // ------------------------------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------------------------------

    @SuppressLint("DiscouragedApi")
    private fun Context.showUnsupportedToast() =
        android.widget.Toast.makeText(this, R.string.monet_unsupported, android.widget.Toast.LENGTH_SHORT).show()

    private fun Context.showUnsupportedDialog() = showComposeDialog(this) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.monet_options_title)) },
            text = { Text(stringResource(R.string.monet_unsupported)) },
            confirmButton = { Button(onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }

    private fun showOptionsDialog(activity: Activity) = showComposeDialog(activity) {
        val progress by MonetEngine.progress.collectAsState()
        val result by MonetEngine.result.collectAsState()
        AlertDialogContent(
            title = {
                Text(stringResource(if (progress != null) R.string.monet_resolve_title else R.string.monet_options_title))
            },
            text = {
                Column {
                    Text(stringResource(R.string.feature_monet_engine_description), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))

                    Text(stringResource(R.string.monet_status), style = MaterialTheme.typography.titleSmall)
                    MonetStatusLine(progress, result)

                    Spacer(Modifier.height(12.dp))
                    RadioOption(stringResource(R.string.monet_bubble_modern), MonetEngine.bubbleStyle == MonetBubbleStyle.MODERN) {
                        MonetEngine.bubbleStyle = MonetBubbleStyle.MODERN
                    }
                    RadioOption(stringResource(R.string.monet_bubble_classic), MonetEngine.bubbleStyle == MonetBubbleStyle.CLASSIC) {
                        MonetEngine.bubbleStyle = MonetBubbleStyle.CLASSIC
                    }
                    RadioOption(stringResource(R.string.monet_bubble_pro), MonetEngine.bubbleStyle == MonetBubbleStyle.PRO) {
                        MonetEngine.bubbleStyle = MonetBubbleStyle.PRO
                    }

                    ToggleRow(stringResource(R.string.monet_multi_scene_corners), MonetEngine.multiSceneCorners) {
                        MonetEngine.multiSceneCorners = it
                    }
                    ToggleRow(stringResource(R.string.monet_error_colors), MonetEngine.errorColors) {
                        MonetEngine.errorColors = it
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.monet_color_source_summary), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button({ MonetEngine.startResolve(force = true) }) { Text(stringResource(R.string.monet_reresolve)) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }

    @Composable
    private fun MonetStatusLine(
        progress: MonetResolveProgress?,
        result: MonetResolveResult?,
    ) {
        when {
            progress != null -> {
                Text(stringResource(R.string.monet_status_running), style = MaterialTheme.typography.titleSmall)
                Text(progress.detail, style = MaterialTheme.typography.bodySmall)
                val completed = progress.completed
                val total = progress.total
                Spacer(Modifier.height(4.dp))
                if (completed != null && total != null) {
                    LinearProgressIndicator(
                        progress = { completed.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$completed/$total",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            result is MonetResolveResult.Failure -> Text(
                stringResource(R.string.monet_resolve_failed, result.progress.stage.label, result.message),
                style = MaterialTheme.typography.bodySmall,
            )

            result is MonetResolveResult.Success -> Text(
                stringResource(
                    R.string.monet_status_summary,
                    MonetEngine.totalCount - result.bindings.unresolved.size,
                    MonetEngine.totalCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            else -> Text(stringResource(R.string.monet_status_missing), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected, onSelect)
            Text(label)
        }
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onChange)
        }
    }
}
