package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Voice_chat
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

/**
 * Settings sheet for [QqMusicOrder].
 *
 * Every option maps 1:1 onto the Hchat keys so a migrated backup keeps working:
 * `qq_music_order_triggers`, `_send_as_card`, `_send_as_voice`, `_custom_singer`,
 * `_default_singer`, `_replace_singer_with_nickname`, `_replace_cover_with_avatar`,
 * `_app_id`, `_intercept_own_command`, `_allowed_talkers`.
 */
object QqMusicOrderSettings {

    fun show(context: Context) {
        showComposeDialog(context) {
            var triggers by remember { mutableStateOf(QqMusicOrder.triggers().joinToString(",")) }
            var sendCard by remember { mutableStateOf(QqMusicOrder.sendAsCard()) }
            var sendVoice by remember { mutableStateOf(QqMusicOrder.sendAsVoice()) }
            var customSinger by remember { mutableStateOf(QqMusicOrder.customSinger()) }
            var defaultSinger by remember { mutableStateOf(QqMusicOrder.defaultSinger()) }
            var singerAsNickname by remember { mutableStateOf(QqMusicOrder.singerAsNickname()) }
            var coverAsAvatar by remember { mutableStateOf(QqMusicOrder.coverAsAvatar()) }
            var appId by remember { mutableStateOf(QqMusicOrder.appId()) }
            var cookie by remember { mutableStateOf(QqMusicOrder.cookie()) }
            var interceptOwn by remember { mutableStateOf(QqMusicOrder.interceptOwnCommand()) }
            var talkers by remember { mutableStateOf(QqMusicOrder.refreshTalkers()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.qq_music_order_settings_title)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_triggers),
                                    description = if (triggers.isBlank()) {
                                        stringResource(R.string.qq_music_order_triggers_summary)
                                    } else {
                                        triggers
                                    },
                                    onClick = { },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = triggers,
                                    onValueChange = { triggers = it },
                                    label = { Text(stringResource(R.string.qq_music_order_triggers)) },
                                    supportingText = {
                                        Text(stringResource(R.string.qq_music_order_triggers_summary))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                            }

                            item {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_send_as_card),
                                    checked = sendCard,
                                    onCheckedChange = { sendCard = it },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Voice_chat,
                                    title = stringResource(R.string.qq_music_order_send_as_voice),
                                    checked = sendVoice,
                                    onCheckedChange = { sendVoice = it },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_intercept_own),
                                    checked = interceptOwn,
                                    onCheckedChange = { interceptOwn = it },
                                    trailingDivider = true,
                                )
                            }

                            item {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_custom_singer),
                                    description = stringResource(R.string.qq_music_order_custom_singer_summary),
                                    checked = customSinger,
                                    onCheckedChange = { customSinger = it },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_default_singer),
                                    description = defaultSinger.ifBlank {
                                        stringResource(R.string.qq_music_order_singer_as_nickname)
                                    },
                                    onClick = { },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = defaultSinger,
                                    onValueChange = { defaultSinger = it },
                                    label = { Text(stringResource(R.string.qq_music_order_default_singer)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                            }
                            item {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_singer_as_nickname),
                                    checked = singerAsNickname,
                                    onCheckedChange = { singerAsNickname = it },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_cover_as_avatar),
                                    checked = coverAsAvatar,
                                    onCheckedChange = { coverAsAvatar = it },
                                    trailingDivider = true,
                                )
                            }

                            item {
                                OutlinedTextField(
                                    value = appId,
                                    onValueChange = { appId = it },
                                    label = { Text(stringResource(R.string.qq_music_order_app_id)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_cookie),
                                    description = stringResource(R.string.qq_music_order_cookie_hint),
                                    onClick = { },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = cookie,
                                    onValueChange = { cookie = it },
                                    label = { Text(stringResource(R.string.qq_music_order_cookie)) },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Music_note,
                                    title = stringResource(R.string.qq_music_order_allowed_talkers),
                                    description = if (talkers.isEmpty()) {
                                        stringResource(R.string.qq_music_order_allowed_talkers_summary)
                                    } else {
                                        talkers.joinToString("\n")
                                    },
                                    onClick = { },
                                    trailingDivider = true,
                                )
                            }
                            if (talkers.isNotEmpty()) {
                                item {
                                    BaseWidget(
                                        icon = MaterialSymbols.Outlined.Music_note,
                                        title = stringResource(R.string.qq_music_order_clear_talkers),
                                        onClick = {
                                            WePrefs.putStringSet(
                                                QqMusicOrder.KEY_ALLOWED_TALKERS,
                                                emptySet(),
                                            )
                                            talkers = emptySet()
                                        },
                                        trailingDivider = true,
                                    )
                                }
                                items(talkers.toList()) { talker ->
                                    BaseWidget(
                                        icon = MaterialSymbols.Outlined.Music_note,
                                        title = talker,
                                        onTrailingClick = {
                                            QqMusicOrder.removeTalker(talker)
                                            talkers = QqMusicOrder.refreshTalkers()
                                        },
                                        trailingDivider = true,
                                    )
                                }
                            }
                        }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        WePrefs.putString(
                            QqMusicOrder.KEY_TRIGGERS,
                            triggers.split(',', '\uFF0C', '\n')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .ifEmpty { listOf(QqMusicOrder.DEFAULT_TRIGGER) }
                                .joinToString(","),
                        )
                        WePrefs.putBool(QqMusicOrder.KEY_SEND_AS_CARD, sendCard)
                        WePrefs.putBool(QqMusicOrder.KEY_SEND_AS_VOICE, sendVoice)
                        WePrefs.putBool(QqMusicOrder.KEY_INTERCEPT_OWN, interceptOwn)
                        WePrefs.putBool(QqMusicOrder.KEY_CUSTOM_SINGER, customSinger)
                        WePrefs.putString(QqMusicOrder.KEY_DEFAULT_SINGER, defaultSinger.trim())
                        WePrefs.putBool(QqMusicOrder.KEY_SINGER_AS_NICKNAME, singerAsNickname)
                        WePrefs.putBool(QqMusicOrder.KEY_COVER_AS_AVATAR, coverAsAvatar)
                        WePrefs.putString(
                            QqMusicOrder.KEY_APP_ID,
                            appId.trim().ifBlank { QqMusicOrder.DEFAULT_APP_ID },
                        )
                        WePrefs.putString(QqMusicOrder.KEY_COOKIE, cookie.trim())
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        }
    }
}
