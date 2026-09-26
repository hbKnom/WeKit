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
    /**
     * 分析身份键（SHA-256，内容 + 上下文 + 消息身份一起哈希）。
     *
     * **必须缓存**：原来写成 `get()`，每次访问都要对整段上下文重算 SHA-256、再把 32 字节
     * 逐字节格式化成十六进制字符串。合并后这个键会被「结果缓存查询 / 渲染指纹 / 提交去重 /
     * 看门狗」反复访问 —— 一屏十几行、刷新节拍每秒一拍，就是每秒成百上千次哈希 + 上千个
     * 临时字符串，全部压在主线程上。输入对象本身不可变，算一次复用即可。
     *
     * 用 [LazyThreadSafetyMode.PUBLICATION]：不加锁，极端并发下最多重复计算一次，
     * 不会让工作线程或主线程阻塞在锁上。
     */
    val key: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MoodStore.keyOf(text, talker, context, messageId, speaker)
    }

    /** 是否是我方发出的消息（回插通道要跳过自己的话，否则等于自己刷自己的屏）。 */
    val isSelf: Boolean get() = speaker == SELF_SPEAKER

    private companion object {
        const val SELF_SPEAKER = "我"
    }
}

object MessagePolicy {
    /**
     * 单条消息的字符上限的**默认值**（第 14 轮从 1000 抬到 2000）。
     *
     * 第 16 轮起真正的上限由设置项 [ModulePrefs.KEY_MAX_CHARS] 决定（[maxCharacters]）：
     * 用户要「上限可配置 + 超限有明确提示」，所以这里保留常量只作为默认值与兼容引用，
     * 判定一律走 [maxCharacters]。
     */
    const val MAX_CHARACTERS = ModulePrefs.DEFAULT_MAX_CHARS
    const val MAX_CONTEXT_MESSAGES = 10

    /** 当前生效的单条字符上限（热路径：命中 [dev.ujhhgtg.wekit.preferences.HotPrefs] 内存缓存，无 SQLite）。 */
    val maxCharacters: Int get() = ModulePrefs.maxChars

    /** 是否超过当前上限。抽出来给渲染侧用（要单独判断「是超限还是内容为空」）。 */
    fun tooLong(text: String): Boolean = text.codePointCount(0, text.length) > maxCharacters

    fun textOrNull(text: String): String? {
        if (tooLong(text)) return null
        return text.trim().takeIf { it.isNotEmpty() }
    }
}
