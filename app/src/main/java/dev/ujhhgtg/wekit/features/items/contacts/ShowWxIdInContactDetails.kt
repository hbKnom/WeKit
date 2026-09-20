package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeContactHeaderApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.system.localizedSystemString
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Shows the WeChat ID on contact cards and in group detail lists.
 *
 * Upstream 09-12 widened this from "profile header only" to also inject a tappable row into the
 * contact / chatroom detail list, with tap-to-copy.
 */
object ShowWxIdInContactDetails : SwitchFeature(),
    WeContactHeaderApi.Provider,
    WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "显示微信 ID"
    override val nameRes = R.string.feature_show_wx_id_in_contact_details_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_wx_id_in_contact_details_description

    private const val ITEM_KEY = "wekit_contact_wx_id"

    private fun wxIdText(activity: Activity): String? = activity.currentWxId

    override fun getHeaderText(activity: Activity): String = activity.localizedContactsString(
        R.string.contacts_wechat_id_value,
        wxIdText(activity) ?: activity.localizedContactsString(R.string.contacts_get_failed),
    )

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val wxId = wxIdText(activity) ?: return emptyList()
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = ITEM_KEY,
                title = activity.localizedContactsString(R.string.feature_show_wx_id_in_contact_details_name),
                summary = wxId,
            ),
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != ITEM_KEY) return false
        val wxId = wxIdText(activity) ?: return true
        copyToClipboard(activity, wxId)
        showToast(activity, activity.localizedSystemString(R.string.copied_to_clipboard))
        return true
    }

    override fun onEnable() {
        WeContactHeaderApi.addProvider(this)
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactHeaderApi.removeProvider(this)
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
