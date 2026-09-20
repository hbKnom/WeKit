package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object AutoEnableSendOriginalMedia : SwitchFeature() {

    override val technicalId = "自动启用发送原图"
    override val nameRes = R.string.feature_auto_enable_send_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_original_media_description

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                // Upstream 09-12 also sets WeChat's newer "key_send_raw_image" flag, which is
                // what makes the picker keep original moments images and original video files
                // (send_raw_img alone only covers the chat path).
                activity.intent.putExtra("send_raw_img", true)
                activity.intent.putExtra("key_send_raw_image", true)
            }
        }

        // Keep the "send as original" toggle logically checked so the picker does not drop the
        // raw flag when it refreshes its own checkbox row.
        methodUpdateSendAsMediaGroupViews.hookBefore {
            args[3] = true
        }
    }

    private val methodUpdateSendAsMediaGroupViews by dexMethod {
        matcher {
            usingEqStrings("updateSendAsMediaGroupViews")
        }
    }
}
