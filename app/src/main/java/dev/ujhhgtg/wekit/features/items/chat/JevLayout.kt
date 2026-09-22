package dev.ujhhgtg.wekit.features.items.chat

import android.util.DisplayMetrics
import dev.ujhhgtg.wekit.utils.HostInfo

/**
 * 系统消息排版引擎（由「元启Jev聊天助手」移植）
 *
 * 微信的系统消息由宿主**强制居中**，插件改不了对齐方式。
 * 但在行尾补全角空格可以把该行撑宽，居中后起点就会左移；
 * 只要每行都补到同一宽度，各行起点就一致，看起来就是左对齐了。
 *
 * 关键细节（原脚本踩过的坑，全部保留）：
 *  - 行尾必须放一个**非空白字符**（U+00A0 不换行空格）做锚点。
 *    Android 排版会丢掉「行尾空白」，只补全角空格的话会全部失效、每行仍按自身宽度居中。
 *  - 已经够宽的行**原样返回**，再补就会折行，反而更乱。
 *  - 横线只画到「正文最宽处」，其后补空格补足到排版宽度，
 *    这样横线右端与文字右端齐平，同时整行宽度仍与上面各行一致。
 */
object JevLayout {

    /** 排版兜底宽度（全角字符数）：取不到屏幕信息时使用 */
    const val DEFAULT_PAD_CHARS = 24

    const val MIN_PAD_CHARS = 8
    const val MAX_PAD_CHARS = 40

    /** 行尾锚点：U+00A0，见类注释 */
    private const val PAD_ANCHOR = '\u00A0'

    /** 横线字符（全角制表线） */
    private const val DIVIDER_CHAR = "─"

    /** 全角空格 */
    private const val FULL_WIDTH_SPACE = "　"

    /**
     * 排版目标宽度（以「全角字符」为单位）。
     * 优先用配置项 [configured]；未配置（0 或越界）时按屏幕宽度估算。
     */
    fun resolvePadWidth(configured: Int): Int {
        if (configured in MIN_PAD_CHARS..MAX_PAD_CHARS) return configured

        return try {
            val dm: DisplayMetrics = HostInfo.application.resources.displayMetrics

            // 系统消息字号约 12sp；可用宽度约为屏幕宽的 68%
            // （取 76% 时横线会超出容器折行，说明系统消息左右留白比想象中大）
            // 必须用 scaledDensity：用户调大系统字体时字会更宽，用 density 会低估字宽、
            // 把每行补得过长，结果折行反而更乱。取两者较大值更保守。
            val charPx = 12f * maxOf(dm.density, dm.scaledDensity)
            if (charPx <= 0f) return DEFAULT_PAD_CHARS

            ((dm.widthPixels * 0.68f) / charPx).toInt().coerceIn(MIN_PAD_CHARS, MAX_PAD_CHARS)
        } catch (_: Throwable) {
            DEFAULT_PAD_CHARS
        }
    }

    /** 估算文本占几个「全角字符」宽：ASCII 算 0.5，其余算 1 */
    fun visualWidth(s: String?): Double {
        if (s == null) return 0.0
        var w = 0.0
        for (c in s) w += if (c.code < 128) 0.5 else 1.0
        return w
    }

    /**
     * 在行尾补全角空格，把居中内容顶向左边。
     * 已经够宽的行原样返回（再补就会折行，反而更乱）。
     */
    fun padLine(line: String?, target: Int): String {
        if (line == null) return ""
        val w = visualWidth(line)
        if (w >= target) return line

        val sb = StringBuilder(line)
        var i = w
        // 留出锚点宽度，避免总宽略微超出容器
        while (i < target - 1) {
            sb.append(FULL_WIDTH_SPACE)
            i += 1.0
        }
        sb.append(PAD_ANCHOR)
        return sb.toString()
    }

    /**
     * 生成分隔线：横线只画到「文字最宽处」，其后补全角空格补足到排版宽度。
     * 这样横线右端与文字右端齐平（不会长出一截），同时整行宽度仍与上面各行一致，
     * 起点才不会跑偏。
     */
    fun dividerLine(contentWidth: Double, target: Int): String {
        var n = Math.ceil(contentWidth).toInt()
        if (n < 4) n = 4
        if (n > target - 1) n = target - 1

        val sb = StringBuilder()
        repeat(n) { sb.append(DIVIDER_CHAR) }

        var w = n.toDouble()
        while (w < target - 1) {
            sb.append(FULL_WIDTH_SPACE)
            w += 1.0
        }
        sb.append(PAD_ANCHOR)
        return sb.toString()
    }

    /**
     * 把一组「正文行 + 结尾行」渲染成系统消息文本。
     * 正文与结尾之间插入一条分隔线（横线长度取两者中最宽的一行）。
     */
    fun render(body: List<String>, tail: List<String>, padChars: Int): String {
        val target = resolvePadWidth(padChars)

        var maxW = 0.0
        for (line in body) maxW = maxOf(maxW, visualWidth(line))
        for (line in tail) maxW = maxOf(maxW, visualWidth(line))

        val sb = StringBuilder()
        for (line in body) sb.append(padLine(line, target)).append('\n')
        if (tail.isNotEmpty()) {
            sb.append(dividerLine(maxW, target)).append('\n')
            for (line in tail) sb.append(padLine(line, target)).append('\n')
        }
        return sb.toString().trim()
    }

    /** 概率转整数百分比 */
    fun toPercent(v: Double): String = "${Math.round(v * 100)}%"

    /** 截断过长文本 */
    fun clip(text: String?, max: Int): String {
        if (text == null) return ""
        return if (text.length > max) text.substring(0, max) + "…" else text
    }
}
