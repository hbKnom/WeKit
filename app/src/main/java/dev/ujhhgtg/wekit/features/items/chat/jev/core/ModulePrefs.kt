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
