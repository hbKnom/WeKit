package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.system.servers.WeChatService
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jev 聊天实时决策分析
 *
 * 在「已开启」的那个会话里，对方每发来一条文字消息，就并发调用一次 Jev 固定骨架分析，
 * 并把结论以多行系统消息（type=10000）插回原会话。每条消息独立分析、互不等待。
 *
 * 入口在本模块的设置页里点开（ClickableFeature.onClick），打开的对话框同时承担两件事：
 * 填写配置（API Key / 自动分析 / 上下文轮数 / 排版宽度）+ 用「开启本聊天」把当前会话设为分析目标。
 * 因此不需要 /jev 这类命令入口。
 *
 * 作用范围：同一时间只开启一个会话（与原脚本一致）。
 */
object JevChatAssistant : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "Jev聊天实时决策分析"

    override val nameRes = R.string.feature_jev_chat_assistant_name

    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    override val descriptionRes = R.string.feature_jev_chat_assistant_description

    private const val TAG = "JevChatAssistant"

    internal const val KEY_API_KEY = "jev_api_key"
    internal const val KEY_AUTO_ANALYZE = "jev_auto_analyze"
    internal const val KEY_ACTIVE_TALKER = "jev_active_talker"
    internal const val KEY_CONTEXT_ROUNDS = "jev_context_rounds"
    internal const val KEY_PAD_CHARS = "jev_pad_chars"

    /** 默认开启自动分析（与原脚本一致） */
    internal const val DEFAULT_AUTO_ANALYZE = true

    /** 默认上下文轮数（与原脚本一致：0 = 默认不带上下文） */
    internal const val DEFAULT_CONTEXT_ROUNDS = 0

    /** 上下文轮数上限（与原脚本一致） */
    internal const val MAX_CONTEXT_ROUNDS = 30

    /**
     * 并发分析上限：原脚本用无上限 cachedThreadPool，上游挂起时线程会无限堆积（每条消息一个线程）。
     * 这里超过上限就丢弃该条（只记日志），保证不刷屏、不把宿主拖死。
     */
    private const val MAX_CONCURRENT_ANALYSES = 8

    // 线程池不进 onDisable 关闭：object 是单例，功能再次开启后还要继续用。
    // 池内线程为 daemon，空闲 60 秒自动回收；在跑的旧任务靠 [isActive] 判断是否需要落库。
    private val analyzePool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "JevChatAssistant").apply { isDaemon = true }
    }

    private val inFlight = AtomicInteger(0)

    // ------------------------------------------------------------------ 生命周期

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        inFlight.set(0)
    }

    override fun onClick(context: ComponentActivity) {
        JevChatAssistantSettings.show(context)
    }

    // ------------------------------------------------------------------ 数据库监听

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return

        // 只分析「对方发来的」消息（原脚本：if (getBoolean(bean, "isSend")) return）
        val isSend = values.getAsInteger("isSend") ?: return
        if (isSend != 0) return

        if (!autoAnalyze()) return

        val talker = values.getAsString("talker") ?: return
        if (talker.isEmpty() || talker != activeTalker()) return

        val raw = values.getAsString("content") ?: return
        // 群聊里对方消息的 content 形如 "wxid_xxx:\n正文"，原样丢给 Jev 会带上发送者前缀
        val content = normalizedContent(talker, raw)
        if (content.isEmpty()) return

        if (apiKey().isEmpty()) {
            // 这里不能弹 Toast：本回调跑在微信的数据库线程上，没有 Looper。
            WeLogger.w(TAG, "未配置 API Key，跳过分析（请在 Jev 设置里填写）")
            return
        }

        submit(talker, content)
    }

    private fun submit(talker: String, content: String) {
        // 原子占位：先自增再判定，避免两条消息同时通过 check-then-act 把并发上限打穿。
        if (inFlight.incrementAndGet() > MAX_CONCURRENT_ANALYSES) {
            inFlight.decrementAndGet()
            WeLogger.w(TAG, "并发分析已达上限($MAX_CONCURRENT_ANALYSES)，本条消息跳过")
            return
        }
        val submitted = runCatching {
            analyzePool.execute {
                try {
                    runAnalysis(talker, content)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            true
        }.getOrElse {
            WeLogger.e(TAG, "提交分析任务失败", it)
            false
        }

        if (!submitted) inFlight.decrementAndGet()
        else WeLogger.i(TAG, "已提交分析：len=${content.length} inFlight=${inFlight.get()}")
    }

    private fun runAnalysis(talker: String, content: String) {
        try {
            val history = buildHistory(talker, content)
            val text = JevApiClient.analyze(apiKey(), content, history, padChars()) ?: return
            if (text.isBlank()) return

            // 分析期间功能可能被关掉：结果不该再落库，否则「关了开关还在动」
            if (!isActive) {
                WeLogger.i(TAG, "功能已关闭，丢弃本次分析结果")
                return
            }

            // 注意：WeChatService.Result 是本项目自己的 sealed class（Success/Error），不是 kotlin.Result，
            // 没有 onFailure/onSuccess 这类扩展 —— 必须 when 穷举处理。
            when (val r = WeChatService.insertSystemMessage(talker, text, System.currentTimeMillis())) {
                is WeChatService.Result.Success -> WeLogger.i(TAG, "分析结果已作为系统消息插入")
                is WeChatService.Result.Error -> WeLogger.e(TAG, "插入系统消息失败：${r.message}")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "分析异常", e)
        }
    }

    // ------------------------------------------------------------------ 历史上下文

    /**
     * 取最近 [contextRounds] 轮「已入库」的文本消息，时间升序，最旧在前。
     * 会跳过最新那一条（= 当前正在分析的这条），与原脚本一致。
     */
    internal fun buildHistory(talker: String, current: String = ""): List<String> {
        val limit = contextRounds()
        if (limit <= 0) return emptyList()

        return runCatching {
            // 多取一条：最新那条就是「当前这条」，要排除掉
            val rows = WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = limit + 1)
            // getMessages 按 createTime DESC 返回，反转成时间升序
            val pairs = rows.asReversed().mapNotNull { msg ->
                if (msg.type?.isText != true) return@mapNotNull null
                val line = formatHistoryLine(talker, msg.content, msg.isSend != 0)
                if (line.isEmpty()) null else msg.content.trim() to line
            }
            // 按**内容**剔除当前这条，而不是按位置 dropLast(1)：插入监听器与本次查询未必在同一事务，
            // 位置法既可能漏删（当前消息留在历史里）也可能误删（把真正的上一条上下文丢给模型）。
            val cur = current.trim()
            val idx = if (cur.isEmpty()) -1 else pairs.indexOfLast { it.first == cur || it.second.endsWith(cur) }
            val kept = if (idx >= 0) pairs.filterIndexed { i, _ -> i != idx } else pairs
            kept.map { it.second }.takeLast(limit)
        }.getOrElse {
            WeLogger.e(TAG, "读取历史消息异常", it)
            emptyList()
        }
    }

    /** 把一行历史排成「我：…」/「对方(xxxx)：…」 */
    private fun formatHistoryLine(talker: String, rawContent: String, isSend: Boolean): String {
        val raw = rawContent.trim()
        if (raw.isEmpty()) return ""
        if (isSend) {
            // 自己发的群消息同样可能带 "wxid_xxx:\n" 前缀，不剥掉会让历史行出现多行
            // "我：wxid_xxx:" 这种把 prompt 结构顶坏的内容。
            val own = if (talker.isGroupChatWxId) normalizedContent(talker, rawContent) else raw
            if (own.isEmpty()) return ""
            return "我：$own"
        }
        if (!talker.isGroupChatWxId) return "对方：$raw"

        val parsed = splitGroupSender(raw)
        val body = parsed.body.trim()
        if (body.isEmpty()) return ""

        val sender = parsed.sender ?: return "对方：$body"
        return "对方(${shortSender(sender)})：$body"
    }

    /** 分析用的正文：群聊剥掉 "wxid_xxx:\n" 前缀，单聊直接用原文 */
    private fun normalizedContent(talker: String, rawContent: String): String {
        val raw = rawContent.trim()
        if (raw.isEmpty()) return ""
        if (!talker.isGroupChatWxId) return raw
        return splitGroupSender(raw).body.trim()
    }

    private class GroupPrefix(val sender: String?, val body: String)

    /**
     * 拆出群聊消息的发送者前缀。解析不出（或看起来不是 wxId）就整体当作正文，
     * 避免把「头衔」XML 之类的垃圾当成发送者，也避免误删正文。
     */
    private fun splitGroupSender(raw: String): GroupPrefix {
        val sep = raw.indexOf(":\n")
        if (sep <= 0) return GroupPrefix(null, raw)

        val sender = raw.substring(0, sep).trim()
        val body = raw.substring(sep + 2)
        if (body.isBlank() || !isPlausibleWxId(sender)) return GroupPrefix(null, raw)
        return GroupPrefix(sender, body)
    }

    /** 兜底校验：只看得出明显不是 wxId 的情况（含空白/尖括号/冒号，或过长） */
    private fun isPlausibleWxId(candidate: String): Boolean {
        if (candidate.isEmpty() || candidate.length > 64) return false
        return candidate.none { it == '<' || it == '>' || it == ':' || it == ' ' || it == '\t' || it == '\n' || it == '\r' }
    }

    private fun shortSender(sender: String): String =
        if (sender.length > 4) sender.takeLast(4) else sender

    // ------------------------------------------------------------------ 配置读取

    internal fun apiKey(): String =
        WePrefs.getStringOrDef(KEY_API_KEY, "").trim()

    internal fun autoAnalyze(): Boolean =
        WePrefs.getBoolOrDef(KEY_AUTO_ANALYZE, DEFAULT_AUTO_ANALYZE)

    internal fun activeTalker(): String =
        WePrefs.getStringOrDef(KEY_ACTIVE_TALKER, "").trim()

    internal fun contextRounds(): Int =
        WePrefs.getIntOrDef(KEY_CONTEXT_ROUNDS, DEFAULT_CONTEXT_ROUNDS)
            .coerceIn(0, MAX_CONTEXT_ROUNDS)

    /** 0 = 按屏幕自动估算（见 [JevLayout.resolvePadWidth]） */
    internal fun padChars(): Int =
        WePrefs.getIntOrDef(KEY_PAD_CHARS, 0).coerceIn(0, JevLayout.MAX_PAD_CHARS)

    /** 当前聊天，没打开聊天时返回 null（WeCurrentConversationApi 未启用时是空串） */
    internal fun currentTalkerOrNull(): String? =
        runCatching { WeCurrentConversationApi.value }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
