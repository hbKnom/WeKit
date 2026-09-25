package dev.ujhhgtg.wekit.features.items.chat.jev

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiBubble
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiScanner
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * 言外 · 聊天潜台词助手。
 *
 * 与同目录下的 [dev.ujhhgtg.wekit.features.items.chat.JevChatAssistant] 是两套独立实现：
 * 那个是「把结论当系统消息插回会话」，本功能是「在每条文字气泡下方挂一张分析卡」，
 * 分析维度也更深（8 类场景 / 32 套模板 / 7 个事实维度 / 20+ 候选动作）。
 * 两者互不干扰，可分别开关。
 *
 * 上游 wechatmood 是独立 APK，靠 ContentProvider 桥跨进程读设置、靠 DexKit 自己找绑定点；
 * 本实现落在 WeKit 内，直接复用 [dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi]
 * 的绑定表与 [ModulePrefs] 的 WePrefs 持久化。
 */
object YanwaiFeature : ClickableFeature() {

    override val technicalId = "言外潜台词分析"
    override val nameRes = R.string.feature_yanwai_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_yanwai_description

    override fun onEnable() {
        ModulePrefs.init()
        MoodLog.init(HostInfo.application)
        runCatching {
            YanwaiScanner.install()
            WeLogger.i(TAG, "言外扫描器已挂载")
        }.onFailure {
            WeLogger.e(TAG, "言外扫描器挂载失败", it)
            MoodLog.e("SCANNER_INSTALL_FAILED", it)
        }
    }

    override fun onDisable() {
        runCatching { YanwaiScanner.uninstall() }
        YanwaiBubble.clearAll()
        MoodStore.clear()
        WeLogger.i(TAG, "言外扫描器已卸载")
    }

    override fun onClick(context: ComponentActivity) {
        YanwaiSettings.show(context)
    }

    private const val TAG = "YanwaiFeature"
}
