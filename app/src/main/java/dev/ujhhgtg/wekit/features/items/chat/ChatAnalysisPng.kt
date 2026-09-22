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
 * 聊天记录分析 —— 报告 PNG 导出（排版重构版 v2）
 *
 * ── 几何模型（全部 px，基于 1080 宽画布，常量集中在文件顶部）─────────────────
 *
 *   0        CANVAS_PAD                                     W-CANVAS_PAD    W
 *   │◄──────────►│                                                │◄─────────►│
 *   ┌───────────────────────────────────────────────────────────────────────┐
 *   │ 卡片：CARD_LEFT … CARD_RIGHT（圆角 / 描边 / 左侧强调条全部一致）        │
 *   │  ┌ CARD_PAD_H ────────────────────────────────── CARD_PAD_H ─┐        │
 *   │  │            内容区 CONTENT_LEFT … CONTENT_RIGHT             │        │
 *   │  └───────────────────────────────────────────────────────────┘        │
 *   └───────────────────────────────────────────────────────────────────────┘
 *
 *   条形行严格分三列（列间 COL_GAP，两两互不相交）：
 *     [ label ← LABEL_W = 42% CONTENT_W ] gap [ value ← VALUE_W = 22% ] gap [ bar ← 余量 ]
 *
 * ── 排版铁律 ─────────────────────────────────────────────────────────────
 *  1. 所有卡片左右边界统一（CARD_LEFT / CARD_RIGHT），上下内边距统一（CARD_PAD_V）。
 *  2. 卡片高度 = CARD_PAD_V + 内容高度 + CARD_PAD_V。先做一遍纯几何布局
 *     （layoutBlocks），再按同一份行位置一次性绘出，文字不可能溢出卡片。
 *  3. 每段文字都画在自己列的矩形内（canvas.save → clipRect → drawText → restore），
 *     即便文本测量有偏差也绝不会串到相邻列或相邻组件。
 *  4. 文字基线统一由 fitBaseline() 计算，并受容器底边硬约束：
 *     baseline + descent ≤ 容器 bottom（越界则上移，永不溢出）。
 *  5. 画布高度按内容累加，底部留 BOTTOM_PAD 收尾，内容永不贴边。
 */
object ChatAnalysisPng {

    // ==================================================================
    // 一、统一度量常量（全部 px，基于 1080 宽画布）
    // ==================================================================

    /** 画布宽度 */
    private const val W = 1080

    /** 画布左右安全边距（所有卡片都对它对齐） */
    private const val CANVAS_PAD = 40

    /** 卡片内水平内边距 */
    private const val CARD_PAD_H = 32

    /** 卡片内垂直内边距（上下一致） */
    private const val CARD_PAD_V = 26

    /** 卡片与卡片之间的间距 */
    private const val CARD_GAP = 28

    /** 卡片圆角半径（所有卡片统一） */
    private const val CARD_RADIUS = 26f

    /** 卡片左侧强调条宽度 */
    private const val CARD_ACCENT_W = 6

    /** 卡片最小高度 */
    private const val CARD_MIN_H = 120

    /** 画布底部收尾留白 */
    private const val BOTTOM_PAD = 40

    /** 普通行高（建议值 56） */
    private const val ROW_H = 56

    /** 条形行高（比普通行略高，行与行之间更透气） */
    private const val BAR_ROW_H = 64

    /** 文本行高（建议值 48） */
    private const val TEXT_LINE_H = 48

    /** 文本行之间的额外留白 */
    private const val TEXT_LINE_GAP = 12

    /** 分区标题整块高度（含上下留白，保证不与上一行/首行重合） */
    private const val SECTION_TITLE_H = 84

    /** 分区标题淡色背景条高度 */
    private const val SECTION_BAND_H = 56

    /** 空行占位高度 */
    private const val GAP_H = 20

    /** 页脚行高 */
    private const val FOOTER_H = 40

    /** 顶部信息卡：主标题行高 */
    private const val HEADER_TITLE_LINE_H = 72

    /** 顶部信息卡：副标题 / 生成时间行高 */
    private const val HEADER_META_LINE_H = 48

    /** 顶部信息卡：主标题与副标题之间留白 */
    private const val HEADER_TITLE_SUB_GAP = 10

    /** 顶部信息卡：副标题与生成时间之间留白 */
    private const val HEADER_SUB_GEN_GAP = 6

    /** 顶部信息卡头像边长 */
    private const val AVATAR_SIZE = 96

    /** 头像与右侧文字列的水平间距 */
    private const val AVATAR_TEXT_GAP = 28

    /** 右上角范围徽章高度 */
    private const val BADGE_H = 48

    /** 右上角范围徽章水平内边距（单侧） */
    private const val BADGE_PAD_H = 24

    /** 右上角范围徽章最大宽度（超出则截断，防止挤占标题） */
    private const val BADGE_MAX_W = 312

    /** 列间距 */
    private const val COL_GAP = 16

    /** 条形轨高度 */
    private const val BAR_TRACK_H = 20f

    /** 条形最小可见宽度 */
    private const val BAR_MIN_W = 4f

    /** 文本 / 标签在列内的横向内缩 */
    private const val CELL_INSET = 4

    /** 画布最小高度 */
    private const val MIN_H = 600

    /** 画布高度上限（超过直接拒绝导出，避免 OOM） */
    private const val MAX_HEIGHT = 30000

    // ---- 由上面的常量推导：卡片 / 内容区 ----
    private const val CARD_LEFT = CANVAS_PAD                                  // 40
    private const val CARD_RIGHT = W - CANVAS_PAD                             // 1040
    private const val CONTENT_LEFT = CARD_LEFT + CARD_PAD_H                   // 72
    private const val CONTENT_RIGHT = CARD_RIGHT - CARD_PAD_H                 // 1008
    private const val CONTENT_W = CONTENT_RIGHT - CONTENT_LEFT                // 936

    // ---- 由上面的常量推导：条形行三列（42% / 22% / 余量 − 2×COL_GAP）----
    private const val LABEL_W = CONTENT_W * 42 / 100                          // 393
    private const val VALUE_W = CONTENT_W * 22 / 100                          // 205
    private const val BAR_W = CONTENT_W - LABEL_W - VALUE_W - COL_GAP * 2     // 306

    private const val LABEL_LEFT = CONTENT_LEFT                               // 72
    private const val LABEL_RIGHT = LABEL_LEFT + LABEL_W                      // 465
    private const val VALUE_LEFT = LABEL_RIGHT + COL_GAP                      // 481
    private const val VALUE_RIGHT = VALUE_LEFT + VALUE_W                      // 686
    private const val BAR_LEFT = VALUE_RIGHT + COL_GAP                        // 702
    private const val BAR_RIGHT = BAR_LEFT + BAR_W                            // 1008

    // ---- 字号（px）----
    private const val FS_TITLE = 46f
    private const val FS_SECTION = 32f
    private const val FS_BODY = 30f
    private const val FS_ROW = 28f
    private const val FS_META = 28f
    private const val FS_SMALL = 26f
    private const val FS_AVATAR = 44f

    // ---- 配色 ----
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
    private const val COLOR_TRACK = 0xFFE4EBF2.toInt()
    private const val COLOR_RANK_GOLD = 0xFFD89A16.toInt()
    private const val COLOR_RANK_SILVER = 0xFF7F8C9B.toInt()
    private const val COLOR_RANK_BRONZE = 0xFFB9754A.toInt()

    init {
        // 排版硬约束：三列两两不相交，且恰好占满卡片内容区
        require(LABEL_RIGHT <= VALUE_LEFT) { "PNG 排版列重叠：label/value" }
        require(VALUE_RIGHT <= BAR_LEFT) { "PNG 排版列重叠：value/bar" }
        require(LABEL_LEFT == CONTENT_LEFT && BAR_RIGHT == CONTENT_RIGHT) {
            "PNG 列未占满卡片内容区"
        }
        require(CONTENT_LEFT > CARD_LEFT && CONTENT_RIGHT < CARD_RIGHT) {
            "PNG 卡片内边距非法"
        }
        require(BAR_W > 0 && LABEL_W > 0 && VALUE_W > 0) { "PNG 列宽非法" }
        // 条形行不得矮于普通行高
        require(BAR_ROW_H >= ROW_H) { "PNG 行高非法" }
    }

    // ==================================================================
    // 二、数据模型（解析 → 布局 → 绘制三段式）
    // ==================================================================

    /** 报告文本切分出的最小单元 */
    private sealed class Block {
        data class Section(val title: String) : Block()
        data class BarLine(val label: String, val value: String, val ratio: Float, val rank: Int) : Block()
        data class TextLine(val text: String) : Block()
        object Gap : Block()
    }

    /** 已定位的行：top / height 都是相对卡片内容区顶部的偏移 */
    private class Row(
        val unit: Block,
        val top: Int,
        val height: Int,
        val lines: List<String> = emptyList(),
    )

    /** 顶部信息卡的布局结果 */
    private class HeaderSpec(
        val avatarChar: String,
        val nameLines: List<String>,
        val subLines: List<String>,
        val genLines: List<String>,
        val nameTop: Int,
        val subTop: Int,
        val genTop: Int,
        val textLeft: Int,
        val textRight: Int,
        val badgeText: String,
        val badgeW: Int,
        val height: Int,
    )

    // ==================================================================
    // 三、对外接口（签名保持不变，被 ChatRecordAnalysis.kt 调用）
    // ==================================================================

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

    // ==================================================================
    // 四、文本解析
    // ==================================================================

    private val RANK_LINE = Regex("^(\\d+[.．、]?\\s*.*?)[:：]\\s*(\\d+)\\s*条?$")
    private val RANK_PREFIX = Regex("^(\\d+)[.．、]")

    private fun parseBlocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        if (text.isBlank()) return out
        for (line in text.split("\n")) {
            val t = line.trim()
            when {
                t.isEmpty() -> out.add(Block.Gap)
                t.startsWith("【") && t.endsWith("】") -> {
                    out.add(Block.Gap)
                    out.add(Block.Section(t.removeSurrounding("【", "】")))
                }
                t.contains("█") -> out.add(parseBarLine(t))
                else -> out.add(Block.TextLine(t))
            }
        }
        return out
    }

    private fun parseBarLine(rawLine: String): Block.BarLine {
        val barLen = rawLine.count { it == '█' }
        val clean = rawLine.replace("█", "").trim()
        var label = clean
        var value = ""
        val rankM = RANK_LINE.find(clean)
        if (rankM != null) {
            // 排行行："1. 张三：45 条" → label=1. 张三, value=45条
            label = rankM.groupValues[1].trim()
            value = rankM.groupValues[2] + "条"
        } else {
            // 载体偏好 / 活跃频次："文字 5468" → label=文字, value=5468
            val lastSpace = clean.lastIndexOf(' ')
            if (lastSpace > 0 && clean.substring(lastSpace + 1).trim().all { it.isDigit() }) {
                label = clean.substring(0, lastSpace).trim()
                value = clean.substring(lastSpace + 1).trim()
            }
        }
        val rank = RANK_PREFIX.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Block.BarLine(label, value, (barLen / 16f).coerceIn(0f, 1f), rank)
    }

    // ==================================================================
    // 五、布局（纯几何，先量后排）
    // ==================================================================

    private fun cardHeight(rows: List<Row>): Int {
        val last = rows.lastOrNull() ?: return CARD_MIN_H
        return maxOf(CARD_MIN_H, CARD_PAD_V * 2 + last.top + last.height)
    }

    private fun layoutBlocks(units: List<Block>, bodyP: Paint): List<Row> {
        val rows = ArrayList<Row>(units.size)
        var y = 0
        for (u in units) {
            when (u) {
                is Block.Gap -> {
                    rows.add(Row(u, y, GAP_H))
                    y += GAP_H
                }
                is Block.Section -> {
                    rows.add(Row(u, y, SECTION_TITLE_H))
                    y += SECTION_TITLE_H
                }
                is Block.BarLine -> {
                    rows.add(Row(u, y, BAR_ROW_H))
                    y += BAR_ROW_H
                }
                is Block.TextLine -> {
                    val lines = wrapLines(u.text, CONTENT_W.toFloat(), bodyP)
                    val count = lines.size.coerceAtLeast(1)
                    val h = count * TEXT_LINE_H + TEXT_LINE_GAP
                    rows.add(Row(u, y, h, lines))
                    y += h
                }
            }
        }
        return rows
    }

    private fun layoutHeader(
        sessionName: String,
        sessionWxid: String,
        period: String,
        generated: String,
    ): HeaderSpec {
        val titleP = paint(FS_TITLE, COLOR_TITLE, bold = true)
        val metaP = paint(FS_META, COLOR_META)
        val smallP = paint(FS_SMALL, COLOR_META)
        val badgeP = paint(FS_SMALL, Color.WHITE, bold = true)

        // 徽章只显示范围标签（period = "范围 · 会话名"，会话名已在标题里，不重复）
        val badgeText = period.substringBefore(" · ").trim().ifEmpty { period.trim() }
        val badgeW = if (badgeText.isEmpty()) {
            0
        } else {
            (badgeP.measureText(badgeText).toInt() + BADGE_PAD_H * 2).coerceAtMost(BADGE_MAX_W)
        }
        val badgeReserve = if (badgeW == 0) 0 else badgeW + COL_GAP

        val textLeft = CONTENT_LEFT + AVATAR_SIZE + AVATAR_TEXT_GAP
        val textRight = CONTENT_RIGHT - badgeReserve
        val colW = (textRight - textLeft).toFloat().coerceAtLeast(120f)

        val nameLines = wrapLines(sessionName.trim().ifEmpty { "聊天记录分析" }, colW, titleP)
        val subLines = wrapLines(sessionWxid.trim().ifEmpty { "（未知会话）" }, colW, metaP)
        val genLines = wrapLines(generated, colW, smallP)

        val nameH = nameLines.size.coerceAtLeast(1) * HEADER_TITLE_LINE_H
        val subH = subLines.size.coerceAtLeast(1) * HEADER_META_LINE_H
        val genH = genLines.size.coerceAtLeast(1) * HEADER_META_LINE_H
        val nameTop = 0
        val subTop = nameH + HEADER_TITLE_SUB_GAP
        val genTop = subTop + subH + HEADER_SUB_GEN_GAP
        val contentH = maxOf(genTop + genH, AVATAR_SIZE)

        return HeaderSpec(
            avatarChar = firstGlyph(sessionName),
            nameLines = nameLines,
            subLines = subLines,
            genLines = genLines,
            nameTop = nameTop,
            subTop = subTop,
            genTop = genTop,
            textLeft = textLeft,
            textRight = textRight,
            badgeText = badgeText,
            badgeW = badgeW,
            height = CARD_PAD_V * 2 + contentH,
        )
    }

    // ==================================================================
    // 六、绘制
    // ==================================================================

    @Throws(Exception::class)
    private fun drawToFile(
        stats: String,
        ai: String,
        sessionName: String,
        sessionWxid: String,
        period: String,
        path: String,
    ) {
        val generated = "分析生成于 ${reportDateText()}"
        val bodyP = paint(FS_BODY, COLOR_BODY)

        // ---- 第一遍：纯几何（先把所有高度算准，再画）----
        val statsRows = layoutBlocks(parseBlocks(stats), bodyP)
        val aiRows = layoutBlocks(parseBlocks(ai), bodyP)
        val header = layoutHeader(sessionName, sessionWxid, period, generated)

        val statsH = if (statsRows.isEmpty()) 0 else cardHeight(statsRows)
        val aiH = if (aiRows.isEmpty()) 0 else cardHeight(aiRows)

        var total = CANVAS_PAD + header.height
        if (statsRows.isNotEmpty()) total += CARD_GAP + statsH
        if (aiRows.isNotEmpty()) total += CARD_GAP + aiH
        total += FOOTER_H + BOTTOM_PAD
        if (total < MIN_H) total = MIN_H
        if (total > MAX_HEIGHT) throw Exception("报告过长，请缩小分析范围后再导出")

        val bmp = Bitmap.createBitmap(W, total, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bg = LinearGradient(
            0f, 0f, 0f, total.toFloat(),
            intArrayOf(COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_BOTTOM),
            floatArrayOf(0f, 0.22f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, W.toFloat(), total.toFloat(), Paint().apply { shader = bg })

        // ---- 第二遍：按第一遍算出的同一份几何绘制 ----
        var y = CANVAS_PAD
        drawHeaderCard(cv, y, header)
        y += header.height

        if (statsRows.isNotEmpty()) {
            y += CARD_GAP
            drawCardBlock(cv, y, statsRows, statsH, COLOR_ACCENT, bodyP)
            y += statsH
        }
        if (aiRows.isNotEmpty()) {
            y += CARD_GAP
            drawCardBlock(cv, y, aiRows, aiH, COLOR_ACCENT2, bodyP)
            y += aiH
        }
        drawFooter(cv, y, generated)

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

    /** 统一的卡片外观：完全相同的左右边界、圆角、描边、左侧强调条 */
    private fun drawCard(cv: Canvas, top: Int, bottom: Int, accent: Int) {
        val rect = RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), bottom.toFloat())
        val cardP = Paint().apply {
            isAntiAlias = true
            color = COLOR_CARD
            style = Paint.Style.FILL
            setShadowLayer(14f, 0f, 4f, 0x1A000000)
        }
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, cardP)
        cardP.clearShadowLayer()

        val borderP = Paint().apply {
            isAntiAlias = true
            color = COLOR_RULE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, borderP)

        val accentP = Paint().apply { isAntiAlias = true; color = accent }
        cv.drawRoundRect(
            RectF(
                CARD_LEFT.toFloat(),
                top.toFloat(),
                (CARD_LEFT + CARD_ACCENT_W).toFloat(),
                bottom.toFloat(),
            ),
            3f, 3f, accentP,
        )
    }

    private fun drawHeaderCard(cv: Canvas, top: Int, spec: HeaderSpec) {
        val bottom = top + spec.height
        drawCard(cv, top, bottom, COLOR_ACCENT)

        val inner = top + CARD_PAD_V
        val innerBottom = (bottom - CARD_PAD_V).toFloat()

        // ---- 头像 ----
        val avRect = RectF(
            CONTENT_LEFT.toFloat(), inner.toFloat(),
            (CONTENT_LEFT + AVATAR_SIZE).toFloat(), (inner + AVATAR_SIZE).toFloat(),
        )
        val avPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                avRect.left, avRect.top, avRect.right, avRect.bottom,
                intArrayOf(0xFF3A7BD5.toInt(), 0xFF12B3A8.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        cv.drawRoundRect(avRect, 24f, 24f, avPaint)
        val avP = paint(FS_AVATAR, Color.WHITE, bold = true)
        val avW = avP.measureText(spec.avatarChar)
        drawClipped(
            cv, spec.avatarChar,
            avRect.centerX() - avW / 2f,
            fitBaseline(avRect.top, AVATAR_SIZE.toFloat(), avP, avRect.bottom),
            avRect, avP,
        )

        // ---- 右上角范围徽章（只占标题行高度，标题列已按 badgeReserve 让位）----
        if (spec.badgeW > 0) {
            val bTop = inner + spec.nameTop + (HEADER_TITLE_LINE_H - BADGE_H) / 2f
            val bRect = RectF(
                (CONTENT_RIGHT - spec.badgeW).toFloat(), bTop,
                CONTENT_RIGHT.toFloat(), bTop + BADGE_H,
            )
            val bP = Paint().apply { isAntiAlias = true; color = COLOR_ACCENT2 }
            cv.drawRoundRect(bRect, BADGE_H / 2f, BADGE_H / 2f, bP)
            val btP = paint(FS_SMALL, Color.WHITE, bold = true)
            val bt = truncateToWidth(spec.badgeText, (spec.badgeW - BADGE_PAD_H * 2).toFloat(), btP)
            drawClipped(
                cv, bt,
                bRect.centerX() - btP.measureText(bt) / 2f,
                fitBaseline(bRect.top, BADGE_H.toFloat(), btP, bRect.bottom),
                bRect, btP,
            )
        }

        // ---- 三行文字：标题 / 副标题 / 生成时间，各自矩形内硬裁剪 + 底边硬约束 ----
        val titleP = paint(FS_TITLE, COLOR_TITLE, bold = true)
        val metaP = paint(FS_META, COLOR_META)
        val smallP = paint(FS_SMALL, COLOR_META)
        drawTextLines(
            cv, spec.nameLines, spec.textLeft.toFloat(), (inner + spec.nameTop).toFloat(),
            HEADER_TITLE_LINE_H, titleP, spec.textRight.toFloat(), innerBottom,
        )
        drawTextLines(
            cv, spec.subLines, spec.textLeft.toFloat(), (inner + spec.subTop).toFloat(),
            HEADER_META_LINE_H, metaP, spec.textRight.toFloat(), innerBottom,
        )
        drawTextLines(
            cv, spec.genLines, spec.textLeft.toFloat(), (inner + spec.genTop).toFloat(),
            HEADER_META_LINE_H, smallP, spec.textRight.toFloat(), innerBottom,
        )
    }

    private fun drawCardBlock(
        cv: Canvas,
        top: Int,
        rows: List<Row>,
        height: Int,
        accent: Int,
        bodyP: Paint,
    ) {
        drawCard(cv, top, top + height, accent)
        val inner = top + CARD_PAD_V
        val innerBottom = (top + height - CARD_PAD_V).toFloat()
        for (row in rows) {
            val rowTop = (inner + row.top).toFloat()
            when (val u = row.unit) {
                is Block.Section -> drawSectionRow(cv, u.title, rowTop, accent, innerBottom)
                is Block.BarLine -> drawBarRow(cv, u, rowTop, row.height.toFloat(), innerBottom)
                is Block.TextLine -> drawTextLines(
                    cv, row.lines, CONTENT_LEFT.toFloat(), rowTop,
                    TEXT_LINE_H, bodyP, CONTENT_RIGHT.toFloat(), innerBottom,
                )
                is Block.Gap -> {
                    // 空行：只占位，不绘制
                }
            }
        }
    }

    /** 分区标题：淡色背景条 + 左侧强调竖条 + 标题文字（整块高度 SECTION_TITLE_H） */
    private fun drawSectionRow(cv: Canvas, title: String, rowTop: Float, accent: Int, limitBottom: Float) {
        val bandTop = rowTop + (SECTION_TITLE_H - SECTION_BAND_H) / 2f
        val bandBottom = bandTop + SECTION_BAND_H
        val band = RectF(CONTENT_LEFT.toFloat(), bandTop, CONTENT_RIGHT.toFloat(), bandBottom)

        cv.drawRoundRect(band, 14f, 14f, Paint().apply {
            isAntiAlias = true
            color = withAlpha(accent, 0x1A)
        })
        cv.drawRoundRect(
            RectF(CONTENT_LEFT + 12f, bandTop + 14f, CONTENT_LEFT + 18f, bandBottom - 14f),
            3f, 3f,
            Paint().apply { isAntiAlias = true; color = accent },
        )

        val p = paint(FS_SECTION, COLOR_TITLE, bold = true)
        val clip = RectF(CONTENT_LEFT + 30f, bandTop, (CONTENT_RIGHT - 12).toFloat(), bandBottom)
        val text = truncateToWidth(title, clip.width() - CELL_INSET, p)
        drawClipped(
            cv, text, CONTENT_LEFT + 38f,
            fitBaseline(bandTop, SECTION_BAND_H.toFloat(), p, minOf(bandBottom, limitBottom)),
            clip, p,
        )
    }

    /**
     * 条形行：严格三列。
     * 1) 先画条形（只在 bar 列内，长度按比例、最小 BAR_MIN_W、最大不超过列宽）
     * 2) 再画标签（label 列内，超宽按测量宽度逐字截断加 "…"）
     * 3) 再画数值（value 列内右对齐）
     */
    private fun drawBarRow(cv: Canvas, u: Block.BarLine, rowTop: Float, rowH: Float, limitBottom: Float) {
        val rowBottom = rowTop + rowH
        val labelP = paint(FS_ROW, COLOR_BODY)
        val valueP = paint(FS_ROW, valueColor(u.rank), bold = true)
        val baseline = fitBaseline(rowTop, rowH, labelP, minOf(rowBottom, limitBottom))

        // ---- 1) bar 列 ----
        val barTop = rowTop + (rowH - BAR_TRACK_H) / 2f
        val track = RectF(BAR_LEFT.toFloat(), barTop, BAR_RIGHT.toFloat(), barTop + BAR_TRACK_H)
        cv.drawRoundRect(track, BAR_TRACK_H / 2f, BAR_TRACK_H / 2f, Paint().apply {
            isAntiAlias = true
            color = COLOR_TRACK
        })
        if (u.ratio > 0f) {
            val fillW = (BAR_W * u.ratio).coerceIn(BAR_MIN_W, BAR_W.toFloat())
            val fill = RectF(track.left, track.top, track.left + fillW, track.bottom)
            val fillP = Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    track.left, 0f, track.right, 0f,
                    intArrayOf(COLOR_ACCENT, lighten(COLOR_ACCENT)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            drawClippedRect(cv, fill, BAR_LEFT.toFloat(), BAR_RIGHT.toFloat(), fillP)
        }

        // ---- 2) label 列 ----
        val labelClip = RectF(LABEL_LEFT.toFloat(), rowTop, LABEL_RIGHT.toFloat(), rowBottom)
        val labelText = truncateToWidth(u.label, (LABEL_W - CELL_INSET * 2).toFloat(), labelP)
        drawClipped(cv, labelText, (LABEL_LEFT + CELL_INSET).toFloat(), baseline, labelClip, labelP)

        // ---- 3) value 列（右对齐到列右边界）----
        val valueClip = RectF(VALUE_LEFT.toFloat(), rowTop, VALUE_RIGHT.toFloat(), rowBottom)
        val vx = (VALUE_RIGHT - CELL_INSET).toFloat() - valueP.measureText(u.value)
        drawClipped(cv, u.value, vx, baseline, valueClip, valueP)
    }

    private fun drawFooter(cv: Canvas, top: Int, generated: String) {
        val p = paint(FS_SMALL, COLOR_META)
        val text = truncateToWidth("WeKit · 聊天记录分析 · $generated", CONTENT_W.toFloat(), p)
        val clip = RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), (top + FOOTER_H).toFloat())
        drawClipped(
            cv, text,
            W / 2f - p.measureText(text) / 2f,
            fitBaseline(top.toFloat(), FOOTER_H.toFloat(), p, clip.bottom),
            clip, p,
        )
    }

    // ==================================================================
    // 七、绘制工具（文本永远不越界）
    // ==================================================================

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = true
        this.color = color
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }

    /**
     * 行内垂直居中基线，并受容器底边硬约束：
     * 返回值保证 baseline + descent ≤ limitBottom（越界则上移，绝不溢出容器）。
     */
    private fun fitBaseline(rowTop: Float, rowH: Float, p: Paint, limitBottom: Float): Float {
        val fm = p.fontMetrics
        val centered = rowTop + (rowH - fm.descent + fm.ascent) / 2f - fm.ascent
        val maxBaseline = limitBottom - fm.descent
        return if (centered > maxBaseline) maxBaseline else centered
    }

    /** 连续多行文本：逐行推进 y（行高固定），每行硬裁剪在自己的行矩形内 */
    private fun drawTextLines(
        cv: Canvas,
        lines: List<String>,
        x: Float,
        firstTop: Float,
        lineH: Int,
        p: Paint,
        clipRight: Float,
        limitBottom: Float,
    ) {
        var top = firstTop
        for (line in lines) {
            val baseline = fitBaseline(top, lineH.toFloat(), p, minOf(top + lineH, limitBottom))
            val clip = RectF(CONTENT_LEFT.toFloat(), top, clipRight, top + lineH)
            drawClipped(cv, line, x, baseline, clip, p)
            top += lineH
        }
    }

    /** 硬裁剪绘制文本：clipRect 兜底，测量偏差也不可能画到别的列/卡片外 */
    private fun drawClipped(cv: Canvas, text: String, x: Float, baseline: Float, clip: RectF, p: Paint) {
        if (text.isEmpty() || clip.right <= clip.left || clip.bottom <= clip.top) return
        cv.save()
        cv.clipRect(clip)
        cv.drawText(text, maxOf(x, clip.left), baseline, p)
        cv.restore()
    }

    /** 硬裁剪绘制矩形（条形填充用），横向严格限制在列内 */
    private fun drawClippedRect(cv: Canvas, rect: RectF, clipLeft: Float, clipRight: Float, p: Paint) {
        cv.save()
        cv.clipRect(clipLeft, rect.top, clipRight, rect.bottom)
        cv.drawRoundRect(rect, BAR_TRACK_H / 2f, BAR_TRACK_H / 2f, p)
        cv.restore()
    }

    /** 按测量宽度换行（不丢字），空段落保留为空行 */
    private fun wrapLines(text: String, maxW: Float, p: Paint): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                out.add("")
                continue
            }
            var start = 0
            while (start < para.length) {
                var end = para.length
                while (end > start && p.measureText(para, start, end) > maxW) end--
                if (end == start) end = start + 1
                // 不要把代理对（emoji 等）截成半截字符
                if (end < para.length && Character.isLowSurrogate(para[end])) end++
                out.add(para.substring(start, end))
                start = end
            }
        }
        return out
    }

    /** 超宽则按 Paint.measureText 逐字截断并补 "…"（不猜宽度） */
    private fun truncateToWidth(text: String, maxW: Float, p: Paint): String {
        if (text.isEmpty() || maxW <= 0f) return text
        if (p.measureText(text) <= maxW) return text
        val ellW = p.measureText("…")
        var end = text.length
        while (end > 0) {
            if (p.measureText(text, 0, end) + ellW <= maxW) return text.substring(0, end) + "…"
            end--
            if (end >= 2 && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) {
                end--
            }
        }
        return "…"
    }

    private fun firstGlyph(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "聊"
        return try {
            String(Character.toChars(t.codePointAt(0)))
        } catch (e: Exception) {
            t.substring(0, 1)
        }
    }

    private fun valueColor(rank: Int): Int = when (rank) {
        1 -> COLOR_RANK_GOLD
        2 -> COLOR_RANK_SILVER
        3 -> COLOR_RANK_BRONZE
        else -> COLOR_ACCENT
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun lighten(color: Int): Int = Color.rgb(
        (Color.red(color) + 40).coerceAtMost(255),
        (Color.green(color) + 40).coerceAtMost(255),
        (Color.blue(color) + 40).coerceAtMost(255),
    )

    private fun reportDateText(): String =
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA).format(Date())
}
