package dev.ujhhgtg.wekit.features.items.chat.jev.core

import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * 言外设置的持久化后端。
 *
 * 上游 wechatmood 是独立 APK，微信进程要通过 ContentProvider 跨进程读设置，
 * 因此有一整套 SettingsProvider / SettingsSync / SettingsSession 桥。
 * 本模块是 Xposed 模块，设置与聊天 hook 同处微信进程，直接走 [WePrefs] 即可，
 * 桥接层全部去掉；上游 [ApiProfiles] 依赖的 channel_{id}_{key,endpoint,model} 键位保持原样。
 */
object ModulePrefs {

    private const val TAG = "YanwaiPrefs"

    // 与上游同名，ApiProfiles 直接引用
    const val KEY_ENABLED = "yanwai_enabled"
    const val KEY_EXPLORE = "yanwai_explore_mode"
    const val KEY_SHOW_BADGE = "yanwai_show_badge"
    const val KEY_API_KEY = "yanwai_api_key"
    const val KEY_API_BASE = "yanwai_api_base"
    const val KEY_API_PROVIDER = "yanwai_api_provider"
    const val KEY_API_MODEL = "yanwai_api_model"
    // 合并「言外潜台词」与「Jev 聊天决策」后新增（键名沿用 yanwai_ 前缀，老用户配置不丢）
    const val KEY_DISPLAY_MESSAGE = "yanwai_display_message"
    const val KEY_SCOPE_ALL = "yanwai_scope_all"
    const val KEY_SCOPE_TALKERS = "yanwai_scope_talkers"
    const val KEY_SCOPE_TALKER_NAMES = "yanwai_scope_talker_names"
    /** 上下文条数（0-20，默认 10，与上游 [MessagePolicy.MAX_CONTEXT_MESSAGES] 一致）。 */
    const val KEY_CONTEXT_LIMIT = "yanwai_context_limit"
    /** 是否连自己发的消息一起分析（默认只分析对方）。 */
    const val KEY_ANALYZE_SELF = "yanwai_analyze_self"
    /** 分析卡是否默认展开完整解读（默认收起，只留主情绪 + 一条建议）。 */
    const val KEY_CARD_EXPANDED = "yanwai_card_expanded"
    /** 分析卡是否显示「与前几句对比」。 */
    const val KEY_SHOW_TREND = "yanwai_show_trend"
    /**
     * 「回插会话」的新鲜度窗口（秒）。只回插最近这么久之内发生的消息 ——
     * 打开历史会话时本屏十几条老消息会被一起分析，窗口太大就会瞬间插出十几条系统消息。
     */
    const val KEY_INSERT_FRESH_SECONDS = "yanwai_insert_fresh_seconds"
    /** 一次性迁移标记：把第 14 轮之前默认可能开着的「回插会话」强制关一次。 */
    const val KEY_INSERT_OFF_MIGRATED = "yanwai_insert_off_migrated"

    const val DEFAULT_INSERT_FRESH_SECONDS = 60
    const val MIN_INSERT_FRESH_SECONDS = 10
    const val MAX_INSERT_FRESH_SECONDS = 1800

    /**
     * 展示通道 1：在消息下方挂分析卡。
     *
     * 与老键 [KEY_SHOW_BADGE] 是同一个开关（合并前叫「显示徽标」），默认**开** ——
     * 合并后的功能主展示就是这张卡，默认关掉等于装了没反应。
     */
    val displayBubble get() = prefs.getBoolOrDef(KEY_SHOW_BADGE, true)

    /**
     * 展示通道 2：把结论作为系统消息插回会话。
     *
     * **默认关**。这道通道在本轮被用户点名（截图里一屏几十条居中的【潜语 · 平静】）：
     * 即使开着，[dev.ujhhgtg.wekit.features.items.chat.jev.hook.MoodMessageChannel] 也还有
     * 新鲜度窗口、逐会话最小间隔与突发上限三道闸门。
     */
    val displayMessage get() = prefs.getBoolOrFalse(KEY_DISPLAY_MESSAGE)

    /** 是否对所有会话生效（默认 true）。 */
    val scopeAll get() = prefs.getBoolOrDef(KEY_SCOPE_ALL, true)

    /** 当前选定的会话（wxId）。 */
    val scopeTalkers: Set<String> get() = scopeTalkers().toSet()

    /**
     * 写回分析范围。[names] 只用于会话列表的可读展示（[scopeSummary]），
     * 键位与上游一致：talkers / names 都是换行分隔。
     */
    fun setScope(all: Boolean, talkers: Set<String>, names: Map<String, String>) {
        prefs.putBool(KEY_SCOPE_ALL, all)
        val ordered = talkers.toList()
        prefs.putString(KEY_SCOPE_TALKERS, ordered.joinToString("\n"))
        prefs.putString(KEY_SCOPE_TALKER_NAMES, ordered.joinToString("\n") { names[it] ?: it })
    }

    private fun scopeTalkers(): List<String> =
        prefs.getStringOrDef(KEY_SCOPE_TALKERS, "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 是否分析这个会话。
     *
     * 「全部聊天」为默认；即使选了「仅选定聊天」，只要列表是空的也放行 ——
     * 避免用户把开关拨到「仅选定」却还没选会话时，功能看起来像坏了。
     */
    fun inScope(talker: String?): Boolean {
        if (prefs.getBoolOrDef(KEY_SCOPE_ALL, true)) return true
        val list = scopeTalkers()
        if (list.isEmpty()) return true
        if (talker.isNullOrBlank()) return false
        return list.contains(talker)
    }

    fun setDisplayMessage(value: Boolean) = prefs.putBool(KEY_DISPLAY_MESSAGE, value)

    /** 上下文条数：夹在 0..20；0 表示不带前文（只分析这一句）。 */
    val contextLimit get() = prefs.getIntOrDef(KEY_CONTEXT_LIMIT, MessagePolicy.MAX_CONTEXT_MESSAGES)
        .coerceIn(0, MAX_CONTEXT_LIMIT)

    fun setContextLimit(value: Int) = prefs.putInt(KEY_CONTEXT_LIMIT, value.coerceIn(0, MAX_CONTEXT_LIMIT))

    val analyzeSelf get() = prefs.getBoolOrDef(KEY_ANALYZE_SELF, false)

    fun setAnalyzeSelf(value: Boolean) = prefs.putBool(KEY_ANALYZE_SELF, value)

    /** 分析卡默认展开完整解读（默认收起：一行主情绪 + 一条建议，安静且省高度）。 */
    val cardExpanded get() = prefs.getBoolOrFalse(KEY_CARD_EXPANDED)

    fun setCardExpanded(value: Boolean) = prefs.putBool(KEY_CARD_EXPANDED, value)

    /** 分析卡是否显示「与前几句对比」（默认开）。 */
    val showTrend get() = prefs.getBoolOrDef(KEY_SHOW_TREND, true)

    fun setShowTrend(value: Boolean) = prefs.putBool(KEY_SHOW_TREND, value)

    /** 回插会话的新鲜度窗口（秒），夹在 10..1800。 */
    val insertFreshSeconds get() = prefs.getIntOrDef(KEY_INSERT_FRESH_SECONDS, DEFAULT_INSERT_FRESH_SECONDS)
        .coerceIn(MIN_INSERT_FRESH_SECONDS, MAX_INSERT_FRESH_SECONDS)

    fun setInsertFreshSeconds(value: Int) = prefs.putInt(
        KEY_INSERT_FRESH_SECONDS,
        value.coerceIn(MIN_INSERT_FRESH_SECONDS, MAX_INSERT_FRESH_SECONDS),
    )

    /**
     * 一次性把「回插会话」关掉。
     *
     * 上游老实现（合并前的「Jev 聊天决策」）默认就会往会话里插系统消息，用户已经明确要求
     * 「去除这个提醒」。这里只在第一次运行新版时强制关一次，之后就完全由用户自己决定 ——
     * 开关本身保留，想用的人在设置页打开即可。幂等。
     */
    fun migrateInsertOffOnce() {
        if (prefs.getBoolOrFalse(KEY_INSERT_OFF_MIGRATED)) return
        prefs.putBool(KEY_DISPLAY_MESSAGE, false)
        prefs.putBool(KEY_INSERT_OFF_MIGRATED, true)
    }

    const val MAX_CONTEXT_LIMIT = 20

    fun scopeSummary(): String {
        if (prefs.getBoolOrDef(KEY_SCOPE_ALL, true)) return "全部聊天"
        val names = prefs.getStringOrDef(KEY_SCOPE_TALKER_NAMES, "").split('\n').filter { it.isNotBlank() }
        return if (names.isEmpty()) "全部聊天" else "已选 ${names.size} 个聊天"
    }

    private val prefs get() = WePrefs

    /** WePrefs 每次读取都落到底层 SharedPreferences，无需缓存失效，保留该调用点以对齐上游语义。 */
    fun reload(force: Boolean = false) = Unit

    fun init() = Unit

    val enabled get() = prefs.getBoolOrFalse(KEY_ENABLED)
    val exploreMode get() = prefs.getBoolOrFalse(KEY_EXPLORE)
    val showBadge get() = prefs.getBoolOrFalse(KEY_SHOW_BADGE)
    val apiKey get() = prefs.getStringOrDef(KEY_API_KEY, "")
    val canAnalyze get() = enabled && apiKey.isNotBlank()

    fun apiSettings(): ApiSettings = runCatching {
        ApiSettings.fromInput(
            endpoint = prefs.getStringOrDef(KEY_API_BASE, ""),
            apiKey = apiKey,
            providerId = prefs.getStringOrDef(KEY_API_PROVIDER, ""),
            model = prefs.getStringOrDef(KEY_API_MODEL, ""),
        )
    }.getOrElse { ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "") }

    fun setSwitch(key: String, value: Boolean) {
        require(key == KEY_ENABLED || key == KEY_SHOW_BADGE) { "不支持的开关：$key" }
        when (key) {
            KEY_ENABLED -> prefs.putBool(KEY_ENABLED, value)
            KEY_SHOW_BADGE -> prefs.putBool(KEY_SHOW_BADGE, value)
        }
        // 不需要（也不能）调用 WePrefs.save()：companion 上没有该方法，
        // 且 SQLite 实现每次 put 就已经落库。
    }

    /** 上游把运行状态回传给独立 App 的设置页；这里只落模块日志。 */
    fun report(status: String) {
        val line = MoodLog.sanitize(status).take(200)
        val now = System.currentTimeMillis()
        synchronized(REPORT_LOCK) {
            // 去重/限流：原来「潜语分析完成，已缓存 N 条」「已回插解读」这类状态在每次分析后
            // 都写一行，一屏十几条就是十几行；重复内容只记一次，并折叠计数。
            if (line == lastReport && now - lastReportAt < REPORT_DEDUPE_MS) {
                suppressedReports++
                lastReportAt = now
                return
            }
            val folded = suppressedReports
            suppressedReports = 0
            lastReport = line
            lastReportAt = now
            WeLogger.i(TAG, if (folded > 0) "$line（同样内容已折叠 $folded 条）" else line)
        }
    }

    private val REPORT_LOCK = Any()
    private var lastReport: String? = null
    private var lastReportAt = 0L
    private var suppressedReports = 0
    private const val REPORT_DEDUPE_MS = 10_000L
}
