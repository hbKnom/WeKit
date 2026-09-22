package dev.ujhhgtg.wekit.features.items.chat

import android.media.MediaPlayer
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_cut
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音频剪辑（由 FunBox 移植）
 *
 * 入口：任意**语音消息**的长按菜单 → 「音频剪辑」。
 * 界面等价 FunBox 的 `audio_clipper_*`：
 *   - 拖动同一进度条上的两个圆点设置头尾位置
 *   - 试听选中片段 / 停止试听、重置范围
 *   - 导出为语音（SILK，可直接发送）或 MP3（保存到 Download/WeKit）
 *   - 选中的片段不能超过 [AudioClipper.MAX_CLIP_MS]
 *
 * 剪辑算法见 [AudioClipper]；本文件只负责入口、界面与生命周期。
 */
object AudioClipperFeature : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "音频剪辑"
    override val nameRes = R.string.feature_audio_clipper_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_audio_clipper_description

    private const val MENU_ID = 777421

    override fun onEnable() = WeChatMessageContextMenuApi.addProvider(this)

    override fun onDisable() = WeChatMessageContextMenuApi.removeProvider(this)

    /** Opened from the settings list: nothing to open without a concrete message. */
    override fun onClick(context: ComponentActivity) {
        showToast(context, context.getString(R.string.audio_clipper_need_voice_message))
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = MENU_ID,
            text = "音频剪辑",
            drawable = MenuIcons.res(R.drawable.ic_menu_voice),
            imageVector = MaterialSymbols.Outlined.Content_cut,
            isSupported = { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                val encPath = msgInfo.imagePath
                if (encPath.isNullOrBlank()) {
                    showToast(view.context, view.context.getString(R.string.audio_clipper_source_missing))
                } else {
                    showAudioClipper(view, encPath)
                }
            },
        ),
    )

    // ------------------------------------------------------------------ dialog

    private fun showAudioClipper(anchor: View, encPath: String) {
        val context = anchor.context

        showComposeDialog(context) {
            // Scope tied to the dialog composition: the previous per-dialog
            // CoroutineScope(SupervisorJob() + Dispatchers.IO) was never cancelled and leaked
            // one job container per opened dialog.
            val scope = rememberCoroutineScope()
            var info by remember { mutableStateOf<AudioClipper.SourceInfo?>(null) }
            var scratch by remember { mutableStateOf<File?>(null) }
            var failure by remember { mutableStateOf<String?>(null) }
            var preparing by remember { mutableStateOf(true) }
            var player by remember { mutableStateOf<MediaPlayer?>(null) }
            var range by remember { mutableStateOf(0f..0f) }

            LaunchedEffect(encPath) {
                val result = withContext(Dispatchers.IO) { AudioClipper.prepareFromVoice(encPath) }
                preparing = false
                result
                    .onSuccess { (sourceInfo, scratchFile) ->
                        info = sourceInfo
                        scratch = scratchFile
                        range = 0f..minOf(sourceInfo.totalMs, AudioClipper.MAX_CLIP_MS)
                            .coerceAtLeast(1L).toFloat()
                    }
                    .onFailure {
                        WeLogger.e("AudioClipperFeature", "prepare failed", it)
                        failure = it.message
                    }
            }

            fun stopPreview() {
                player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
                player = null
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.audio_clipper_title)) },
                text = {
                    val source = info
                    when {
                        preparing -> Text(stringResource(R.string.audio_clipper_preparing))

                        failure != null -> Text(
                            stringResource(R.string.audio_clipper_failed, failure.orEmpty()),
                        )

                        source == null -> Text(stringResource(R.string.audio_clipper_source_missing))

                        else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                            val clipMs = ((range.endInclusive - range.start) * 1000f).toLong()
                            val tooLong = clipMs > AudioClipper.MAX_CLIP_MS
                            val startMs = (range.start * 1000f).toLong()
                            val endMs = (range.endInclusive * 1000f).toLong()

                            item {
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    Text(
                                        stringResource(
                                            R.string.audio_clipper_range,
                                            AudioClipper.formatMs(startMs),
                                            AudioClipper.formatMs(endMs),
                                            AudioClipper.formatMs(clipMs),
                                        ),
                                    )
                                    RangeSlider(
                                        value = range,
                                        onValueChange = { range = it },
                                        valueRange = 0f..source.totalSeconds,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(stringResource(R.string.audio_clipper_range_tip))
                                }
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Content_cut,
                                    title = stringResource(R.string.audio_clipper_info_title),
                                    description = stringResource(
                                        R.string.audio_clipper_info,
                                        source.path.substringAfterLast('/'),
                                        source.sampleRate,
                                        AudioClipper.formatMs(source.totalMs),
                                        AudioClipper.formatBytes(source.sizeBytes),
                                    ),
                                    onClick = { },
                                    trailingDivider = true,
                                )
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Content_cut,
                                    title = if (player == null) {
                                        stringResource(R.string.audio_clipper_play)
                                    } else {
                                        stringResource(R.string.audio_clipper_stop)
                                    },
                                    onClick = {
                                        if (player != null) {
                                            stopPreview()
                                        } else {
                                            AudioClipper
                                                .preview(source.path, startMs, endMs) { player = null }
                                                .onSuccess { player = it }
                                                .onFailure {
                                                    showToast(
                                                        context,
                                                        context.getString(R.string.audio_clipper_play_failed),
                                                    )
                                                }
                                        }
                                    },
                                    trailingDivider = true,
                                )
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Content_cut,
                                    title = stringResource(R.string.audio_clipper_reset),
                                    onClick = {
                                        range = 0f..minOf(source.totalMs, AudioClipper.MAX_CLIP_MS)
                                            .coerceAtLeast(1L).toFloat()
                                    },
                                    trailingDivider = true,
                                )
                            }

                            if (tooLong) {
                                item {
                                    Text(
                                        stringResource(
                                            R.string.audio_clipper_max_duration_error,
                                            AudioClipper.formatMs(AudioClipper.MAX_CLIP_MS),
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Content_cut,
                                    title = stringResource(R.string.audio_clipper_export_voice),
                                    enabled = !tooLong,
                                    onClick = {
                                        val pcm = scratch ?: return@BaseWidget
                                        scope.launch {
                                            val outcome = withContext(Dispatchers.IO) {
                                                AudioClipper.exportSilk(pcm, source, startMs, endMs)
                                            }
                                            report(context, outcome)
                                        }
                                    },
                                    trailingDivider = true,
                                )
                            }

                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Content_cut,
                                    title = stringResource(R.string.audio_clipper_export_mp3),
                                    enabled = !tooLong,
                                    onClick = {
                                        val pcm = scratch ?: return@BaseWidget
                                        scope.launch {
                                            val outcome = withContext(Dispatchers.IO) {
                                                AudioClipper.exportMp3(pcm, source, startMs, endMs)
                                            }
                                            report(context, outcome)
                                        }
                                    },
                                    trailingDivider = true,
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        stopPreview()
                        AudioClipper.deleteScratch(scratch)
                        onDismiss()
                    }
                },
                confirmButton = {
                    Button(onDismiss) {
                        stopPreview()
                        AudioClipper.deleteScratch(scratch)
                        Text(stringResource(R.string.audio_clipper_confirm))
                    }
                },
            )
        }
    }

    private fun report(context: android.content.Context, outcome: AudioClipper.ClipResult) {
        when (outcome) {
            is AudioClipper.ClipResult.Ok ->
                showToast(
                    context,
                    context.getString(R.string.audio_clipper_exported, outcome.file.absolutePath),
                )

            AudioClipper.ClipResult.TooLong ->
                showToast(
                    context,
                    context.getString(
                        R.string.audio_clipper_max_duration_error,
                        AudioClipper.formatMs(AudioClipper.MAX_CLIP_MS),
                    ),
                )

            is AudioClipper.ClipResult.Failed ->
                showToast(
                    context,
                    context.getString(R.string.audio_clipper_export_failed, outcome.error.message),
                )
        }
    }
}
