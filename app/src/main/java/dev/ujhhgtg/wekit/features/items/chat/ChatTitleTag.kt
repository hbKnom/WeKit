package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Label
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ColorPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.math.roundToInt

/**
 * 会话自定义头衔（WeKit 原生移植）
 *
 * 忠实还原脚本「会话自定义头衔」的功能：长按对方消息设置头衔，消息昵称旁显示头衔标签。
 *
 * 相对原脚本的稳定性改造（解决滑动卡顿）：
 *  - 原脚本在昵称 bind 回调里动态 addView 一个独立 TextView，每次 bind 都触发重布局，是卡顿根源。
 *    这里改为复用 WeKit 的 [WeChatMessageViewApi] 视图监听 + 前缀 ReplacementSpan 渲染，
 *    直接把头衔绘制进昵称 TextView 的文本流里，无 addView / 无重布局，滑动零额外开销。
 *  - 头衔样式扩展为 8 种预设（原 4 种 + 4 种新色），并支持自定义颜色覆盖。
 *  - 持久化沿用原脚本 memberTitlesV9 的 Base64 格式，已保存的配置可直接读取。
 */
object ChatTitleTag : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "会话自定义头衔"
    override val nameRes = R.string.feature_chat_title_tag_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_title_tag_description

    private const val TAG = "ChatTitleTag"
    private const val MENU_ID = 777268
    private const val PREF_KEY = "memberTitlesV9"
    private const val SEP = "\u0001"
    private const val DEFAULT_TITLE = "带刀侍卫"

    // ---------------- 样式（原 4 种 + 扩展 4 种，均支持渐变） ----------------

    private data class TitleStyle(val name: String, val bgStart: Int, val bgEnd: Int, val fg: Int)

    private val PRESETS = listOf(
        TitleStyle("鎏金侍卫", 0xFF80520F.toInt(), 0xFFD9A441.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("赤焰金章", 0xFFD32F2F.toInt(), 0xFFFF9800.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("翡翠守卫", 0xFF00695C.toInt(), 0xFF43A047.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("星夜密令", 0xFF1A237E.toInt(), 0xFF8E24AA.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("樱花落", 0xFFE91E63.toInt(), 0xFFFF80AB.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("暗夜蔷薇", 0xFF4A148C.toInt(), 0xFF7B1FA2.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("深海流光", 0xFF0D47A1.toInt(), 0xFF0288D1.toInt(), 0xFFFFFFFF.toInt()),
        TitleStyle("墨玉鎏金", 0xFF37474F.toInt(), 0xFF607D8B.toInt(), 0xFFFFFFFF.toInt()),
    )

    private data class TitleEntry(
        val title: String,
        val style: Int,
        val customBgStart: Int? = null,
        val customBgEnd: Int? = null,
        val customFg: Int? = null,
    )

    private val titles = java.util.concurrent.ConcurrentHashMap<String, TitleEntry>()

    override fun onEnable() {
        loadTitles()
        WeChatMessageViewApi.addListener(this)
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    // ---------------- 持久化（兼容原脚本 memberTitlesV9） ----------------

    private fun encode(value: String): String =
        android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    private fun decode(value: String): String =
        try {
            String(android.util.Base64.decode(value, android.util.Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

    private fun normalizeStyle(style: Int) = if (style < 0 || style >= PRESETS.size) 0 else style

    private fun keyOf(group: Boolean, room: String, wxid: String): String =
        (if (group) "G" else "P") + SEP + (if (group) room else "") + SEP + wxid

    private fun loadTitles() {
        titles.clear()
        val raw = WePrefs.getString(PREF_KEY) ?: return
        if (raw.isBlank()) return
        try {
            for (row in raw.split("\n")) {
                val p = row.split("|")
                if (p.size < 5) continue
                val group = decode(p[0]) == "G"
                val room = decode(p[1])
                val wxid = decode(p[2])
                val title = decode(p[3]).trim()
                val style = decode(p[4]).toIntOrNull() ?: 0
                if (wxid.isEmpty()) continue
                titles[keyOf(group, room, wxid)] = TitleEntry(title, normalizeStyle(style))
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "loadTitles failed", e)
        }
    }

    private fun saveTitles() {
        try {
            val sb = StringBuilder()
            for ((key, entry) in titles) {
                val p = key.split(SEP)
                if (p.size != 3) continue
                val group = p[0] == "G"
                val room = p[1]
                val wxid = p[2]
                if (wxid.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(encode(if (group) "G" else "P")).append("|")
                    .append(encode(room)).append("|")
                    .append(encode(wxid)).append("|")
                    .append(encode(entry.title)).append("|")
                    .append(encode(normalizeStyle(entry.style).toString()))
            }
            WePrefs.putString(PREF_KEY, sb.toString())
        } catch (e: Exception) {
            WeLogger.e(TAG, "saveTitles failed", e)
            showToast("头衔保存失败，请查看日志")
        }
    }

    private fun entryFor(group: Boolean, room: String, wxid: String): TitleEntry? =
        titles[keyOf(group, room, wxid)]

    private fun displayName(group: Boolean, room: String, wxid: String): String {
        val name = if (group) {
            WeDatabaseApi.getGroupMemberDisplayName(room, wxid)
        } else {
            WeDatabaseApi.getDisplayName(wxid)
        }
        return name.ifBlank { wxid }
    }

    // ---------------- 菜单 ----------------

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "头衔",
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
                showEditDialog(view, group, room, wxid)
            },
        )
    )

    // ---------------- 渲染（前缀 Span，无 addView，不卡顿） ----------------

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (e: Exception) {
            return
        }
        if (msgInfo.isSelfSender) return

        val group = msgInfo.isInGroupChat
        val room = msgInfo.talker
        val wxid = if (group) msgInfo.sender else msgInfo.talker
        if (wxid.isEmpty()) return

        val entry = entryFor(group, room, wxid) ?: return
        if (entry.title.isBlank()) return

        val tag = view.tag
        val textView = tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView ?: return

        val name = textView.text
        val style = PRESETS[normalizeStyle(entry.style)]
        val badge = TitleBadgeSpan(
            bgStart = entry.customBgStart ?: style.bgStart,
            bgEnd = entry.customBgEnd ?: style.bgEnd,
            textColor = entry.customFg ?: style.fg,
        )

        val sb = SpannableStringBuilder()
        sb.append(entry.title)
        sb.append(" ")
        sb.append(name)
        sb.setSpan(badge, 0, entry.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        textView.text = sb
    }

    // ---------------- 设置弹窗 ----------------

    private fun showEditDialog(view: View, group: Boolean, room: String, wxid: String) {
        val name = displayName(group, room, wxid)
        val existing = entryFor(group, room, wxid)

        showComposeDialog(view.context) {
            var title by remember { mutableStateOf(existing?.title ?: "") }
            var selectedStyle by remember { mutableIntStateOf(normalizeStyle(existing?.style ?: 0)) }
            var customBgStart by remember { mutableStateOf((existing?.customBgStart ?: PRESETS[selectedStyle].bgStart).toColorHex()) }
            var customBgEnd by remember { mutableStateOf((existing?.customBgEnd ?: PRESETS[selectedStyle].bgEnd).toColorHex()) }
            var customFg by remember { mutableStateOf((existing?.customFg ?: PRESETS[selectedStyle].fg).toColorHex()) }

            AlertDialogContent(
                title = { Text((if (group) "群聊" else "私聊") + "头衔：$name") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("头衔文字") },
                            placeholder = { Text("默认：$DEFAULT_TITLE") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )

                        SegmentedColumn(
                            title = "选择样式",
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            PRESETS.forEachIndexed { index, preset ->
                                item {
                                    BaseWidget(
                                        icon = MaterialSymbols.Outlined.Label,
                                        iconPlaceholder = true,
                                        title = preset.name,
                                        description = "渐变底 · 圆角标签",
                                        selected = selectedStyle == index,
                                        onClick = { selectedStyle = index },
                                        trailingDivider = index < PRESETS.lastIndex,
                                    )
                                }
                            }
                        }

                        SegmentedColumn(
                            title = "自定义颜色（覆盖预设）",
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                ColorPickerWidget(
                                    title = "背景起点色",
                                    value = customBgStart,
                                    onValueChange = { customBgStart = it },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = "背景终点色",
                                    value = customBgEnd,
                                    onValueChange = { customBgEnd = it },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = "文字色",
                                    value = customFg,
                                    onValueChange = { customFg = it },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        val trimmed = title.trim()
                        val entry = TitleEntry(
                            title = trimmed,
                            style = selectedStyle,
                            customBgStart = customBgStart.toColorIntOrNull(),
                            customBgEnd = customBgEnd.toColorIntOrNull(),
                            customFg = customFg.toColorIntOrNull(),
                        )
                        if (trimmed.isEmpty()) titles.remove(keyOf(group, room, wxid))
                        else titles[keyOf(group, room, wxid)] = entry
                        saveTitles()
                        onDismiss()
                        showToast(if (trimmed.isEmpty()) "已恢复默认头衔" else "头衔已保存")
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        }
    }

    private fun Int.toColorHex(): String = String.format("#%08X", this)
    private fun String.toColorIntOrNull(): Int? = runCatching { toColorInt() }.getOrNull()
}

/** 带渐变背景的圆角标签，作为昵称前缀绘制进 TextView 文本流，避免 addView 重布局。 */
private class TitleBadgeSpan(
    private val bgStart: Int,
    private val bgEnd: Int,
    private val textColor: Int,
    private val cornerRadius: Float = 16f,
    private val padding: Float = 12f,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        return (paint.measureText(text, start, end) + padding * 2).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = paint.measureText(text, start, end)
        val rect = RectF(x, top.toFloat(), x + width + padding * 2, bottom.toFloat())

        val shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, bgStart, bgEnd, Shader.TileMode.CLAMP)
        paint.shader = shader
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.shader = null

        paint.color = textColor
        canvas.drawText(text, start, end, x + padding, y.toFloat(), paint)
    }
}
