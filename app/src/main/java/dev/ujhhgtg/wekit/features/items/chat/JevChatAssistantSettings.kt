package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.History
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.ConversationPickerSection
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

/**
 * [JevChatAssistant] 的设置对话框。
 *
 * 键名与「元启Jev聊天助手」原脚本一一对应，方便从旧插件迁移配置：
 * `jev_api_key` / `jev_active_talker` / `jev_auto_analyze` /
 * `jev_context_rounds` / `jev_pad_chars`。
 *
 * 这个对话框同时承担两件事：填配置 + 用「作用范围」把当前聊天设为分析目标，
 * 所以不需要像原脚本那样再提供一个 `/jev` 命令入口。
 */
object JevChatAssistantSettings {

    fun show(context: Context) {
        val talker = JevChatAssistant.currentTalkerOrNull()

        showComposeDialog(context) {
            var apiKey by remember { mutableStateOf(JevChatAssistant.apiKey()) }
            var autoAnalyze by remember { mutableStateOf(JevChatAssistant.autoAnalyze()) }
            var contextRounds by remember {
                mutableStateOf(JevChatAssistant.contextRounds().toString())
            }
            var padChars by remember { mutableStateOf(JevChatAssistant.padChars().toString()) }
            var activeTalker by remember { mutableStateOf(JevChatAssistant.activeTalker()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.jev_chat_assistant_settings_title)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        // ---------------- 作用范围 ----------------
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Chat,
                                title = stringResource(R.string.jev_chat_assistant_scope),
                                description = if (talker == null) {
                                    stringResource(R.string.jev_chat_assistant_scope_no_chat)
                                } else if (talker == activeTalker) {
                                    stringResource(R.string.jev_chat_assistant_scope_on, talker)
                                } else {
                                    stringResource(R.string.jev_chat_assistant_scope_off, talker)
                                },
                                onClick = {
                                    if (talker != null) {
                                        activeTalker = if (talker == activeTalker) "" else talker
                                    }
                                },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.History,
                                title = stringResource(R.string.jev_chat_assistant_scope_hint),
                                description = if (activeTalker.isBlank()) {
                                    stringResource(R.string.jev_chat_assistant_scope_none)
                                } else {
                                    activeTalker
                                },
                                onClick = { activeTalker = "" },
                                trailingDivider = true,
                            )
                        }

                        // 用户 2026-09-23 要求：作用范围参考「QQ点歌 → 生效聊天」的做法 ——
                        // 直接加载**全部会话**，点哪一条哪一条就生效，不用先打开那个聊天再设置
                        // （原来是"只能在当前聊天里开"，要换目标很麻烦）。
                        item {
                            Text(
                                text = stringResource(R.string.jev_chat_assistant_scope_pick),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        item {
                            ConversationPickerSection(
                                selected = if (activeTalker.isBlank()) {
                                    emptySet()
                                } else {
                                    setOf(activeTalker)
                                },
                                onToggle = { wxId, enabled ->
                                    // 单选语义：打开哪一条，作用范围就切到哪一条；关掉当前项就清空。
                                    activeTalker = when {
                                        enabled -> wxId
                                        wxId == activeTalker -> ""
                                        else -> activeTalker
                                    }
                                },
                            )
                        }

                        // ---------------- API Key ----------------
                        item {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text(stringResource(R.string.jev_chat_assistant_api_key)) },
                                supportingText = {
                                    Text(stringResource(R.string.jev_chat_assistant_api_key_summary))
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        // ---------------- 分析设置 ----------------
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.jev_chat_assistant_auto_analyze),
                                description = stringResource(R.string.jev_chat_assistant_auto_analyze_summary),
                                checked = autoAnalyze,
                                onCheckedChange = { autoAnalyze = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = contextRounds,
                                onValueChange = { contextRounds = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.jev_chat_assistant_context_rounds)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            R.string.jev_chat_assistant_context_rounds_summary,
                                            JevApiClient.DEFAULT_MODEL,
                                        ),
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = padChars,
                                onValueChange = { padChars = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.jev_chat_assistant_pad_chars)) },
                                supportingText = {
                                    Text(stringResource(R.string.jev_chat_assistant_pad_chars_summary))
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        WePrefs.putString(JevChatAssistant.KEY_API_KEY, apiKey.trim())
                        WePrefs.putString(JevChatAssistant.KEY_ACTIVE_TALKER, activeTalker.trim())
                        WePrefs.putBool(JevChatAssistant.KEY_AUTO_ANALYZE, autoAnalyze)

                        val rounds = contextRounds.toIntOrNull() ?: JevChatAssistant.DEFAULT_CONTEXT_ROUNDS
                        WePrefs.putInt(
                            JevChatAssistant.KEY_CONTEXT_ROUNDS,
                            rounds.coerceIn(0, JevChatAssistant.MAX_CONTEXT_ROUNDS),
                        )

                        val pad = padChars.toIntOrNull() ?: 0
                        WePrefs.putInt(
                            JevChatAssistant.KEY_PAD_CHARS,
                            pad.coerceIn(0, JevLayout.MAX_PAD_CHARS),
                        )
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        }
    }
}
