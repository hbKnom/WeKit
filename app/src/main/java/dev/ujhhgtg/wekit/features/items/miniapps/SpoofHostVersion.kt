package dev.ujhhgtg.wekit.features.items.miniapps

import org.json.JSONObject
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object SpoofHostVersion : SwitchFeature(), IResolveDex {

    override val technicalId = "伪装宿主版本"
    override val nameRes = R.string.feature_spoof_host_version_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_spoof_host_version_description

    override fun onEnable() {
        ctorCgiLaunchWxaAppFunc1122.hookBefore {
            args[6] = 9999
        }

        // Upstream 09-12: also stop the official "please update WeChat" page from hijacking the
        // mini-program container. WeChat funnels it through its private URL opener; blanking the
        // url makes it a no-op while leaving every other private open untouched.
        methodPrivateOpenUrl.hookBefore {
            val json = args.getOrNull(1) as? JSONObject ?: return@hookBefore
            val url = json.optString("url")
            if (UPDATE_URLS.any { url.matchesUpdateUrl(it) }) {
                json.put("url", "")
            }
        }
    }

    private fun String.matchesUpdateUrl(base: String): Boolean =
        this == base || startsWith("$base/") || startsWith("$base?") || startsWith("$base#")

    private val UPDATE_URLS = listOf(
        "https://support.weixin.qq.com/update",
        "https://szsupport.weixin.qq.com/update",
    )

    private val methodPrivateOpenUrl by dexMethod {
        matcher {
            paramTypes("org.json.JSONObject", "int")
            returnType = "void"
            usingEqStrings("private_openUrl", "rawUrl", "geta8key_open_webview_appid")
        }
    }

    private val ctorCgiLaunchWxaAppFunc1122 by dexConstructor {
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrand.CgiLaunchWxaApp|func:1122",
                "<init> cgiHash[%d], username[%s] appId[%s] sync[%b] sessionId[%s] instanceId[%s] libVersion[%d], source:%s, launchMode:%d, migrate:%b, fallback:%b"
            )
        }
    }
}
