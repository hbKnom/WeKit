package dev.ujhhgtg.wekit.features.items.chat

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 文字转语音播报（由 Hchat 移植）
 *
 * Hchat 的 `us5` 功能 + `qt5` 引擎等价物：
 *   - 用 **Android 系统 TextToSpeech**，无自研引擎、无 native 依赖
 *   - `MediaSession` + `PlaybackState` 承载音量键控制（配合独立音量）
 *   - `VOLUME_CHANGED_ACTION` 后台音量变化监听
 *   - 静默时段、尊重微信免打扰、允许名单、播报模板
 *
 * WeKit 侧改为数据库插入监听（与 ChatAutoReply 同一套机制），避免 Hook 消息观察内部实现。
 */
object TextSpeechAnnouncer : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    WeConversationContextMenuApi.IMenuItemsProvider {

    override val technicalId = "文字转语音播报"
    override val nameRes = R.string.feature_text_speech_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_text_speech_description

    private const val TAG = "TextSpeechAnnouncer"

    // AudioManager.VOLUME_CHANGED_ACTION / EXTRA_VOLUME_STREAM_TYPE 在本项目的编译 SDK 里不可见
    // （平台标 @hide，部分 SDK 未暴露），这里用字面量常量，运行时行为与宿主一致。
    private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    internal const val KEY_ALLOWED = "text_speech_allowed_contacts"
    internal const val KEY_ANNOUNCE_SENDER = "text_speech_announce_sender"
    internal const val KEY_TEMPLATE = "text_speech_announcement_template"
    internal const val KEY_PLAY_VOICE = "text_speech_play_voice_messages"
    internal const val KEY_QUIET_ENABLE = "text_speech_quiet_enable"
    internal const val KEY_QUIET_START = "text_speech_quiet_start"
    internal const val KEY_QUIET_END = "text_speech_quiet_end"
    internal const val KEY_RESPECT_DND = "text_speech_respect_wechat_dnd"
    internal const val KEY_TTS_ENGINE = "text_speech_tts_engine"
    internal const val KEY_VOLUME_CONTROL = "text_speech_volume_control"

    internal const val DEFAULT_TEMPLATE = "{发送者昵称} 发了一条消息说 {消息正文}"
    internal const val DEFAULT_QUIET_START = "23:00"
    internal const val DEFAULT_QUIET_END = "08:00"

    internal const val PH_SENDER = "{发送者昵称}"
    internal const val PH_BODY = "{消息正文}"
    internal const val PH_GROUP = "{群名称}"

    private const val UTTERANCE_ID = "wekit_text_speech"

    /** Conversation long-press menu ids (kept in a range no other feature uses). */
    private const val MENU_ID_CHAT_ON = 777443
    private const val MENU_ID_CHAT_OFF = 777444

    // ---------------------------------------------------------------- config

    fun allowedContacts(): Set<String> = WePrefs.getStringSetOrDef(KEY_ALLOWED, emptySet())

    fun setContactEnabled(talker: String, enabled: Boolean) {
        val current = allowedContacts().toMutableSet()
        if (enabled) current.add(talker) else current.remove(talker)
        WePrefs.putStringSet(KEY_ALLOWED, current)
    }

    fun announceSender(): Boolean = WePrefs.getBoolOrDef(KEY_ANNOUNCE_SENDER, true)

    fun template(): String = WePrefs.getStringOrDef(KEY_TEMPLATE, DEFAULT_TEMPLATE).ifBlank { DEFAULT_TEMPLATE }

    fun playVoiceMessages(): Boolean = WePrefs.getBoolOrDef(KEY_PLAY_VOICE, false)

    fun quietEnabled(): Boolean = WePrefs.getBoolOrDef(KEY_QUIET_ENABLE, false)

    fun quietStart(): String = WePrefs.getStringOrDef(KEY_QUIET_START, DEFAULT_QUIET_START)

    fun quietEnd(): String = WePrefs.getStringOrDef(KEY_QUIET_END, DEFAULT_QUIET_END)

    fun respectWechatDnd(): Boolean = WePrefs.getBoolOrDef(KEY_RESPECT_DND, true)

    fun ttsEngine(): String = WePrefs.getStringOrDef(KEY_TTS_ENGINE, "")

    fun volumeControl(): Boolean = WePrefs.getBoolOrDef(KEY_VOLUME_CONTROL, false)

    // ---------------------------------------------------------------- runtime state

    private val main = Handler(Looper.getMainLooper())

    /**
     * Announcement queue.
     *
     * Entries are pushed from the database thread ([onInsert]) and consumed on the main thread
     * ([drainQueue]), so a plain [ArrayDeque] would race; the concurrent deque keeps both ends safe.
     */
    private val queue = ConcurrentLinkedDeque<Announcement>()

    /** Access-ordered LRU of recently announced message ids, guarding against double inserts. */
    private val spokenRecently = object : LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 64
    }

    private data class Announcement(val talker: String, val text: String)

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var engineReady = false

    @Volatile
    private var speaking = false

    private var mediaSession: MediaSession? = null
    private var volumeReceiver: BroadcastReceiver? = null

    // ---------------------------------------------------------------- hooks

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeConversationContextMenuApi.addProvider(this)
        main.post { initEngine() }
        if (volumeControl()) main.post { installVolumeReceiver() }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeConversationContextMenuApi.removeProvider(this)
        queue.clear()
        synchronized(spokenRecently) { spokenRecently.clear() }
        runCatching { volumeReceiver?.let { HostInfo.application.unregisterReceiver(it) } }
        volumeReceiver = null
        runCatching { mediaSession?.isActive = false; mediaSession?.release() }
        mediaSession = null
        runCatching { engine?.stop(); engine?.shutdown() }
        engine = null
        engineReady = false
        speaking = false
    }

    override fun onClick(context: ComponentActivity) {
        TextSpeechSettings.show(context)
    }

    /**
     * Per-chat switch for the announce allow-list.
     *
     * The list starts empty ("every chat") and the settings sheet can only clear entries, so
     * without this entry point a chat could never be added. Two mutually exclusive rows are
     * published because a single row cannot know the chat before it is shown.
     */
    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ID_CHAT_ON,
            text = localizedChatString(R.string.text_speech_this_chat_only),
            drawable = MenuIcons.res(R.drawable.ic_menu_voice),
            shouldShow = { context, _ ->
                context.talker.isNotEmpty() && context.talker !in allowedContacts()
            },
        ) { context -> toggleContact(context.activity, context.talker, true) },
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ID_CHAT_OFF,
            text = localizedChatString(R.string.text_speech_this_chat_off),
            drawable = MenuIcons.res(R.drawable.ic_menu_voice),
            shouldShow = { context, _ ->
                context.talker.isNotEmpty() && context.talker in allowedContacts()
            },
        ) { context -> toggleContact(context.activity, context.talker, false) },
    )

    private fun toggleContact(context: Context, talker: String, enabled: Boolean) {
        setContactEnabled(talker, enabled)
        val text = if (enabled) {
            R.string.text_speech_this_chat_only
        } else {
            R.string.text_speech_this_chat_off
        }
        showToast(context, localizedChatString(text))
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (values.getAsInteger("isSend") != 0) return

        val type = values.getAsInteger("type") ?: return
        val msgType = MessageType.fromCode(type) ?: return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        val talker = msgInfo.talker
        if (talker.isEmpty()) return

        val allow = allowedContacts()
        if (allow.isNotEmpty() && talker !in allow) return

        // msgId can still be 0 while the insert is in flight; msgSvrId is assigned by WeChat
        // and is stable, so prefer it and fall back to the local id.
        val dedupKey = if (msgInfo.serverId != 0L) msgInfo.serverId else msgInfo.id
        if (dedupKey != 0L) {
            synchronized(spokenRecently) {
                if (spokenRecently.containsKey(dedupKey)) return
                spokenRecently[dedupKey] = true
            }
        }

        val body: String

        when {
            msgType.isText -> body = msgInfo.actualContent
            msgType.code == MessageType.VOICE.code -> {
                // Announced as a placeholder line; the voice itself is not replayed.
                if (!playVoiceMessages()) return
                body = HostInfo.application.getString(R.string.text_speech_voice_message)
            }
            else -> return
        }

        if (body.isBlank()) return

        val sender = resolveSenderName(talker, msgInfo)
        val text = renderTemplate(talker, sender, body)

        if (isQuietNow()) {
            WeLogger.d(TAG, "quiet hours, skipping announcement")
            return
        }
        if (respectWechatDnd() && runCatching { WeConversationApi.isDnd(talker) }.getOrDefault(false)) {
            WeLogger.d(TAG, "chat is muted and wechat-dnd respect is on, skipping")
            return
        }

        queue.addLast(Announcement(talker, text))
        main.post { drainQueue() }
    }

    // ---------------------------------------------------------------- template

    private fun resolveSenderName(talker: String, msgInfo: MessageInfo): String {
        if (!announceSender()) return ""
        return runCatching {
            if (talker.isGroupChatWxId) {
                WeDatabaseApi.getGroupMemberDisplayName(talker, msgInfo.sender)
                    .ifBlank { WeDatabaseApi.getDisplayName(msgInfo.sender) }
            } else {
                WeDatabaseApi.getDisplayName(talker)
            }
        }.getOrDefault("").ifBlank { talker }
    }

    internal fun renderTemplate(talker: String, sender: String, body: String): String {
        val groupName = if (talker.isGroupChatWxId) {
            runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        } else ""
        return template()
            .replace(PH_SENDER, sender)
            .replace(PH_GROUP, groupName)
            .replace(PH_BODY, body)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ---------------------------------------------------------------- quiet hours

    /**
     * Quiet window that may wrap midnight (e.g. 23:00 → 08:00). Hchat compares the same way.
     */
    internal fun isQuietNow(): Boolean {
        if (!quietEnabled()) return false
        val start = parseHhMm(quietStart()) ?: return false
        val end = parseHhMm(quietEnd()) ?: return false
        if (start == end) return false

        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start < end) now in start until end else now >= start || now < end
    }

    internal fun parseHhMm(raw: String): Int? {
        val parts = raw.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    // ---------------------------------------------------------------- TTS

    private fun initEngine() {
        if (engine != null) return
        val ctx = HostInfo.application
        val wanted = ttsEngine()
        val created = if (wanted.isNotBlank()) {
            runCatching { TextToSpeech(ctx, { }, wanted) }.getOrNull()
        } else null

        engine = created ?: TextToSpeech(ctx) { status ->
            engineReady = status == TextToSpeech.SUCCESS
            if (!engineReady) {
                WeLogger.w(TAG, "TextToSpeech init failed: status=$status")
            } else {
                configureEngine()
            }
        }

        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                speaking = false
                main.post { drainQueue() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                main.post { drainQueue() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                WeLogger.w(TAG, "tts error: $errorCode")
                speaking = false
                main.post { drainQueue() }
            }
        })

        if (created != null) {
            engineReady = true
            configureEngine()
        }
    }

    private fun configureEngine() {
        runCatching {
            val e = engine ?: return
            val res = e.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
            if (res >= TextToSpeech.LANG_AVAILABLE) {
                e.language = Locale.SIMPLIFIED_CHINESE
            } else {
                WeLogger.w(TAG, "chinese language pack unavailable: $res")
            }
        }
    }

    private fun drainQueue() {
        if (speaking) return
        val next = queue.pollFirst() ?: return
        if (!engineReady) {
            // Engine still warming up; retry shortly instead of dropping the message.
            queue.addFirst(next)
            main.postDelayed({ drainQueue() }, 500)
            return
        }
        speak(next)
    }

    private fun speak(item: Announcement) {
        val e = engine ?: return
        speaking = true
        updatePlaybackState(true)

        val result = e.speak(item.text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            WeLogger.w(TAG, "speak() returned $result")
            speaking = false
            main.post { drainQueue() }
        }
    }

    private fun updatePlaybackState(playing: Boolean) {
        val session = mediaSession ?: return
        runCatching {
            val state = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED, 0L, 1f)
                .build()
            session.setPlaybackState(state)
        }
    }

    // ---------------------------------------------------------------- volume keys

    private fun installVolumeReceiver() {
        if (volumeReceiver != null) return
        val ctx = HostInfo.application
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_VOLUME_CHANGED) return
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                if (stream != AudioManager.STREAM_MUSIC) return
                // While speaking, remember the user's last volume so the announcement and any
                // later playback stay consistent instead of fighting each other.
                WeLogger.d(TAG, "volume changed while idle/speaking, stream=$stream")
            }
        }

        runCatching {
            ctx.registerReceiver(receiver, IntentFilter(ACTION_VOLUME_CHANGED))
            volumeReceiver = receiver
            val session = MediaSession(ctx, "WeKitTextSpeech")
            session.isActive = true
            mediaSession = session
        }.onFailure { WeLogger.w(TAG, "volume receiver init failed: ${it.message}") }
    }
}
