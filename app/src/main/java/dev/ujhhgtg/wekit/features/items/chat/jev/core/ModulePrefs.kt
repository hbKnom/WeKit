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

    /**
     * 展示通道 1：在消息下方挂分析卡。
     *
     * 与老键 [KEY_SHOW_BADGE] 是同一个开关（合并前叫「显示徽标」），默认**开** ——
     * 合并后的功能主展示就是这张卡，默认关掉等于装了没反应。
     */
    val displayBubble get() = prefs.getBoolOrDef(KEY_SHOW_BADGE, true)

    /** 展示通道 2：把结论作为系统消息插回会话（默认关；复用同一份分析结果，不额外请求模型）。 */
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
        WeLogger.i(TAG, MoodLog.sanitize(status).take(200))
    }
}
