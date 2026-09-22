package dev.ujhhgtg.wekit.features.items.chat

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.utils.AndroidAudioDecoder
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 音频剪辑（由 FunBox 移植）
 *
 * FunBox 的 `audio_clipper_*` 功能等价物：拖动同一进度条上的两个圆点设置头尾位置，
 * 试听选中片段，重置范围，然后导出。
 *
 * 实现思路（复用 WeKit 既有音频栈，不新增 native）：
 *   1. [AndroidAudioDecoder.decodeToPcm16] 把来源解码成 16bit PCM（同时得到采样率/声道）
 *   2. 按头/尾位置对 PCM **按帧对齐** 切片
 *   3. 导出：
 *      - MP3  → 原始 PCM 写盘后 [AudioUtils.pcmToMp3]
 *      - 语音 → 补 WAV 头后 [AudioUtils.anyToSilk] → 可直接 [WeMessageApi.sendVoice]
 *
 * 约束：选中的片段不能超过 [MAX_CLIP_MS]（与 FunBox 的「选中的片段不能超过 %1$s」一致）。
 */
object AudioClipper {

    private const val TAG = "AudioClipper"

    /** Upper bound for a single export, mirroring WeChat's own voice length ceiling. */
    const val MAX_CLIP_MS = 60_000L

    /** Bytes per sample for the 16-bit PCM we decode into. */
    private const val BYTES_PER_SAMPLE = 2

    data class SourceInfo(
        val path: String,
        val totalMs: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val sizeBytes: Long,
    ) {
        val frameBytes: Int get() = BYTES_PER_SAMPLE * channelCount
        val totalFrames: Long get() = (sizeBytes / frameBytes).coerceAtLeast(0L)

        /** Slider-friendly duration; never 0 so a [RangeSlider] valueRange stays valid. */
        val totalSeconds: Float get() = (totalMs / 1000.0).toFloat().coerceAtLeast(1f)

        fun msToPcmOffset(ms: Long): Long {
            val frames = (ms.coerceAtLeast(0L) * sampleRate) / 1000L
            return (frames.coerceAtMost(totalFrames)) * frameBytes
        }

        fun offsetToMs(offset: Long): Long {
            if (totalFrames <= 0L) return 0L
            val frames = offset / frameBytes
            return (frames * 1000L) / sampleRate
        }

        fun clipMs(startMs: Long, endMs: Long): Long = (endMs - startMs).coerceAtLeast(0L)
    }

    sealed interface ClipResult {
        data class Ok(val file: File) : ClipResult
        data object TooLong : ClipResult
        data class Failed(val error: Throwable) : ClipResult
    }

    // ------------------------------------------------------------------ probe

    /**
     * Decodes [source] to a PCM scratch file so the UI can offer precise frame-aligned trimming.
     * The caller owns the returned scratch file and must delete it via [deleteScratch].
     */
    fun prepare(sourcePath: String): Result<Pair<SourceInfo, File>> = runCatching {
        val source = File(sourcePath)
        require(source.isFile) { "source is not a file: $sourcePath" }

        val scratch = File(scratchDir(), "clip_${UUID.randomUUID()}.pcm")
        val decoded = AndroidAudioDecoder.decodeToPcm16(sourcePath, scratch)
        require(scratch.isFile && scratch.length() > 0L) { "decoded pcm is empty" }

        val info = SourceInfo(
            path = sourcePath,
            totalMs = readDurationMs(sourcePath),
            sampleRate = decoded.sampleRate.coerceAtLeast(1),
            channelCount = decoded.channelCount.coerceAtLeast(1),
            sizeBytes = scratch.length(),
        )
        info to scratch
    }

    /**
     * Voice-message variant of [prepare].
     *
     * WeChat stores voice messages as SILK, which Android's own decoder cannot read, so the
     * WeKit native helper converts SILK → PCM16 first. The resulting PCM is raw 16-bit mono at
     * WeChat's recording rate, which is exactly what the trimmer needs.
     */
    fun prepareFromVoice(encPath: String): Result<Pair<SourceInfo, File>> = runCatching {
        val silkPath = dev.ujhhgtg.wekit.features.api.core.WeMessageApi.getVoiceFullPath(encPath)
        val silk = File(silkPath)
        require(silk.isFile && silk.length() > 0L) { "voice silk not found: $silkPath" }

        val scratch = File(scratchDir(), "clip_${UUID.randomUUID()}.pcm")
        require(AudioUtils.silkToPcm(silk.absolutePath, scratch.absolutePath)) { "silk→pcm failed" }
        require(scratch.isFile && scratch.length() > 0L) { "decoded pcm is empty" }

        // WeChat voice notes are 16-bit mono at 24kHz in every shipped client build; fall back to
        // that when nothing else can be probed, since the native helper does not report a rate.
        val sampleRate = probeSampleRate(silkPath) ?: 24_000
        SourceInfo(
            path = silkPath,
            totalMs = readDurationMs(silkPath),
            sampleRate = sampleRate,
            channelCount = 1,
            sizeBytes = scratch.length(),
        ) to scratch
    }.onFailure { WeLogger.e(TAG, "prepareFromVoice failed", it) }

    /** Best-effort sample rate; WeChat voice is 24kHz mono, so a miss just means "use the default". */
    private fun probeSampleRate(path: String): Int? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever?.release() }
        }
    }

    private fun readDurationMs(path: String): Long {
        val byPlayer = runCatching { AudioUtils.getDurationMs(path) }.getOrDefault(0L)
        if (byPlayer > 0L) return byPlayer

        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            WeLogger.w(TAG, "duration probe failed for $path: ${e.message}")
            0L
        } finally {
            runCatching { retriever?.release() }
        }
    }

    fun deleteScratch(file: File?) {
        runCatching { if (file != null && file.exists() && !file.delete()) file.deleteOnExit() }
    }

    private fun scratchDir(): File =
        File(HostInfo.application.cacheDir, "wekit_audio_clipper").apply { mkdirs() }

    // ------------------------------------------------------------------ preview

    /**
     * Plays only the [startMs]..[endMs] window of [sourcePath]. Returns the player so the caller can
     * stop it; playback auto-stops at [endMs].
     */
    fun preview(sourcePath: String, startMs: Long, endMs: Long, onFinished: () -> Unit): Result<MediaPlayer> =
        runCatching {
            val player = MediaPlayer()
            player.setDataSource(sourcePath)
            player.prepare()
            player.seekTo(startMs.coerceAtLeast(0L).toInt())
            player.start()

            val window = (endMs - startMs).coerceAtLeast(0L)
            if (window > 0L) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching {
                        if (player.isPlaying) player.stop()
                        player.release()
                    }
                    onFinished()
                }, window)
            }
            player
        }.onFailure { WeLogger.e(TAG, "preview failed", it) }

    // ------------------------------------------------------------------ export

    private fun validate(info: SourceInfo, startMs: Long, endMs: Long): ClipResult? {
        val length = info.clipMs(startMs, endMs)
        if (length <= 0L) return ClipResult.Failed(IllegalArgumentException("empty range"))
        if (length > MAX_CLIP_MS) return ClipResult.TooLong
        return null
    }

    /** Trims the decoded PCM into a raw PCM file inside [destinationDir]. */
    private fun writeTrimmedPcm(
        scratchPcm: File,
        info: SourceInfo,
        startMs: Long,
        endMs: Long,
        destinationDir: File,
        stem: String,
    ): File {
        val frameBytes = info.frameBytes
        val startOffset = alignDown(info.msToPcmOffset(startMs), frameBytes)
        val endOffset = alignDown(info.msToPcmOffset(endMs), frameBytes)
        val length = max(0L, endOffset - startOffset)
        require(length > 0L) { "trimmed length is zero" }

        val target = File(destinationDir, "$stem.pcm")
        RandomAccessFile(scratchPcm, "r").use { input ->
            input.seek(startOffset)
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
        return target
    }

    private fun alignDown(value: Long, alignment: Int): Long =
        if (alignment <= 1) value else value - (value % alignment)

    /**
     * Exports the selected range as MP3 into Download/WeKit/.
     */
    fun exportMp3(
        scratchPcm: File,
        info: SourceInfo,
        startMs: Long,
        endMs: Long,
    ): ClipResult {
        validate(info, startMs, endMs)?.let { return it }
        return runCatching {
            val dir = KnownPaths.downloads.toFile().apply { mkdirs() }
            val stem = "clip_${System.currentTimeMillis()}"
            val pcm = writeTrimmedPcm(scratchPcm, info, startMs, endMs, dir, stem)
            val mp3 = File(dir, "$stem.mp3")
            val ok = AudioUtils.pcmToMp3(pcm.absolutePath, mp3.absolutePath)
            pcm.delete()
            require(ok && mp3.isFile && mp3.length() > 0L) { "pcm→mp3 failed" }
            WeLogger.i(TAG, "exported mp3: $mp3")
            ClipResult.Ok(mp3) as ClipResult
        }.getOrElse { ClipResult.Failed(it) }
    }

    /**
     * Exports the selected range as a SILK file suitable for [dev.ujhhgtg.wekit.features.api.core.WeMessageApi.sendVoice].
     * Wraps the trimmed PCM in a WAV container so [AudioUtils.anyToSilk] can decode it uniformly.
     */
    fun exportSilk(
        scratchPcm: File,
        info: SourceInfo,
        startMs: Long,
        endMs: Long,
    ): ClipResult {
        validate(info, startMs, endMs)?.let { return it }
        return runCatching {
            val dir = scratchDir()
            val stem = "clip_${System.currentTimeMillis()}"
            val pcm = writeTrimmedPcm(scratchPcm, info, startMs, endMs, dir, stem)
            val wav = File(dir, "$stem.wav")
            writeWavHeader(pcm, wav, info.sampleRate, info.channelCount)
            pcm.delete()

            val silk = File(dir, "$stem.silk")
            val ok = AudioUtils.anyToSilk(wav.absolutePath, silk.absolutePath)
            wav.delete()
            require(ok && silk.isFile && silk.length() > 0L) { "wav→silk failed" }
            WeLogger.i(TAG, "exported silk: $silk")
            ClipResult.Ok(silk) as ClipResult
        }.getOrElse { ClipResult.Failed(it) }
    }

    /** Writes a canonical 44-byte PCM WAV header in front of [pcm], producing [target]. */
    internal fun writeWavHeader(pcm: File, target: File, sampleRate: Int, channelCount: Int) {
        val dataLength = pcm.length().toInt()
        val bitsPerSample = 16
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8

        target.outputStream().use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataLength)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)                       // PCM chunk size
            header.putShort(1)                      // audio format = PCM
            header.putShort(channelCount.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataLength)
            out.write(header.array())

            pcm.inputStream().use { it.copyTo(out) }
            out.flush()
        }
    }

    // ------------------------------------------------------------------ formatting helpers

    /** mm:ss for the UI counters, matching FunBox's `audio_clipper_default_time` = 00:00. */
    fun formatMs(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000.0).roundToLong()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
