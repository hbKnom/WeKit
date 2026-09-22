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
import com.composables.icons.materialsymbols.outlined.Text_to_speech
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

/**
 * Settings sheet for [TextSpeechAnnouncer].
 *
 * Keys match Hchat's `text_speech_*` so an imported backup keeps working.
 */
object TextSpeechSettings {

    fun show(context: Context) {
        showComposeDialog(context) {
            var template by remember { mutableStateOf(TextSpeechAnnouncer.template()) }
            var announceSender by remember { mutableStateOf(TextSpeechAnnouncer.announceSender()) }
            var playVoice by remember { mutableStateOf(TextSpeechAnnouncer.playVoiceMessages()) }
            var quietEnable by remember { mutableStateOf(TextSpeechAnnouncer.quietEnabled()) }
            var quietStart by remember { mutableStateOf(TextSpeechAnnouncer.quietStart()) }
            var quietEnd by remember { mutableStateOf(TextSpeechAnnouncer.quietEnd()) }
            var respectDnd by remember { mutableStateOf(TextSpeechAnnouncer.respectWechatDnd()) }
            var volumeControl by remember { mutableStateOf(TextSpeechAnnouncer.volumeControl()) }
            var engineName by remember { mutableStateOf(TextSpeechAnnouncer.ttsEngine()) }
            var contacts by remember { mutableStateOf(TextSpeechAnnouncer.allowedContacts()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.text_speech_settings_title)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_announce_sender),
                                checked = announceSender,
                                onCheckedChange = { announceSender = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_play_voice_messages),
                                checked = playVoice,
                                onCheckedChange = { playVoice = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_respect_wechat_dnd),
                                checked = respectDnd,
                                onCheckedChange = { respectDnd = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_volume_control),
                                checked = volumeControl,
                                onCheckedChange = { volumeControl = it },
                                trailingDivider = true,
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = template,
                                onValueChange = { template = it },
                                label = { Text(stringResource(R.string.text_speech_template)) },
                                supportingText = {
                                    Text(
                                        stringResource(R.string.text_speech_template_summary) +
                                            "\n${TextSpeechAnnouncer.PH_SENDER}  " +
                                            TextSpeechAnnouncer.PH_BODY + "  " +
                                            TextSpeechAnnouncer.PH_GROUP,
                                    )
                                },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_quiet_enable),
                                checked = quietEnable,
                                onCheckedChange = { quietEnable = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = quietStart,
                                onValueChange = { quietStart = it },
                                label = { Text(stringResource(R.string.text_speech_quiet_start)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = quietEnd,
                                onValueChange = { quietEnd = it },
                                label = { Text(stringResource(R.string.text_speech_quiet_end)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = engineName,
                                onValueChange = { engineName = it },
                                label = { Text(stringResource(R.string.text_speech_tts_engine)) },
                                supportingText = {
                                    Text(stringResource(R.string.text_speech_tts_engine_summary))
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_allowed_contacts),
                                description = if (contacts.isEmpty()) {
                                    stringResource(R.string.text_speech_allowed_contacts_summary)
                                } else {
                                    contacts.joinToString("\n")
                                },
                                onClick = { },
                                trailingDivider = true,
                            )
                        }
                        if (contacts.isNotEmpty()) {
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Text_to_speech,
                                    title = stringResource(R.string.text_speech_clear_contacts),
                                    onClick = {
                                        WePrefs.putStringSet(TextSpeechAnnouncer.KEY_ALLOWED, emptySet())
                                        contacts = emptySet()
                                    },
                                    trailingDivider = true,
                                )
                            }
                            items(contacts.toList()) { wxid ->
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Text_to_speech,
                                    title = wxid,
                                    onTrailingClick = {
                                        val next = contacts - wxid
                                        WePrefs.putStringSet(TextSpeechAnnouncer.KEY_ALLOWED, next)
                                        contacts = next
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
                        WePrefs.putString(TextSpeechAnnouncer.KEY_TEMPLATE, template.trim())
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_ANNOUNCE_SENDER, announceSender)
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_PLAY_VOICE, playVoice)
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_QUIET_ENABLE, quietEnable)
                        WePrefs.putString(
                            TextSpeechAnnouncer.KEY_QUIET_START,
                            quietStart.trim().ifBlank { TextSpeechAnnouncer.DEFAULT_QUIET_START },
                        )
                        WePrefs.putString(
                            TextSpeechAnnouncer.KEY_QUIET_END,
                            quietEnd.trim().ifBlank { TextSpeechAnnouncer.DEFAULT_QUIET_END },
                        )
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_RESPECT_DND, respectDnd)
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_VOLUME_CONTROL, volumeControl)
                        WePrefs.putString(TextSpeechAnnouncer.KEY_TTS_ENGINE, engineName.trim())
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        }
    }
}
