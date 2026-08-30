package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录分析 —— 美化版 PNG 导出
 *
 * 相比脚本原版升级：
 *  - 渐变背景 + 圆角会话卡片（头像色块 / 会话名 / wxid / 时段徽章 / 生成时间）
 *  - 统计分区以彩色卡片呈现，条形图用圆角渐变进度条替代 █ 字符
 *  - AI 报告独立卡片，支持换行排版
 *  - 导出到 /sdcard/Download/WeKit/ 公共目录
 */
object ChatAnalysisPng {

    private const val W = 1080
    private const val PAD = 60
    private const val MAX_HEIGHT = 30000

    private const val COLOR_BG_TOP = 0xFF1B2A3A.toInt()
    private const val COLOR_BG_MID = 0xFF2E4A63.toInt()
    private const val COLOR_BG_BOTTOM = 0xFFF2F6FA.toInt()
    private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    private const val COLOR_TITLE = 0xFF16283B.toInt()
    private const val COLOR_BODY = 0xFF2A3644.toInt()
    private const val COLOR_META = 0xFF7A8A9A.toInt()
    private const val COLOR_ACCENT = 0xFF2E7DD1.toInt()
    private const val COLOR_ACCENT2 = 0xFF12B3A8.toInt()
    private const val COLOR_RULE = 0xFFE3EAF2.toInt()

    private sealed class Unit {
        data class Section(val title: String) : Unit()
        data class BarLine(val label: String, val value: String, val ratio: Float) : Unit()
        data class TextLine(val text: String) : Unit()
        object Gap : Unit()
    }

    /**
     * 导出报告 PNG。
     * @param stats 本地统计报告文本
     * @param ai AI 报告文本
     * @param sessionName 会话显示名
     * @param sessionWxid 会话 wxid
     * @param period 时段标签
     * @return 保存路径
     */
    @Throws(Exception::class)
    fun export(
        stats: String,
        ai: String,
        sessionName: String,
        sessionWxid: String,
        period: String,
    ): String {
        val dir = exportDir()
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "聊天记录分析_${fmt.format(Date())}.png"
        val path = "$dir/$name"
        drawToFile(stats, ai, sessionName, sessionWxid, period, path)
        return path
    }

    fun exportDir(): String {
        val base = File("/sdcard/Download/WeKit")
        if (!base.exists()) base.mkdirs()
        if (base.exists() && base.isDirectory && base.canWrite()) return base.absolutePath
        val fallback = File("/sdcard/Download")
        if (fallback.exists() && fallback.isDirectory && fallback.canWrite()) return fallback.absolutePath
        throw RuntimeException("没有可写的本地导出目录")
    }

    // ---------------- 文本解析 ----------------

    private fun parseUnits(text: String, accent: Int): List<Unit> {
        val out = mutableListOf<Unit>()
        if (text.isBlank()) return out
        val lines = text.split("\n")
        for (line in lines) {
            val t = line.trim()
            when {
                t.isEmpty() -> { /* 空行用 Gap 控制间距 */ }
                t.startsWith("【") && t.endsWith("】") -> {
                    out.add(Unit.Gap)
                    out.add(Unit.Section(t.removeSurrounding("【", "】")))
                }
                t.contains("█") -> {
                    val barLen = t.count { it == '█' }
                    val clean = t.replace("█", "").replace(" ", "").trim()
                    // 形如「凌晨0-5点123」或「1.张三：5条123」→ 分离 label 与数字
                    val m = Regex("^(.*?)(\\d+)$").find(clean)
                    val label = m?.groupValues?.get(1) ?: clean
                    val value = m?.groupValues?.get(2) ?: ""
                    out.add(Unit.BarLine(label, value, barLen / 16f))
                }
                else -> out.add(Unit.TextLine(t))
            }
        }
        return out
    }

    // ---------------- 绘制 ----------------

    @Throws(Exception::class)
    private fun drawToFile(
        stats: String,
        ai: String,
        sessionName: String,
        sessionWxid: String,
        period: String,
        path: String,
    ) {
        val statsUnits = parseUnits(stats, COLOR_ACCENT)
        val aiUnits = parseUnits(ai, COLOR_ACCENT2)

        // 预扫描高度
        val titleP = paint(46f, COLOR_TITLE, bold = true)
        val metaP = paint(28f, COLOR_META)
        val sectionP = paint(32f, COLOR_TITLE, bold = true)
        val bodyP = paint(30f, COLOR_BODY)
        val smallP = paint(24f, COLOR_META)
        val maxW = W - PAD * 2

        var h = PAD
        // header
        h += wrapCount(sessionName, maxW, titleP) * 62 + 24
        h += wrapCount(sessionWxid, maxW, metaP) * 38 + 14
        h += wrapCount(period, maxW, metaP) * 38 + 14
        h += 30
        val generated = "分析生成于 ${reportDateText()}"
        h += wrapCount(generated, maxW, smallP) * 32 + 20
        h += 36 // header 底部距卡片

        // stats 卡片
        h += statsUnits.sumOf { unitHeight(it, maxW, bodyP, sectionP, metaP) } + 48
        if (statsUnits.isNotEmpty()) h += 10
        // ai 卡片
        if (aiUnits.isNotEmpty()) {
            h += aiUnits.sumOf { unitHeight(it, maxW, bodyP, sectionP, metaP) } + 48
        }
        h += PAD + 70
        if (h < 600) h = 600
        if (h > MAX_HEIGHT) throw Exception("报告过长，请缩小分析范围后再导出")

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bg = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_BOTTOM),
            floatArrayOf(0f, 0.22f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply { shader = bg })

        var y = drawHeader(cv, sessionName, sessionWxid, period, generated, maxW)

        // 统计卡片
        if (statsUnits.isNotEmpty()) {
            val cardTop = y - 16
            val bodyH = statsUnits.sumOf { unitHeight(it, maxW, bodyP, sectionP, metaP) } + 44
            val cardBottom = cardTop + bodyH
            drawCard(cv, cardTop, cardBottom, COLOR_ACCENT)
            y = drawUnits(cv, statsUnits, y, maxW, COLOR_ACCENT)
            y += 28
        }

        // AI 卡片
        if (aiUnits.isNotEmpty()) {
            val cardTop = y - 16
            val bodyH = aiUnits.sumOf { unitHeight(it, maxW, bodyP, sectionP, metaP) } + 44
            val cardBottom = cardTop + bodyH
            drawCard(cv, cardTop, cardBottom, COLOR_ACCENT2)
            y = drawUnits(cv, aiUnits, y, maxW, COLOR_ACCENT2)
            y += 28
        }

        // 页脚
        val footerP = paint(24f, COLOR_META)
        cv.drawText("WeKit · 聊天记录分析 · 生成于 ${reportDateText()}", PAD.toFloat(), y.toFloat(), footerP)

        var fos: FileOutputStream? = null
        try {
            val out = File(path)
            val parent = out.parentFile
            if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.canWrite()) {
                throw Exception("导出目录不可写: $path")
            }
            fos = FileOutputStream(out, false)
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) throw Exception("PNG 编码失败")
            fos.flush()
            fos.fd.sync()
            if (!out.exists() || out.length() <= 0L) throw Exception("PNG 文件未落盘")
        } finally {
            fos?.close()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun drawHeader(
        cv: Canvas,
        sessionName: String,
        sessionWxid: String,
        period: String,
        generated: String,
        maxW: Int,
    ): Int {
        val titleP = paint(46f, COLOR_TITLE, bold = true)
        val metaP = paint(28f, COLOR_META)
        val smallP = paint(24f, COLOR_META)

        val nameLines = wrapCount(sessionName, maxW - 120, titleP).coerceAtLeast(1)
        val metaLines = wrapCount(sessionWxid, maxW - 120, metaP).coerceAtLeast(1)
            + wrapCount(period, maxW - 120, metaP).coerceAtLeast(1)
        val genLines = wrapCount(generated, maxW - 120, smallP).coerceAtLeast(1)
        val headerH = 44 + nameLines * 62 + 20 + metaLines * 38 + 14 + genLines * 32 + 16

        // header 卡片
        val top = PAD - 8
        val bottom = top + headerH
        val cardP = Paint().apply {
            color = Color.WHITE
            setShadowLayer(18f, 0f, 6f, 0x22000000)
        }
        val rect = RectF((PAD - 24).toFloat(), top.toFloat(), (W - PAD + 24).toFloat(), bottom.toFloat())
        cv.drawRoundRect(rect, 28f, 28f, cardP)
        // 左侧强调条
        val accentBar = Paint().apply { color = COLOR_ACCENT }
        cv.drawRoundRect(
            RectF((PAD - 24).toFloat(), top.toFloat(), (PAD - 16).toFloat(), bottom.toFloat()),
            8f, 8f, accentBar,
        )

        // 头像色块（首字）
        val avatarSize = 92
        val avatarPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, avatarSize.toFloat(), avatarSize.toFloat(),
                intArrayOf(0xFF3A7BD5.toInt(), 0xFF12B3A8.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        val avatarRect = RectF(
            (PAD + 8).toFloat(), (top + 28).toFloat(),
            (PAD + 8 + avatarSize).toFloat(), (top + 28 + avatarSize).toFloat(),
        )
        cv.drawRoundRect(avatarRect, 24f, 24f, avatarPaint)
        val avatarTextP = paint(44f, Color.WHITE, bold = true)
        val avatarChar = sessionName.trim().firstOrNull()?.toString() ?: "聊"
        val tw = avatarTextP.measureText(avatarChar)
        cv.drawText(
            avatarChar,
            avatarRect.centerX() - tw / 2,
            avatarRect.centerY() + 16,
            avatarTextP,
        )

        var y = top + 44
        val tx = PAD + 8 + avatarSize + 30
        y = drawWrapped(cv, sessionName, tx, y, maxW - 120, 62, titleP)
        y += 20
        y = drawWrapped(cv, sessionWxid, tx, y, maxW - 120, 38, metaP)
        y += 14
        y = drawWrapped(cv, period, tx, y, maxW - 120, 38, metaP)
        y += 14
        y = drawWrapped(cv, generated, tx, y, maxW - 120, 32, smallP)
        y += 16
        // 时段徽章（右上角）
        val badgeP = paint(26f, Color.WHITE, bold = true)
        val badgeBg = Paint().apply { color = 0xFF3A7BD5.toInt() }
        val badgeW = badgeP.measureText(period) + 48
        val badgeRect = RectF(
            (W - PAD - badgeW).toFloat(), (top + 26).toFloat(),
            (W - PAD + 8).toFloat(), (top + 26 + 52).toFloat(),
        )
        cv.drawRoundRect(badgeRect, 26f, 26f, badgeBg)
        cv.drawText(period, badgeRect.left + 24, badgeRect.centerY() + 9, badgeP)

        return y + 30
    }

    private fun drawCard(cv: Canvas, top: Int, bottom: Int, accent: Int) {
        val cardP = Paint().apply {
            color = COLOR_CARD
            setShadowLayer(14f, 0f, 4f, 0x1A000000)
        }
        val rect = RectF((PAD - 20).toFloat(), top.toFloat(), (W - PAD + 20).toFloat(), bottom.toFloat())
        cv.drawRoundRect(rect, 24f, 24f, cardP)
        val accentP = Paint().apply { color = accent }
        cv.drawRoundRect(
            RectF((PAD - 20).toFloat(), top.toFloat(), (PAD - 12).toFloat(), bottom.toFloat()),
            6f, 6f, accentP,
        )
    }

    private fun unitHeight(u: Unit, maxW: Int, bodyP: Paint, sectionP: Paint, metaP: Paint): Int = when (u) {
        is Unit.Gap -> 18
        is Unit.Section -> 52
        is Unit.BarLine -> 44
        is Unit.TextLine -> wrapCount(u.text, maxW, bodyP) * 40 + 6
    }

    private fun drawUnits(
        cv: Canvas,
        units: List<Unit>,
        startY: Int,
        maxW: Int,
        accent: Int,
    ): Int {
        val bodyP = paint(30f, COLOR_BODY)
        val sectionP = paint(32f, COLOR_TITLE, bold = true)
        val metaP = paint(28f, COLOR_META)
        var y = startY

        for (u in units) {
            when (u) {
                is Unit.Gap -> y += 18
                is Unit.Section -> {
                    // 分区标题：色块 + 文字
                    val accentP = Paint().apply { color = accent }
                    cv.drawRoundRect(
                        RectF((PAD - 8).toFloat(), (y - 34).toFloat(), (PAD - 0).toFloat(), (y - 2).toFloat()),
                        4f, 4f, accentP,
                    )
                    cv.drawText(u.title, (PAD + 8).toFloat(), y.toFloat(), sectionP)
                    y += 52
                }
                is Unit.BarLine -> {
                    // label（左）+ value（中）+ 进度条（右）
                    val labelP = paint(27f, COLOR_BODY)
                    val valueP = paint(27f, COLOR_META, bold = true)
                    cv.drawText(u.label, (PAD + 8).toFloat(), y.toFloat(), labelP)
                    val valueX = (PAD + 8 + 300).toFloat()
                    cv.drawText(u.value, valueX, y.toFloat(), valueP)
                    // 进度条
                    val trackP = Paint().apply { color = 0xFFE4EBF2.toInt() }
                    val trackRect = RectF(
                        (PAD + 8 + 340).toFloat(), (y - 18).toFloat(),
                        (W - PAD - 20).toFloat(), (y - 6).toFloat(),
                    )
                    cv.drawRoundRect(trackRect, 6f, 6f, trackP)
                    val fillP = Paint().apply {
                        shader = LinearGradient(
                            trackRect.left, 0f, trackRect.right, 0f,
                            intArrayOf(accent, lighten(accent)),
                            null, Shader.TileMode.CLAMP,
                        )
                    }
                    val fillW = (trackRect.width() * u.ratio.coerceIn(0f, 1f))
                    if (fillW > 2f) {
                        cv.drawRoundRect(
                            RectF(trackRect.left, trackRect.top, trackRect.left + fillW, trackRect.bottom),
                            6f, 6f, fillP,
                        )
                    }
                    y += 44
                }
                is Unit.TextLine -> {
                    y = drawWrapped(cv, u.text, PAD + 8, y, maxW - 16, 40, bodyP)
                    y += 6
                }
            }
        }
        return y
    }

    private fun lighten(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * 1 + 40
        val g = ((color shr 8) and 0xFF) * 1 + 40
        val b = (color and 0xFF) * 1 + 40
        return Color.rgb(r.coerceAtMost(255), g.coerceAtMost(255), b.coerceAtMost(255))
    }

    // ---------------- 文本绘制工具 ----------------

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = true
        this.color = color
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }

    private fun wrapCount(text: String, maxW: Int, p: Paint): Int {
        if (text.isEmpty()) return 0
        var lines = 0
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                lines++
                continue
            }
            var start = 0
            while (start < para.length) {
                var end = para.length
                while (end > start && p.measureText(para.substring(start, end)) > maxW) end--
                if (end == start) end = start + 1
                lines++
                start = end
            }
        }
        return lines
    }

    private fun drawWrapped(
        cv: Canvas,
        text: String,
        x: Int,
        y: Int,
        maxW: Int,
        lineH: Int,
        p: Paint,
    ): Int {
        if (text.isEmpty()) return y
        var cur = y
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                cur += lineH
                continue
            }
            var start = 0
            while (start < para.length) {
                var end = para.length
                while (end > start && p.measureText(para.substring(start, end)) > maxW) end--
                if (end == start) end = start + 1
                cv.drawText(para.substring(start, end), x.toFloat(), cur.toFloat(), p)
                cur += lineH
                start = end
            }
        }
        return cur
    }

    private fun reportDateText(): String =
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA).format(Date())
}
