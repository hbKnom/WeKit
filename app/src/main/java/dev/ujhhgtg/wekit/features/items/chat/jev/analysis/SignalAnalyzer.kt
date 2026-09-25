package dev.ujhhgtg.wekit.features.items.chat.jev.analysis

import dev.ujhhgtg.wekit.features.items.chat.jev.core.*
import kotlinx.coroutines.*

/**
 * 潜语分析调度：认领去重 + 限速 + 超时 + 可见失败 + 流水记账。
 *
 * 这里刻意不引入队列积压：同一句话只认领一次（[MoodStore.claim]），失败后进冷却窗口，
 * 超时/无响应由 [clearStuck] 兜底结清 —— 保证界面上每一条提交过的分析最终都会变成
 * 「有结果」或「可见失败」，不会永远停在「正在分析…」。
 *
 * 第 14 轮补的两件事，直接对着用户实测的「有的行出结果、有的行一直不显示」：
 *  1. **最新优先**：待分析队列按消息时间倒序（[queue]），用户正在看的那几句先出结论；
 *  2. **3 个 worker 并行**（[WORKERS]）：原来只有 2 个槽位，一屏十几条要排队几十秒。
 */
object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = JevHttpClient()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failureMessages = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 待分析队列：**最新的一句话先分析**。
     *
     * 用户实测的「有的行出结果很快、有的行一直不显示」根因就在这里 —— 进入会话时本屏
     * 十几条消息一起提交，原来按「屏幕从上到下」（= 从旧到新）的顺序发请求，
     * 用户正在看的最新几条排在最后，要等前面十几条跑完（每条两轮请求）才轮到。
     * 现在按消息时间倒序排队，并且放 3 个 worker 并行，眼前的消息先出结论。
     */
    private val queue = java.util.concurrent.PriorityBlockingQueue<AnalysisInput>(
        8,
        compareByDescending { createdAtKey(it) },
    )

    private val workersStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 单条消息的硬超时（兜底）。真正保证"不会一直转圈"的是扫描器一侧的看门狗。 */
    private const val TIMEOUT_MS = 45_000L

    /** 失败后的冷却：这段时间内同一条消息不重复打模型，但失败原因必须一直可见（可手动重试）。 */
    private const val RETRY_COOLDOWN_MS = 30_000L

    /**
     * 分析成功回调 —— 「会话消息」展示通道用。
     *
     * 同一份分析结果既画气泡卡、也（可选）插一条系统消息，**不会再打第二次模型**：
     * 合并前「言外潜台词」与「Jev 聊天决策」各有一套请求链路，同一句话被分析两遍。
     */
    @Volatile
    var onAnalyzed: ((AnalysisInput, Mood) -> Unit)? = null

    fun failure(key: String): String? = failureMessages[key]

    /** 已发出的请求数（含重试），设置页显示运行状态用。 */
    val requestCount: Int get() = client.requestCount

    /**
     * 看门狗结清：某条消息既没结果也没失败、却已经超出等待上限时调用。
     *
     * 目的只有一个 —— 让界面**永远**能给出一个可解释的状态（失败 + 可重试），
     * 而不是无限「正在分析…」。释放认领后同一句话可以立刻重试。
     */
    fun clearStuck(key: String) {
        val wasPending = MoodStore.isPending(key)
        MoodStore.release(key)
        failures.remove(key)
        val reason = if (wasPending) "分析超时（模型无响应），点击此卡重试" else "分析已中断，点击此卡重试"
        failureMessages[key] = reason
        MoodStore.markFailed()
        ModulePrefs.report("看门狗结清：${key.take(8)} pending=$wasPending")
    }

    fun retryFailure(key: String) {
        failures.remove(key)
        failureMessages.remove(key)
    }

    /** 「重试全部失败」：清掉所有冷却与失败标记，交给调用方重新提交可见行。 */
    fun retryAllFailures(): Int {
        val count = failureMessages.size
        failures.clear()
        failureMessages.clear()
        return count
    }

    fun clearResults() {
        queue.clear()
        failures.clear()
        failureMessages.clear()
        MoodStore.clearResults()
    }

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze) return null
        if (!ModulePrefs.inScope(input.talker)) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < RETRY_COOLDOWN_MS) return key
        if (!MoodStore.claim(key)) return key
        failureMessages.remove(key)
        ensureWorkers()
        queue.offer(input)
        return key
    }

    /** 3 个常驻 worker；只在第一次提交时启动，进程内可复用。 */
    private fun ensureWorkers() {
        if (!workersStarted.compareAndSet(false, true)) return
        repeat(WORKERS) {
            scope.launch {
                while (true) {
                    // 阻塞取队列（worker 线程就是 Dispatchers.IO 的线程，阻塞在这里不占主线程）。
                    val input = queue.take()
                    runCatching { perform(input) }
                        .onFailure { MoodLog.e("分析任务异常：${it.message}") }
                }
            }
        }
    }

    /** 真正跑一条分析：结果必然落到「成功」或「可见失败」，不允许静默丢弃。 */
    private suspend fun perform(input: AnalysisInput) {
        val key = input.key
        try {
            ModulePrefs.reload()
            if (!ModulePrefs.canAnalyze) {
                MoodStore.release(key)
                return
            }
            // 刻意不再用「这一行还在不在屏幕上」当作继续条件：
            // 一旦开始就必然留下结果或可见失败。以前消息一转出屏幕就静默 release，
            // 气泡会永远停在「正在分析…」（用户实测的主要症状之一）。
            val mood = withTimeoutOrNull(TIMEOUT_MS) { analyze(input) }
            if (mood == null) {
                fail(key, input, "分析超时（模型无响应），点击此卡重试", report = "分析超时：${key.take(8)}")
                return
            }
            succeed(key, input, mood)
        } catch (e: CancellationException) {
            MoodStore.release(key)
            throw e
        } catch (e: Exception) {
            fail(key, input, e.message ?: "分析失败，请稍后重试", report = "分析失败：${e.message}")
        }
    }

    private fun succeed(key: String, input: AnalysisInput, mood: Mood) {
        MoodStore.complete(key, mood)
        failures.remove(key)
        failureMessages.remove(key)
        MoodStore.markCompleted()
        MoodStore.recordScore(input.talker, mood.score)
        MoodStore.record(
            MoodStore.Entry(
                key = key,
                label = mood.label,
                talker = input.talker,
                at = System.currentTimeMillis(),
                ok = true,
                note = buildString {
                    append("情绪 ")
                    append((mood.score * 100).toInt().coerceIn(-100, 100))
                    append("%")
                    mood.advice?.let { append("；建议：").append(it.take(24)) }
                },
            ),
        )
        MoodLog.i("潜语解读完成：${mood.label}")
        ModulePrefs.report("潜语分析完成，已缓存 ${MoodStore.size()} 条")
        runCatching { onAnalyzed?.invoke(input, mood) }
            .onFailure { MoodLog.e("结论回插会话失败：${it.message}") }
    }

    private fun fail(key: String, input: AnalysisInput, reason: String, report: String) {
        failures[key] = System.currentTimeMillis()
        failureMessages[key] = reason
        MoodStore.release(key)
        MoodStore.markFailed()
        MoodStore.record(
            MoodStore.Entry(
                key = key,
                label = "分析失败",
                talker = input.talker,
                at = System.currentTimeMillis(),
                ok = false,
                note = reason,
            ),
        )
        MoodLog.e("分析失败：$reason")
        ModulePrefs.report(report)
    }

    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(),
        speaker: String = "对方"): Mood = analyze(AnalysisInput(text, "sample", context, speaker = speaker))

    /**
     * 设置页的「检测连接」：真的打一次接口并校验协议。
     *
     * 三种结果分开报，用户才能对症：接口不通（Key/网络/额度）、
     * 通但模型不按 Jev 协议回答（渠道或模型不对）、完全正常。
     */
    fun testConnection(callback: (Boolean, String) -> Unit) {
        scope.launch {
            val outcome = runCatching {
                val settings = ModulePrefs.apiSettings()
                check(settings.isConfigured) { "请先在下方填写并保存 API Key" }
                val body = client.exchange(JevProtocol.payload("在吗？", settings.model), settings)
                runCatching { JevProtocol.parseProfile(body) }.fold(
                    onSuccess = { true to "连接正常：${settings.provider.label} · ${settings.model}（已用请求 ${client.requestCount} 次）" },
                    onFailure = { false to "接口可连通，但返回内容不符合 Jev 协议，请换渠道或模型（已用请求 ${client.requestCount} 次）" },
                )
            }.getOrElse { false to (it.message ?: "检测失败，请稍后重试") }
            withContext(Dispatchers.Main) { runCatching { callback(outcome.first, outcome.second) } }
        }
    }

    private suspend fun analyze(input: AnalysisInput, shouldContinue: () -> Boolean = { true }): Mood = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        // Keep both rounds on the same endpoint and credential, even if settings change mid-request.
        val settings = ModulePrefs.apiSettings()
        check(settings.isConfigured) { "请先在潜语设置中填写并保存 API Key" }
        try {
            ChatAnalysis.analyze(input, settings.model, { client.exchange(it, settings) }) {
                job.ensureActive()
                shouldContinue()
            }
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        }
    }

    /** 时间未知（createTime 读不到）的消息按「最新」处理，避免它们被永久排在队尾。 */
    private fun createdAtKey(input: AnalysisInput): Long =
        if (input.createdAt > 0) input.createdAt else Long.MAX_VALUE

    /** 并行 worker 数：3 条同时跑，配合限速把一屏消息的等待时间压到秒级。 */
    private const val WORKERS = 3
}
