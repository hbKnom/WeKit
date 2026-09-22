package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object AutoEnableSendOriginalMedia : SwitchFeature() {

    override val technicalId = "自动启用发送原图"
    override val nameRes = R.string.feature_auto_enable_send_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_original_media_description

    /**
     * 相册预览页；发送原图的勾选状态在它的 iniView / 数量回调里被反复重建。
     * GalleryEntryUI 是「气泡 / 相机」路径的中转页，它把整个 intent 透传给
     * ImagePreviewUI，所以这两个 flag 必须由它一起带上，否则那条路径拿不到。
     */
    private val PICKER_PAGES = listOf(
        "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
        "com.tencent.mm.plugin.gallery.ui.GalleryEntryUI",
    )

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"

    override fun onEnable() {
        PICKER_PAGES.forEach { name ->
            // toClassOrNull：宿主改名/缺失时不能抛 ClassNotFoundException 把整个 onEnable 打断
            // （一旦抛出，后面的 Hook 全部不会安装，表现就是"功能整条消失"）。
            name.toClassOrNull()?.hookBeforeOnCreate {
                val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
                // Upstream 09-12 also sets WeChat's newer "key_send_raw_image" flag, which is
                // what makes the picker keep original moments images and original video files
                // (send_raw_img alone only covers the chat path).
                activity.intent.putExtra("send_raw_img", true)
                activity.intent.putExtra("key_send_raw_image", true)
            }
        }

        // Keep the "send as original" toggle logically checked so the picker does not drop the
        // raw flag when it refreshes its own checkbox row (选数量变化时宿主会把勾选状态重置)。
        if (!methodUpdateSendAsMediaGroupViews.isPlaceholder) {
            methodUpdateSendAsMediaGroupViews.hookBefore {
                // 该方法的最后一个 boolean 参数就是原图开关；先做类型/越界保护，
                // 参数位对不上的宿主版本直接跳过（宁可不动，也不要改错参数静默失效）。
                if (args.size > 3 && args[3] is Boolean) {
                    args[3] = true
                }
            }
        }
    }

    /**
     * 相册预览页的数量变化回调（包含宿主自身埋点字符串 updateSendAsMediaGroupViews）。
     * 必须锁定 declaredClass：早前只按字符串匹配，可能解析到宿主别处同名埋点的方法，
     * args[3] 便不是原图开关 → 越界/改错参数被 hook 框架吞掉，功能静默失效。
     */
    private val methodUpdateSendAsMediaGroupViews by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            usingEqStrings("updateSendAsMediaGroupViews")
        }
    }
}
