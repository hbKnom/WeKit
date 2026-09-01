package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.widget.TextView
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Label
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.utils.HookParam

/**
 * 会话自定义头衔 · 上（忠实还原原脚本「会话自定义头衔」的注入位置）
 *
 * 注入位置与原脚本一致：在消息昵称（userTV）上方注入一个独立小标签 View，
 * 群聊显示在昵称上方，私聊同样生效（私聊昵称不可见时标签仍可见）。
 * 无配置时显示默认头衔「带刀侍卫」（原脚本行为）。
 *
 * 与「头衔下」完全独立：各自菜单按钮、各自存储（memberTitlesV9 / memberTitlesV9Up），互不干扰。
 */
object ChatTitleTagUp : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "会话自定义头衔上"
    override val nameRes = R.string.feature_chat_title_tag_up_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_title_tag_up_description

    private const val TAG = "ChatTitleTagUp"
    private const val MENU_ID = 777273
    private const val PREF_KEY = "memberTitlesV9Up"
    private const val DEFAULT_TITLE = "带刀侍卫"
    private const val OVERLAY_KEY = "up"

    private val store = TitleStore(PREF_KEY, TAG)

    override fun onEnable() {
        store.load()
        WeChatMessageViewApi.addListener(this)
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    // ---------------- 菜单 ----------------

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "头衔上",
            drawable = MenuIcons.res(R.drawable.ic_menu_title_group),
            imageVector = MaterialSymbols.Outlined.Label,
            isSupported = { msg -> !msg.isSelfSender },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                val group = msgInfo.isInGroupChat
                val room = msgInfo.talker
                val wxid = if (group) msgInfo.sender else msgInfo.talker
                if (wxid.isEmpty()) {
                    showToast("未取得对方发送者，请长按对方消息后重试")
                    return@MenuItem
                }
                showTitleEditDialog(view.context, store, group, room, wxid, "头衔上", DEFAULT_TITLE)
            },
        )
    )

    // ---------------- 渲染（原版注入位置：昵称上方独立标签） ----------------

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (e: Exception) {
            return
        }
        if (msgInfo.isSelfSender) {
            hideOverlay(view)
            return
        }

        val group = msgInfo.isInGroupChat
        val room = msgInfo.talker
        val wxid = if (group) msgInfo.sender else msgInfo.talker
        if (wxid.isEmpty() || wxid == WeApi.selfWxId) {
            hideOverlay(view)
            return
        }

        val tag = view.tag
        val textView = tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView ?: return

        val entry = store.entryFor(group, room, wxid)
        val title = entry?.title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE

        val titleView = TitleTagOverlay.getOrCreate(textView, OVERLAY_KEY) { applyTitleStyle(it, entry ?: TitleEntry(DEFAULT_TITLE, 0)) }
            ?: return
        titleView.text = title
        titleView.visibility = View.VISIBLE
    }

    private fun hideOverlay(view: View) {
        val tv = view.tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView ?: return
        TitleTagOverlay.hide(tv, OVERLAY_KEY)
    }
}
