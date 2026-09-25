package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.JevProtocol
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.dominantName
import dev.ujhhgtg.wekit.features.items.system.servers.WeChatService

/**
 * 展示通道 2：把解读结论作为系统消息插回原会话。
 *
 * 合并前这是独立的「Jev 聊天决策实时分析」功能，自带一条请求链路 —— 同一句话被分析两遍、
 * 两套配置、两套作用域。现在它退化成 [SignalAnalyzer] 的一个**订阅者**：
 * 与气泡卡共用同一次请求、同一份结果、同一个作用域与开关，不再重复打模型。
 *
 * 三道闸门，避免刷屏与误导：
 *  1. [ModulePrefs.displayMessage]（默认关）—— 用户明确要插才插；
 *  2. 作用域 —— 不在选定聊天里的不插；
 *  3. 新鲜度 —— 只插「刚发生的」消息；打开历史会话时本屏十几条老消息
 *     会被一起分析，若不加这一道就会瞬间插入十几条系统消息。
 * 外加 [MoodStore.markInserted] 保证同一条消息只插一次（失败重试、结果回填都不重复）。
 */
object MoodMessageChannel {

    private const val TAG = "MoodMessageChannel"

    /** 只回插最近这段时间内发生的消息。 */
    private const val FRESH_WINDOW_MS = 5 * 60_000L

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        SignalAnalyzer.onAnalyzed = { input, mood -> onAnalyzed(input, mood) }
        MoodLog.i(
            "$TAG 已挂载（回插=${ModulePrefs.displayMessage}，范围=${ModulePrefs.scopeSummary()}）",
        )
    }

    fun uninstall() {
        installed = false
        SignalAnalyzer.onAnalyzed = null
    }

    /**
     * 会话里能看到的那段文本；与气泡卡同一份结论。
     *
     * 第 14 轮改成**照着结构化字段拼**（情绪横条 / 建议），不再直接把模型那段多行正文
     * 原样贴进来 —— 原来会出现「【潜语解读 · 情绪概率】」「【潜语解读 · 下一步动作】」
     * 这种把「段落名当成情绪」的标题，以及同一份情绪百分比在两行里重复。
     * 现在的固定形状：
     *
     * ```
     * 【潜语 · 平静】
     * 情绪：平静 59% · 不确定 32% · 开心 9%
     * 建议：接住对方那句话，回应自己的感受。
     * ```
     */
    fun format(mood: Mood): String {
        val bars = mood.bars.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { "${it.name} ${it.percent}%" }
            ?.let { "情绪：$it" }
        // 卡片正文里的「事件 / 判读」等补充信息（去掉与上面重复的情绪行与建议行）
        val extra = mood.detail.lines()
            .filterNot {
                it.startsWith(JevProtocol.header) || it.startsWith("情绪：") || it.startsWith("建议：")
            }
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
        val advice = mood.advice?.takeIf { it.isNotBlank() }?.let { "建议：$it" }
        return listOfNotNull("【潜语 · ${mood.dominantName()}】", bars, extra, advice).joinToString("\n")
    }

    private fun onAnalyzed(input: AnalysisInput, mood: Mood) {
        if (!installed || !ModulePrefs.displayMessage) return
        if (!ModulePrefs.inScope(input.talker)) return

        val now = System.currentTimeMillis()
        val created = createdAtMs(input)
        if (created > 0 && now - created > FRESH_WINDOW_MS) {
            ModulePrefs.report("回插跳过：历史消息（${(now - created) / 1000}s 前）")
            return
        }
        if (!MoodStore.markInserted(input.key)) return

        when (val result = WeChatService.insertSystemMessage(input.talker, format(mood), now)) {
            is WeChatService.Result.Success -> ModulePrefs.report("已回插解读：${mood.label}")
            is WeChatService.Result.Error -> MoodLog.e("回插解读失败：${result.message}")
        }
    }

    /** 微信的 createTime 是秒；这里是秒/毫秒都认（读不到时为 0）。 */
    private fun createdAtMs(input: AnalysisInput): Long =
        if (input.createdAt in 1..9_999_999_999L) input.createdAt * 1000 else input.createdAt
}
