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
    internal const val KEY_COOKIE = "qq_music_order_cookie"

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

    /** 用户填写的 QQ 音乐 Cookie（可选，仅用于换取可播放直链）。 */
    fun cookie(): String = WePrefs.getStringOrDef(KEY_COOKIE, "").trim()

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
     *
     * 但「全句 contains」太宽松：真机日志里用户发的一段普通聊天（"…qq点歌，发送电点歌无法发出
     * 卡片和歌曲…"）被当成点歌指令，还去搜索了一个超长"歌名"。因此收紧为：
     *  - 触发词必须出现在句首，或前面只允许一个 `@某人 ` 前缀（群里 @机器人 点歌的常见写法）；
     *  - 整条消息不能含换行；
     *  - 查询串长度上限 40 字（歌名+歌手）。
     */
    fun parseCommand(text: String): SongQuery? {
        val body = text.trim()
        if (body.isEmpty() || body.contains('\n')) return null

        // The longest trigger wins, so "点歌 " style variants do not shadow longer ones.
        val hit = triggers().mapNotNull { candidate ->
            val idx = body.indexOf(candidate)
            if (idx < 0) return@mapNotNull null
            val prefix = body.substring(0, idx)
            if (prefix.isNotEmpty() && !MENTION_ONLY.matches(prefix)) return@mapNotNull null
            candidate to idx
        }.maxByOrNull { it.first.length } ?: return null

        val after = body.substring(hit.second + hit.first.length).trim()
        if (after.isEmpty() || after.length > MAX_SONG_QUERY_CHARS) return null

        if (customSinger() && after.contains('&')) {
            val idx = after.indexOf('&')
            val song = after.substring(0, idx).trim()
            val singer = after.substring(idx + 1).trim().ifEmpty { null }
            return if (song.isEmpty()) null else SongQuery(song, singer)
        }
        return SongQuery(after, null)
    }

    private companion object {
        /** 只允许一个 `@某人` 前缀出现在触发词之前。 */
        val MENTION_ONLY = Regex("^@[^\\s@]{1,24}\\s*$")

        /** 歌名 + 歌手的合理长度上限，超过就当成普通聊天。 */
        const val MAX_SONG_QUERY_CHARS = 40
    }

    // ------------------------------------------------------------------ pipeline

    private fun process(talker: String, sender: String, query: SongQuery) {
        try {
            if (!sendAsCard() && !sendAsVoice()) {
                notice(talker, R.string.qq_music_order_need_a_channel)
                return
            }

            val hit = searchMid(query.song)
            if (hit == null) {
                notice(talker, R.string.qq_music_order_not_found)
                return
            }

            // 详情接口偶发失败时不要整单放弃：搜索结果里的 mid / 媒体 mid / 歌曲 id 已经够发卡片和取流。
            val detail = fetchDetail(hit.mid) ?: SongDetail(
                mid = hit.mid,
                name = hit.name,
                singer = hit.singer,
                songId = hit.songId,
                albumPmid = null,
                mediaMid = hit.mediaMid,
            ).also { WeLogger.w(TAG, "detail unavailable, fallback to search hit ${hit.mid}") }

            val lyric = fetchLyric(hit.mid)
            val audioUrl = resolveAudioUrl(detail)
            val thumbUrl = resolveCoverUrl(detail)
            val singer = resolveSinger(talker, sender, detail)

            val wantsCard = sendAsCard()
            val wantsVoice = sendAsVoice()

            var cardOk = false
            if (wantsCard) cardOk = sendCard(talker, detail, singer, lyric, audioUrl, thumbUrl)

            var voiceOk = false
            if (wantsVoice && !audioUrl.isNullOrBlank()) voiceOk = sendVoice(talker, audioUrl)

            WeLogger.i(
                TAG,
                "order done: card=$wantsCard/$cardOk voice=$wantsVoice/$voiceOk " +
                    "audioUrl=${if (audioUrl.isNullOrBlank()) "none" else "resolved"}",
            )

            when {
                wantsCard && wantsVoice && !cardOk && !voiceOk ->
                    notice(talker, R.string.qq_music_order_both_failed)

                wantsCard && !cardOk ->
                    notice(talker, R.string.qq_music_order_card_failed)

                // 取不到可播放直链（会员曲/版权限制）不是"发送失败"，要说清楚只发了卡片。
                wantsVoice && audioUrl.isNullOrBlank() ->
                    notice(talker, R.string.qq_music_order_voice_unavailable)

                wantsVoice && !voiceOk ->
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

    private data class SongHit(
        val mid: String,
        val name: String,
        val singer: String,
        val songId: Long,
        val mediaMid: String?,
    )

    /**
     * 搜索歌曲。
     *
     * 真机/接口实测（2026-09-22）：musicu 搜索的响应是
     * `req.data.body.song.list[0]`，字段是 `title` / `mid` / `file.media_mid` / `id`；
     * 而旧代码读的是 `data.song.itemlist`（那是 smartbox 的路径），所以**主路永远命中不了**，
     * 只能靠 smartbox 兜底 —— 结果经常搜到翻唱、伴奏或干脆搜不到。
     */
    private fun searchMid(song: String): SongHit? {
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

            val item = musicu(body)?.optJSONObject("req")
                ?.optJSONObject("data")
                ?.optJSONObject("body")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?.optJSONObject(0)
            if (item != null) {
                val hit = hitOf(item)
                if (hit != null) {
                    WeLogger.i(TAG, "search hit via musicu: ${hit.name} / ${hit.mid}")
                    return hit
                }
            }
        }.onFailure { WeLogger.w(TAG, "musicu search failed", it) }

        // ② smartbox 兜底（路径确实是 data.song.itemlist）
        return runCatching {
            val raw = getString(SMARTBOX + URLEncoder.encode(song, "UTF-8"), jsonHeaders())
                ?: return@runCatching null
            val item = JSONObject(raw).optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("itemlist")
                ?.optJSONObject(0)
                ?: return@runCatching null
            hitOf(item)?.also { WeLogger.i(TAG, "search hit via smartbox: ${it.name} / ${it.mid}") }
        }.onFailure { WeLogger.w(TAG, "smartbox fallback failed", it) }.getOrNull()
    }

    private fun hitOf(item: JSONObject): SongHit? {
        val mid = item.optString("mid").takeIf { it.isNotBlank() } ?: return null
        return SongHit(
            mid = mid,
            name = item.optString("title").ifBlank { item.optString("name").ifBlank { mid } },
            singer = item.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty(),
            songId = item.optLong("id", 0L),
            mediaMid = item.optJSONObject("file")?.optString("media_mid")?.takeIf { it.isNotBlank() },
        )
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

    /**
     * 解析可播放直链。
     *
     * 真机实测结论（2026-09-22，容器内直连 QQ 音乐接口验证）：
     *  - 旧实现只读 `flowurl`，而取流接口返回的可播放字段是 **`purl`**（相对路径，需拼 `sip` 主机），
     *    所以哪怕是免费曲也永远拿不到链接 → 语音条从来没发出去过；
     *  - `MtLimitFreeSvr.Obtain` 对会员曲只回空 ppurl，且旧实现里 `CgiGetTempVkey` 的 `mediamid`
     *    写死成 "Yun"（应该是歌曲的 media_mid），链路根本走不通；
     *  - 免 vkey 直链（ws.stream.qqmusic.qq.com/C400<media>.m4a）现在一律 403，不要再依赖；
     *  - 匿名请求（不带账号凭据）对**免费曲**也返回空 purl/ppurl —— 2026-09-22 容器内实测：
     *    UrlGetVkey(M500/C400/M800)、CgiGetVkey(uin=0)、MtLimitFreeSvr 全部为空，
     *    第三方 Meting 公共实例同样拿不到直链。所以"语音条"只有两条路：填 QQ 音乐 Cookie
     *    （走 [resolveViaCookie] 换正式 vkey），或者不发语音只发卡片。
     *  - 会员曲（is_vip）即便有 Cookie 也要账号有对应权益，取不到就如实告知，不是 bug。
     *
     * 尝试顺序：Cookie 正式 vkey → UrlGetVkey（M500/C400/M800）→ CgiGetVkey → MtLimitFreeSvr+TempVkey。
     */
    private fun resolveAudioUrl(detail: SongDetail): String? {
        val media = detail.mediaMid ?: detail.mid

        // 没有 Cookie 就直接收手：匿名请求（UrlGetVkey / CgiGetVkey(uin=0) / MtLimitFreeSvr）
        // 已被实测证明对免费曲也一律返回空，继续跑等于白发 5 个网络请求、拖慢点歌响应。
        // 调用方会据此提示"只发了卡片 + 建议填 Cookie"。
        if (cookieAuth() == null) {
            WeLogger.i(TAG, "no qq music cookie configured: skip stream resolution, card only")
            return null
        }

        // 0) 带账号凭据的取流 —— 唯一能拿到正式 vkey 的方式（vkey 直链正是官方/侧边栏
        //    音乐卡片组件用的那种 URL，只是它们由客户端带登录态去换）。
        resolveViaCookie(detail)?.let {
            WeLogger.i(TAG, "audio url resolved via cookie vkey")
            return it
        }

        for (name in listOf("M500$media.mp3", "C400$media.m4a", "M800$media.mp3")) {
            val url = runCatching {
                val param = JSONObject()
                    .put("guid", "Yun")
                    .put("songmid", JSONArray().put(detail.mid))
                    .put("filename", JSONArray().put(name))
                val request = JSONObject()
                    .put("module", "music.vkey.GetVkey")
                    .put("method", "UrlGetVkey")
                    .put("param", param)
                val body = JSONObject()
                    .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                    .put("request", request)
                extractPurl(musicu(body), "midurlinfo")
            }.onFailure { WeLogger.w(TAG, "UrlGetVkey($name) failed", it) }.getOrNull()
            if (!url.isNullOrBlank()) {
                WeLogger.i(TAG, "audio url resolved via UrlGetVkey($name)")
                return url
            }
        }

        val viaCgi = runCatching {
            val param = JSONObject()
                .put("guid", "10000")
                .put("songmid", JSONArray().put(detail.mid))
                .put("songtype", JSONArray().put(0))
                .put("uin", "0")
                .put("loginflag", 1)
                .put("platform", "20")
            val request = JSONObject()
                .put("module", "vkey.GetVkeyServer")
                .put("method", "CgiGetVkey")
                .put("param", param)
            val body = JSONObject()
                .put("comm", JSONObject().put("ct", "24").put("cv", "0").put("uin", "0"))
                .put("req", request)
            extractPurl(musicu(body), "midurlinfo")
        }.onFailure { WeLogger.w(TAG, "CgiGetVkey failed", it) }.getOrNull()
        if (!viaCgi.isNullOrBlank()) {
            WeLogger.i(TAG, "audio url resolved via CgiGetVkey")
            return viaCgi
        }

        // 最后一条路：会员限免额度（部分非会员曲目会给出临时 ppurl），再用它换临时 vkey。
        if (detail.songId > 0) {
            val temp = runCatching {
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
                musicu(body)?.optJSONObject("request")
                    ?.optJSONObject("data")
                    ?.optJSONArray("tracks")
                    ?.optJSONObject(0)
                    ?.optJSONObject("control")
                    ?.optString("ppurl")
                    ?.takeIf { it.isNotBlank() }
            }.onFailure { WeLogger.w(TAG, "MtLimitFreeSvr failed", it) }.getOrNull()

            if (!temp.isNullOrBlank()) {
                val url = runCatching {
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
                                            .put("mediamid", media)
                                            .put("tempVkey", temp)
                                            .put("songMID", detail.mid),
                                    ),
                                ),
                        )
                    val body = JSONObject().put("request", request)
                    musicu(body)?.optJSONObject("request")
                        ?.optJSONObject("data")
                        ?.optJSONObject("data")
                        ?.optString("purl")
                        ?.takeIf { it.isNotBlank() }
                }.onFailure { WeLogger.w(TAG, "CgiGetTempVkey failed", it) }.getOrNull()

                if (!url.isNullOrBlank()) {
                    WeLogger.i(TAG, "audio url resolved via MtLimitFreeSvr")
                    return if (url.startsWith("http")) url else STREAM_PREFIX + url
                }
            }
        }

        WeLogger.i(TAG, "no playable audio url for mid=${detail.mid} (vip/limited or unavailable)")
        return null
    }

    /**
     * 用用户填的 QQ 音乐 Cookie 换取正式 vkey（相当于官方客户端/侧边栏音乐卡片那条路）。
     *
     * 请求形状与 QQ 音乐客户端一致：`comm` 带 `uin` + `authst`（即 `qm_keyst`），
     * `req` 走 `vkey.GetVkeyServer/CgiGetVkey`、`platform=20`、`loginflag=1`；
     * 返回 `req.data.midurlinfo[0].purl` 是相对路径，要拼上 `req.data.sip[0]`。
     */
    private fun resolveViaCookie(detail: SongDetail): String? {
        val (uin, authst) = cookieAuth() ?: return null
        return runCatching {
            val param = JSONObject()
                .put("guid", "10000")
                .put("songmid", JSONArray().put(detail.mid))
                .put("songtype", JSONArray().put(0))
                .put("uin", uin)
                .put("loginflag", 1)
                .put("platform", "20")
            val request = JSONObject()
                .put("module", "vkey.GetVkeyServer")
                .put("method", "CgiGetVkey")
                .put("param", param)
            val body = JSONObject()
                .put(
                    "comm",
                    JSONObject()
                        .put("uin", uin)
                        .put("format", "json")
                        .put("ct", 24)
                        .put("cv", 0)
                        .put("authst", authst),
                )
                .put("req", request)
            extractPurl(musicu(body), "midurlinfo")
        }.onFailure { WeLogger.w(TAG, "cookie vkey request failed", it) }.getOrNull()
    }

    /**
     * 解析用户填写的 QQ 音乐 Cookie（可选）。
     *
     * 支持整段 Cookie（登录 y.qq.com 后从开发者工具复制：`uin=123456; qm_keyst=xxx; ...`），
     * 键顺序无关，`o123456` 这类带前缀的 uin 会自动去掉前缀。拿不到 uin 或密钥时返回 null
     * —— 此时只发卡片，不再空跑后面的匿名取流（匿名对免费曲也拿不到链接）。
     */
    private fun cookieAuth(): Pair<String, String>? {
        val raw = cookie()
        if (raw.isEmpty() || !raw.contains('=')) return null

        var uin = ""
        var auth = ""
        raw.split(';', '\n').forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val key = part.substring(0, idx).trim().lowercase()
            val value = part.substring(idx + 1).trim().trim('"')
            if (value.isEmpty()) return@forEach
            when (key) {
                "uin", "wxuin", "p_uin", "o_uin", "musickey_uin" -> if (uin.isEmpty()) uin = value
                "qm_keyst", "qqmusic_key", "qqmusic_key_new", "music_key", "skey" ->
                    if (auth.isEmpty()) auth = value
            }
        }
        if (uin.isEmpty() || auth.isEmpty()) {
            WeLogger.w(TAG, "qq music cookie incomplete: uin=${uin.isNotEmpty()} key=${auth.isNotEmpty()}")
            return null
        }
        val digits = uin.removePrefix("o")
        if (digits.isEmpty() || !digits.all { it.isDigit() }) {
            WeLogger.w(TAG, "qq music cookie uin malformed")
            return null
        }
        return digits to auth
    }

    /** musicu 通用请求：统一拼接 URL、编码与异常兜底。 */
    private fun musicu(body: JSONObject): JSONObject? = runCatching {
        val raw = getString(MUSICU + URLEncoder.encode(body.toString(), "UTF-8"), jsonHeaders())
            ?: return@runCatching null
        JSONObject(raw)
    }.onFailure { WeLogger.w(TAG, "musicu request failed", it) }.getOrNull()

    /**
     * 从 musicu 响应里取 `purl`（其次 `flowurl`）并拼上 `sip` 主机前缀。
     *
     * 响应外层键名跟随请求（`request` / `req` 两种都见过），所以两个都试。
     */
    private fun extractPurl(resp: JSONObject?, listKey: String): String? {
        if (resp == null) return null
        val data = (resp.optJSONObject("request") ?: resp.optJSONObject("req"))
            ?.optJSONObject("data") ?: return null
        val item = data.optJSONArray(listKey)?.optJSONObject(0) ?: return null
        val raw = item.optString("purl").takeIf { it.isNotBlank() }
            ?: item.optString("flowurl").takeIf { it.isNotBlank() }
            ?: return null
        if (raw.startsWith("http")) return raw
        val host = data.optJSONArray("sip")?.optString(0).orEmpty().ifBlank { STREAM_PREFIX }
        return if (host.endsWith("/")) host + raw else "$host/$raw"
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

    /**
     * 卡片的缩略图地址。
     *
     * 之前这里下载封面字节再传给发送接口，但 `WeMessageApi.sendXmlAppMsg` 的 url/data 参数恒为
     * null（封面由 XML 里的 `thumburl` 交给微信自己去下），所以那次下载完全是白跑的网络请求
     * —— 去掉后卡片依旧正常，还少一次请求。`coverAsAvatar` 现在真正生效（用歌手头像做封面）。
     */
    private fun resolveCoverUrl(detail: SongDetail): String {
        val album = detail.albumPmid?.let { "$COVER_PREFIX$it.jpg" }.orEmpty()
        if (!coverAsAvatar()) return album
        return runCatching { WeDatabaseApi.getAvatarUrl(detail.mid) }
            .getOrDefault("")
            .takeIf { it.isNotBlank() }
            ?: album
    }

    // ------------------------------------------------------------------ send

    private fun sendCard(
        talker: String,
        detail: SongDetail,
        singer: String,
        lyric: String,
        audioUrl: String?,
        thumbUrl: String,
    ): Boolean = runCatching {
        val xml = buildSongXml(detail, singer, lyric, audioUrl, thumbUrl)
        val ok = WeMessageApi.sendXmlAppMsg(talker, xml)
        WeLogger.i(TAG, "send card result=$ok xmlLen=${xml.length}")
        ok
    }.onFailure { WeLogger.e(TAG, "send card failed", it) }.getOrDefault(false)

    /**
     * Standard QQ Music appmsg card. `appid` must be a registered music appId so WeChat renders
     * the music card instead of a generic link.
     *
     * 必须是完整的 `<msg>…</msg>` 报文：宿主解析入口（WeAppMsgApi.methodParseXml 命中的那个方法）
     * 是「解析一条完整消息 XML 里的 appmsg 段」，只给 `<appmsg>` 片段时它会直接返回 null 并打印
     * "parse amessage xml failed" —— 这正是线上点歌卡片一直发不出去的根因（真机日志实测）。
     * 参考可用的报文样例：MarkdownRendering.toNativeMarkdownAppMsg、ReadReceipts 的卡片 XML。
     *
     * [audioUrl] 是解析出来的可播放直链（可能为空）：非空时写进 dataurl/lowdataurl，
     * 让微信卡片自带播放能力；为空时仍发卡片（标题/歌手/封面/歌词都可用），只按链接跳转。
     */
    private fun buildSongXml(
        detail: SongDetail,
        singer: String,
        lyric: String,
        audioUrl: String? = null,
        thumbUrl: String = "",
    ): String {
        val url = SONG_PAGE + detail.mid
        val cover = thumbUrl
        val play = audioUrl.orEmpty()
        return buildString {
            append("<msg>")
            append("<appmsg appid=\"").append(escape(appId())).append("\" sdkver=\"0\">")
            append("<title>").append(escape(detail.name)).append("</title>")
            append("<des>").append(escape(singer)).append("</des>")
            append("<action>view</action>")
            append("<type>3</type>")
            append("<showtype>0</showtype>")
            append("<content></content>")
            append("<url>").append(escape(url)).append("</url>")
            append("<lowurl>").append(escape(url)).append("</lowurl>")
            append("<dataurl>").append(escape(play)).append("</dataurl>")
            append("<lowdataurl>").append(escape(play)).append("</lowdataurl>")
            append("<thumburl>").append(escape(cover)).append("</thumburl>")
            append("<songalbumurl>").append(escape(cover)).append("</songalbumurl>")
            append("<songlyric>").append(escape(lyric)).append("</songlyric>")
            append("<appattach><totallen>0</totallen><attachid></attachid><fileext></fileext></appattach>")
            append("<frommsgid>0</frommsgid>")
            append("</appmsg>")
            append("</msg>")
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

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val AUDIO_EXTS = setOf("mp3", "m4a", "mp4", "flac", "ogg", "wav")
}
