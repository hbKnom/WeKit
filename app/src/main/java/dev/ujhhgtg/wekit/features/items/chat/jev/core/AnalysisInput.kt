package dev.ujhhgtg.wekit.features.items.chat.jev.core

data class ContextMessage(val speaker: String, val text: String)

data class AnalysisInput(
    val text: String,
    val talker: String,
    val context: List<ContextMessage> = emptyList(),
    val messageId: Long = 0,
    val speaker: String = "对方",
    /** 消息本身的创建时间（毫秒）。0 表示读不到；「回插会话」通道用它跳过历史消息。 */
    val createdAt: Long = 0,
) {
    val key: String get() = MoodStore.keyOf(text, talker, context, messageId, speaker)
}

object MessagePolicy {
    /**
     * 单条消息的字符上限。
     *
     * 第 14 轮从 1000 抬到 2000：超过上限的消息以前既不分析、也不画卡 ——
     * 用户看到的就是「这一条什么都没有」，被当成功能漏掉了。现在上限放宽，
     * 真正超限的极少，而且会明确显示「本条内容过长，未分析」。
     */
    const val MAX_CHARACTERS = 2000
    const val MAX_CONTEXT_MESSAGES = 10

    fun textOrNull(text: String): String? {
        if (text.codePointCount(0, text.length) > MAX_CHARACTERS) return null
        return text.trim().takeIf { it.isNotEmpty() }
    }
}
