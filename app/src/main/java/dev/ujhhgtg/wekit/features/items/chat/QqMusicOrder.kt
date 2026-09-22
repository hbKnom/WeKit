package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.SendIcon
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐点歌（由 Hchat 移植）
 *
 * 触发词命中后走 QQ 音乐公开接口链：
 *   ① 搜索   musicu.fcg / music.search.SearchCgiService / DoSearchForQQMusicDesktop
 *   ② 兜底   smartbox_new.fcg
 *   ③ 详情   musicu.fcg / music.pf_song_detail_svr / get_song_detail
 *   ④ 歌词   fcg_query_lyric_new.fcg
 *   ⑤ 直链   music.qqmusiclite.MtLimitFreeSvr / Obtain        → control.ppurl
 *   ⑥ vkey   music.vkey.GetVkey / CgiGetTempVkey              → data.data.purl
 *            兜底 music.vkey.GetVkey / UrlGetVkey              → midurlinfo[0].flowurl
 *   ⑦ 音频   https://sjy.stream.qqmusic.qq.com/ + flowurl
 *   ⑧ 封面   https://y.gtimg.cn/music/photo_new/T002R500x500M000<pmid>.jpg
 *
 * 发送：卡片走 [WeMessageApi.sendXmlAppMsg]，语音走 [WeMessageApi.sendVoice]（经 SILK 转换）。
 */
object QqMusicOrder : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    WeConversationContextMenuApi.IMenuItemsProvider {

    override val technicalId = "QQ音乐点歌"
    override val nameRes = R.string.feature_qq_music_order_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_qq_music_order_description

    private const val TAG = "QqMusicOrder"

    internal const val KEY_TRIGGERS = "qq_music_order_triggers"
    internal const val KEY_SEND_AS_CARD = "qq_music_order_send_as_card"
    internal const val KEY_SEND_AS_VOICE = "qq_music_order_send_as_voice"
    internal const val KEY_CUSTOM_SINGER = "qq_music_order_custom_singer"
    internal const val KEY_DEFAULT_SINGER = "qq_music_order_default_singer"
    internal const val KEY_SINGER_AS_NICKNAME = "qq_music_order_replace_singer_with_nickname"
    internal const val KEY_COVER_AS_AVATAR = "qq_music_order_replace_cover_with_avatar"
    internal const val KEY_APP_ID = "qq_music_order_app_id"
    internal const val KEY_INTERCEPT_OWN = "qq_music_order_intercept_own_command"
    internal const val KEY_ALLOWED_TALKERS = "qq_music_order_allowed_talkers"

    internal const val DEFAULT_TRIGGER = "点歌"
    const val DEFAULT_APP_ID = "wx485a97c844086dc9"

    /** Conversation long-press menu ids (kept in a range no other feature uses). */
    private const val MENU_ID_CHAT_ON = 777441
    private const val MENU_ID_CHAT_OFF = 777442

    private const val MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg?data="
    private const val SMARTBOX =
        "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&inCharset=utf8&outCharset=utf-8&key="
    private const val LYRIC =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid="
    private const val STREAM_PREFIX = "https://sjy.stream.qqmusic.qq.com/"
    private const val COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R500x500M000"
    private const val SONG_PAGE = "https://y.qq.com/n/ryqq/songDetail/"
    private const val NO_LYRIC = "[99:99.99]暂无歌词"

    private const val MAX_AUDIO_BYTES = 128L * 1024 * 1024
    private const val MAX_COVER_BYTES = 128 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val jsonClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val dlClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val voiceDir: File
        get() = File(HostInfo.application.cacheDir, "wekit_qq_music_order_voice").apply { mkdirs() }

    // ------------------------------------------------------------------ config

    fun triggers(): List<String> {
        val raw = WePrefs.getStringOrDef(KEY_TRIGGERS, DEFAULT_TRIGGER)
        val list = raw.split(',', '\uFF0C', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return list.ifEmpty { listOf(DEFAULT_TRIGGER) }
    }

    fun sendAsCard(): Boolean = WePrefs.getBoolOrDef(KEY_SEND_AS_CARD, true)

    fun sendAsVoice(): Boolean = WePrefs.getBoolOrDef(KEY_SEND_AS_VOICE, false)

    fun customSinger(): Boolean = WePrefs.getBoolOrDef(KEY_CUSTOM_SINGER, false)

    fun defaultSinger(): String = WePrefs.getStringOrDef(KEY_DEFAULT_SINGER, "")

    fun singerAsNickname(): Boolean = WePrefs.getBoolOrDef(KEY_SINGER_AS_NICKNAME, false)

    fun coverAsAvatar(): Boolean = WePrefs.getBoolOrDef(KEY_COVER_AS_AVATAR, false)

    fun appId(): String = WePrefs.getStringOrDef(KEY_APP_ID, DEFAULT_APP_ID).ifBlank { DEFAULT_APP_ID }

    fun interceptOwnCommand(): Boolean = WePrefs.getBoolOrDef(KEY_INTERCEPT_OWN, false)

    fun allowedTalkers(): Set<String> = WePrefs.getStringSetOrDef(KEY_ALLOWED_TALKERS, emptySet())

    fun setTalkerEnabled(talker: String, enabled: Boolean) {
        val current = allowedTalkers().toMutableSet()
        if (enabled) current.add(talker) else current.remove(talker)
        WePrefs.putStringSet(KEY_ALLOWED_TALKERS, current)
    }

    /** Live view of the enabled-chat set, refreshed on every dialog open. */
    internal fun refreshTalkers(): Set<String> = allowedTalkers()

    internal fun removeTalker(talker: String) {
        setTalkerEnabled(talker, false)
    }

    override fun onClick(context: ComponentActivity) {
        QqMusicOrderSettings.show(context)
    }

    // ------------------------------------------------------------------ per-chat menu

    /**
     * Per-chat switch for the allow-list.
     *
     * The allow-list starts empty, which means "every chat", and the settings sheet can only clear
     * entries — so without this entry point a chat could never be added and the option was a dead
     * end. Two mutually exclusive rows are published (add / remove) because a single row cannot
     * know the chat before it is shown.
     */
    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ID_CHAT_ON,
            text = localizedChatString(R.string.qq_music_order_this_chat_only),
            drawable = SendIcon,
            shouldShow = { context, _ ->
                context.talker.isNotEmpty() && context.talker !in allowedTalkers()
            },
        ) { context -> toggleTalker(context.activity, context.talker, true) },
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ID_CHAT_OFF,
            text = localizedChatString(R.string.qq_music_order_this_chat_off),
            drawable = SendIcon,
            shouldShow = { context, _ ->
                context.talker.isNotEmpty() && context.talker in allowedTalkers()
            },
        ) { context -> toggleTalker(context.activity, context.talker, false) },
    )

    private fun toggleTalker(context: android.content.Context, talker: String, enabled: Boolean) {
        setTalkerEnabled(talker, enabled)
        val text = if (enabled) {
            R.string.qq_music_order_this_chat_only
        } else {
            R.string.qq_music_order_this_chat_off
        }
        showToast(context, localizedChatString(text))
    }

    // ------------------------------------------------------------------ hooks

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return

        val isSend = values.getAsInteger("isSend") ?: 1
        if (isSend != 0 && !interceptOwnCommand()) return

        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return
        // Group chats carry the sender wxId here; it is what the nickname replacement needs.
        val sender = values.getAsString("sender").orEmpty()

        val allow = allowedTalkers()
        if (allow.isNotEmpty() && talker !in allow) return

        val query = parseCommand(content) ?: return

        WeLogger.i(TAG, "order detected: talker=$talker song=${query.song} singer=${query.singer}")
        scope.launch { process(talker, sender, query) }
    }

    data class SongQuery(val song: String, val singer: String?)

    /**
     * Returns the requested song when [text] contains one of the configured triggers.
     *
     * Mirrors Hchat `ge4.c`: a plain `contains` locates the trigger, everything after it becomes
     * the query, and an optional `歌名&歌手` suffix is honoured when custom singer is enabled.
     */
    fun parseCommand(text: String): SongQuery? {
        val body = text.trim()
        if (body.isEmpty()) return null

        // The longest trigger wins, so "点歌 " style variants do not shadow longer ones.
        val trigger = triggers().filter { body.contains(it) }.maxByOrNull { it.length } ?: return null
        val after = body.substringAfter(trigger).trim()
        if (after.isEmpty()) return null

        if (customSinger() && after.contains('&')) {
            val idx = after.indexOf('&')
            val song = after.substring(0, idx).trim()
            val singer = after.substring(idx + 1).trim().ifEmpty { null }
            return if (song.isEmpty()) null else SongQuery(song, singer)
        }
        return SongQuery(after, null)
    }

    // ------------------------------------------------------------------ pipeline

    private fun process(talker: String, sender: String, query: SongQuery) {
        try {
            if (!sendAsCard() && !sendAsVoice()) {
                notice(talker, R.string.qq_music_order_need_a_channel)
                return
            }

            val mid = searchMid(query.song)
            if (mid.isNullOrBlank()) {
                notice(talker, R.string.qq_music_order_not_found)
                return
            }

            val detail = fetchDetail(mid)
            if (detail == null) {
                notice(talker, R.string.qq_music_order_detail_failed)
                return
            }

            val lyric = fetchLyric(mid)
            val audioUrl = resolveAudioUrl(detail)
            val cover = fetchCover(detail)
            val singer = resolveSinger(talker, sender, detail)

            var cardOk = false
            if (sendAsCard()) cardOk = sendCard(talker, detail, singer, lyric, cover)

            var voiceOk = false
            if (sendAsVoice() && !audioUrl.isNullOrBlank()) voiceOk = sendVoice(talker, audioUrl)

            when {
                sendAsCard() && sendAsVoice() && !cardOk && !voiceOk ->
                    notice(talker, R.string.qq_music_order_both_failed)

                sendAsCard() && !cardOk ->
                    notice(talker, R.string.qq_music_order_card_failed)

                sendAsVoice() && !voiceOk ->
                    notice(talker, R.string.qq_music_order_voice_failed)
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "order failed", e)
        }
    }

    private fun notice(talker: String, @androidx.annotation.StringRes resId: Int) {
        val text = HostInfo.application.getString(resId)
        runCatching {
            // 10000 is WeChat's local tip-like message type used for in-chat notices.
            WeMessageApi.createSimpleMsgInfoAndInsert(10000, talker, text, System.currentTimeMillis())
        }.onFailure { WeLogger.e(TAG, "failed to post local notice", it) }
    }

    // ------------------------------------------------------------------ QQ Music API

    private fun searchMid(song: String): String? {
        runCatching {
            val param = JSONObject()
                .put("num_per_page", 10)
                .put("page_num", 1)
                .put("query", song)
                .put("search_type", 0)
            val req = JSONObject()
                .put("method", "DoSearchForQQMusicDesktop")
                .put("module", "music.search.SearchCgiService")
                .put("param", param)
            val body = JSONObject()
                .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                .put("req", req)
                .toString()

            val raw = getString(MUSICU + URLEncoder.encode(body, "UTF-8"), jsonHeaders())
                ?: return@runCatching
            val mid = JSONObject(raw).optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("itemlist")
                ?.optJSONObject(0)
                ?.optString("mid")
            if (!mid.isNullOrBlank()) return@runCatching
        }.onFailure { WeLogger.e(TAG, "musicu search failed", it) }

        // ② smartbox fallback
        return runCatching {
            val raw = getString(SMARTBOX + URLEncoder.encode(song, "UTF-8"), jsonHeaders())
                ?: return@runCatching null
            JSONObject(raw).optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("itemlist")
                ?.optJSONObject(0)
                ?.optString("mid")
                ?.takeIf { it.isNotBlank() }
        }.onFailure { WeLogger.e(TAG, "smartbox fallback failed", it) }.getOrNull()
    }

    data class SongDetail(
        val mid: String,
        val name: String,
        val singer: String,
        val songId: Long,
        val albumPmid: String?,
        val mediaMid: String?,
    )

    private fun fetchDetail(mid: String): SongDetail? = runCatching {
        val req = JSONObject()
            .put("module", "music.pf_song_detail_svr")
            .put("method", "get_song_detail")
            .put("param", JSONObject().put("song_mid", mid))
        val body = JSONObject()
            .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
            .put("req", req)
            .toString()

        val raw = getString(MUSICU + URLEncoder.encode(body, "UTF-8"), jsonHeaders())
            ?: return@runCatching null
        val info = JSONObject(raw).optJSONObject("req")
            ?.optJSONObject("data")
            ?.optJSONObject("track_info")
            ?: return@runCatching null

        SongDetail(
            mid = info.optString("mid").ifBlank { mid },
            name = info.optString("name").ifBlank { mid },
            singer = info.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty(),
            songId = info.optLong("id", 0L),
            albumPmid = info.optJSONObject("album")?.optString("pmid")?.takeIf { it.isNotBlank() },
            mediaMid = info.optJSONObject("file")?.optString("media_mid")?.takeIf { it.isNotBlank() },
        )
    }.onFailure { WeLogger.e(TAG, "fetchDetail failed", it) }.getOrNull()

    private fun fetchLyric(mid: String): String = runCatching {
        val raw = getString(LYRIC + mid, jsonHeaders()) ?: return@runCatching NO_LYRIC
        JSONObject(raw).optString("lyric").ifBlank { NO_LYRIC }
    }.getOrDefault(NO_LYRIC)

    private fun resolveAudioUrl(detail: SongDetail): String? {
        if (detail.songId > 0) {
            val ppurl = runCatching {
                val param = JSONObject()
                    .put("songid", JSONArray().put(detail.songId))
                    .put("need_ppurl", true)
                val request = JSONObject()
                    .put("module", "music.qqmusiclite.MtLimitFreeSvr")
                    .put("method", "Obtain")
                    .put("param", param)
                val body = JSONObject()
                    .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                    .put("request", request)
                    .toString()

                val raw = getString(MUSICU + URLEncoder.encode(body, "UTF-8"), jsonHeaders())
                    ?: return@runCatching null
                JSONObject(raw).optJSONObject("request")
                    ?.optJSONObject("data")
                    ?.optJSONArray("tracks")
                    ?.optJSONObject(0)
                    ?.optJSONObject("control")
                    ?.optString("ppurl")
                    ?.takeIf { it.isNotBlank() }
            }.onFailure { WeLogger.e(TAG, "MtLimitFreeSvr failed", it) }.getOrNull()

            if (!ppurl.isNullOrBlank()) {
                val purl = runCatching {
                    val request = JSONObject()
                        .put("module", "music.vkey.GetVkey")
                        .put("method", "CgiGetTempVkey")
                        .put(
                            "param",
                            JSONObject()
                                .put("guid", "Yun")
                                .put(
                                    "songlist",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("mediamid", "Yun")
                                            .put("tempVkey", ppurl)
                                            .put("songMID", detail.mid),
                                    ),
                                ),
                        )
                    val body = JSONObject().put("request", request).toString()

                    val raw = getString(MUSICU + URLEncoder.encode(body, "UTF-8"), jsonHeaders())
                        ?: return@runCatching null
                    JSONObject(raw).optJSONObject("request")
                        ?.optJSONObject("data")
                        ?.optJSONObject("data")
                        ?.optString("purl")
                        ?.takeIf { it.isNotBlank() }
                }.onFailure { WeLogger.e(TAG, "CgiGetTempVkey failed", it) }.getOrNull()

                if (!purl.isNullOrBlank()) return purl
            }
        }

        // UrlGetVkey fallback
        val media = detail.mediaMid ?: detail.mid
        return runCatching {
            val param = JSONObject()
                .put("guid", "Yun")
                .put("songmid", JSONArray().put(detail.mid))
                .put("filename", JSONArray().put("M500$media.mp3"))
            val request = JSONObject()
                .put("module", "music.vkey.GetVkey")
                .put("method", "UrlGetVkey")
                .put("param", param)
            val body = JSONObject()
                .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                .put("request", request)
                .toString()

            val raw = getString(MUSICU + URLEncoder.encode(body, "UTF-8"), jsonHeaders())
                ?: return@runCatching null
            val flow = JSONObject(raw).optJSONObject("request")
                ?.optJSONObject("data")
                ?.optJSONArray("midurlinfo")
                ?.optJSONObject(0)
                ?.optString("flowurl")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            if (flow.startsWith("http")) flow else STREAM_PREFIX + flow
        }.onFailure { WeLogger.e(TAG, "UrlGetVkey failed", it) }.getOrNull()
    }

    /**
     * Replaces the singer with the requester's nickname (Hchat behaviour).
     *
     * The member id has to come from the inserted row ([sender]): passing an empty id made the
     * lookup always return "" (WeDatabaseApi short-circuits on a blank member id), so the option
     * silently did nothing. For our own commands the row usually has no sender, hence the
     * self-wxId fallback.
     */
    private fun resolveSinger(talker: String, sender: String, detail: SongDetail): String {
        if (!singerAsNickname()) return detail.singer

        val fallback = defaultSinger().ifBlank { detail.singer }
        val memberId = sender.ifBlank { runCatching { WeApi.selfWxId }.getOrDefault("") }
        if (memberId.isBlank()) return fallback

        val nick = runCatching {
            if (talker.isGroupChatWxId) {
                WeDatabaseApi.getGroupMemberDisplayName(talker, memberId)
                    .ifBlank { WeDatabaseApi.getDisplayName(memberId) }
            } else {
                WeDatabaseApi.getDisplayName(memberId)
            }
        }.getOrDefault("")

        return nick.ifBlank { fallback }
    }

    private fun fetchCover(detail: SongDetail): ByteArray? {
        val url = if (coverAsAvatar()) {
            runCatching { WeDatabaseApi.getAvatarUrl(detail.mid) }
                .getOrDefault("")
                .takeIf { it.isNotBlank() }
                ?: detail.albumPmid?.let { "$COVER_PREFIX$it.jpg" }
        } else {
            detail.albumPmid?.let { "$COVER_PREFIX$it.jpg" }
        } ?: return null

        return runCatching { downloadBytes(url, MAX_COVER_BYTES) }
            .onFailure { WeLogger.e(TAG, "cover download failed", it) }
            .getOrNull()
    }

    // ------------------------------------------------------------------ send

    private fun sendCard(
        talker: String,
        detail: SongDetail,
        singer: String,
        lyric: String,
        cover: ByteArray?,
    ): Boolean = runCatching {
        WeMessageApi.sendXmlAppMsg(talker, buildSongXml(detail, singer, lyric))
    }.onFailure { WeLogger.e(TAG, "send card failed", it) }.getOrDefault(false)

    /**
     * Standard QQ Music appmsg card. `appid` must be a registered music appId so WeChat renders
     * the music card instead of a generic link.
     */
    private fun buildSongXml(detail: SongDetail, singer: String, lyric: String): String {
        val url = SONG_PAGE + detail.mid
        val cover = detail.albumPmid?.let { "$COVER_PREFIX$it.jpg" }.orEmpty()
        return buildString {
            append("<appmsg appid=\"").append(escape(appId())).append("\" sdkver=\"0\">")
            append("<title>").append(escape(detail.name)).append("</title>")
            append("<des>").append(escape(singer)).append("</des>")
            append("<type>3</type>")
            append("<url>").append(escape(url)).append("</url>")
            append("<thumburl>").append(escape(cover)).append("</thumburl>")
            append("<songalbumurl>").append(escape(cover)).append("</songalbumurl>")
            append("<songlyric>").append(escape(lyric)).append("</songlyric>")
            append("<appattach><totallen>0</totallen><attachid></attachid><fileext></fileext></appattach>")
            append("<frommsgid>0</frommsgid>")
            append("</appmsg>")
        }
    }

    private fun sendVoice(talker: String, audioUrl: String): Boolean = runCatching {
        val ext = audioUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in AUDIO_EXTS } ?: "audio"
        val stamp = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val target = File(voiceDir, "qq_music_$stamp.$ext")
        val tmp = File(target.absolutePath + ".part")
        val silk = File(voiceDir, "qq_music_$stamp.silk")

        try {
            if (!downloadTo(tmp, audioUrl)) return@runCatching false
            if (!tmp.renameTo(target) || !target.isFile || target.length() <= 0L) return@runCatching false

            // WeChat only accepts SILK for voice messages; fall back to the raw file if the
            // converter is unavailable so the user still gets something playable.
            val converted = runCatching {
                AudioUtils.anyToSilk(target.absolutePath, silk.absolutePath)
            }.getOrDefault(false)

            val playable = if (converted && silk.isFile && silk.length() > 0L) silk else target
            val durationMs = runCatching { AudioUtils.getDurationMs(playable.absolutePath) }
                .getOrDefault(0L)
                .takeIf { it > 0L }
                ?.toInt()
                ?: probeDuration(playable.absolutePath).toInt()

            WeMessageApi.sendVoice(talker, playable.absolutePath, durationMs.coerceIn(1000, 60_000))
        } finally {
            cleanup(tmp)
            cleanup(target)
            cleanup(silk)
        }
    }.onFailure { WeLogger.e(TAG, "send voice failed", it) }.getOrDefault(false)

    private fun cleanup(file: File) {
        runCatching { if (file.exists() && !file.delete()) file.deleteOnExit() }
    }

    /** Standalone duration probe, useful when the native helper cannot read the container. */
    internal fun probeDuration(path: String): Long {
        // MediaMetadataRetriever only became AutoCloseable in API 29, so close it manually.
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            WeLogger.w(TAG, "probeDuration failed for $path: ${e.message}")
            0L
        } finally {
            runCatching { retriever?.release() }
        }
    }

    // ------------------------------------------------------------------ http

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36",
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com",
        "Accept" to "application/json, text/plain, */*",
    )

    private fun getString(url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return jsonClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun downloadTo(target: File, url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MicroMessenger Client")
            .header("Referer", "https://y.qq.com/")
            .get()
            .build()

        dlClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return false

            val ctype = resp.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            if (ctype.startsWith("text/") || ctype.contains("json") || ctype.contains("xml")) return false

            val body = resp.body ?: return false
            if (body.contentLength() > MAX_AUDIO_BYTES) return false

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > MAX_AUDIO_BYTES) return false
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
        }
        return target.isFile && target.length() > 0L
    }

    private fun downloadBytes(url: String, limit: Int): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MicroMessenger Client")
            .get()
            .build()

        dlClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > limit) return null
                    out.write(buf, 0, read)
                }
                return out.toByteArray()
            }
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val AUDIO_EXTS = setOf("mp3", "m4a", "mp4", "flac", "ogg", "wav")
}
