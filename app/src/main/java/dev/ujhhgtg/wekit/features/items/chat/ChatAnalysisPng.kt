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
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天记录分析 —— 报告 PNG 导出（排版重构版 v3 · 专业分析报告观感）
 *
 * ── 几何模型（全部 px，基于 1080 宽画布，常量集中在文件顶部）─────────────────
 *
 *   0     CANVAS_PAD(48)                                 W-CANVAS_PAD(1032)  1080
 *   │◄────────►│                                                      │◄──────►│
 *   ┌─────────────────────────────────────────────────────────────────────────┐
 *   │ 卡片：CARD_LEFT … CARD_RIGHT（圆角 / 细描边 / 左侧渐变强调条全部一致）    │
 *   │  ┌ CARD_PAD_H(36) ──────────────────────────────── CARD_PAD_H(36) ─┐   │
 *   │  │             内容区 CONTENT_LEFT(84) … CONTENT_RIGHT(996)         │   │
 *   │  └─────────────────────────────────────────────────────────────────┘   │
 *   └─────────────────────────────────────────────────────────────────────────┘
 *
 *   条形行严格分三列（列间 COL_GAP(20)，两两互不相交）：
 *     [ label ← LABEL_W(383) ] gap [ value ← VALUE_W(200) ] gap [ bar ← BAR_W(289) ]
 *
 *   每个【】章节 = 一张独立卡片（标题 + 序号徽章 + 渐变下划线 + 正文），
 *   连续的 "键：值" 行聚合成 KPI 大数字网格（每行 2 格）。
 *
 * ── 排版铁律 ─────────────────────────────────────────────────────────────
 *  1. 所有卡片左右边界统一（CARD_LEFT / CARD_RIGHT），上下内边距统一（CARD_PAD_V）。
 *  2. 两遍法：先做一遍纯几何布局（layoutHeader / buildCards → 每行 top/height），
 *     再按同一份几何一次性绘出，卡片高度由内容高度反推，文字不可能溢出卡片。
 *  3. 每段文字都画在自己列的矩形内（canvas.save → clipRect → drawText → restore），
 *     每张卡片再额外 clip 一次卡片矩形，即便测量有偏差也绝不会串到相邻列/卡片外。
 *  4. 文字基线统一由 fitBaseline() 计算，并受容器底边硬约束：
 *     baseline + descent ≤ 容器 bottom（越界则上移，永不溢出）。
 *  5. 画布高度按内容累加，底部留 BOTTOM_PAD 收尾；超长报告走"报告过长"降级路径。
 *  6. 只有 1 张 Bitmap（即画布本身），不做任何全图拷贝，避免宿主堆 OOM。
 */
object ChatAnalysisPng {

    // ==================================================================
    // 一、统一度量常量（全部 px，基于 1080 宽画布）
    // ==================================================================

    /** 画布宽度 */
    private const val W = 1080

    /** 画布左右安全边距（硬约束：≥48px，所有卡片都对它对齐） */
    private const val CANVAS_PAD = 56

    /** 卡片内水平内边距 */
    private const val CARD_PAD_H = 40

    /** 卡片内垂直内边距（上下一致） */
    private const val CARD_PAD_V = 36

    /** 卡片与卡片之间的间距 */
    private const val CARD_GAP = 40

    /** 卡片圆角半径（所有卡片统一） */
    private const val CARD_RADIUS = 32f

    /** 卡片左侧强调条宽度 */
    private const val CARD_ACCENT_W = 8

    /** 卡片最小高度 */
    private const val CARD_MIN_H = 168

    /** 画布底部收尾留白 */
    private const val BOTTOM_PAD = 64

    /** 条形行 / 单行键值行高 */
    private const val ROW_H = 66

    /** 正文文本行高 */
    private const val TEXT_LINE_H = 56

    /** 正文文本行之间的额外留白 */
    private const val TEXT_LINE_GAP = 18

    /** 空行占位高度（段间距） */
    private const val GAP_H = 26

    /** 章节卡片头部高度（序号徽章 + 标题 + 渐变下划线） */
    private const val SECTION_HEADER_H = 120

    /** 章节标题下方的渐变下划线宽度 / 厚度 */
    private const val SECTION_UNDERLINE_W = 180
    private const val SECTION_UNDERLINE_H = 6f

    /** 章节序号徽章边长 */
    private const val SECTION_BADGE_BOX = 56

    /** 分组 pill（本地统计报告 / AI 洞察报告）高度 */
    private const val GROUP_PILL_H = 68

    /** 分组 pill 与上一张卡片的间距（分组间隔更大，节奏分明） */
    private const val GROUP_PILL_GAP = 44

    /** 分组 pill 与紧随其后的卡片之间的间距 */
    private const val GROUP_PILL_CARD_GAP = 14

    /** 分组 pill 最大宽度 */
    private const val GROUP_PILL_MAX_W = 520

    /** 分组 pill 内部：左内边距 / 圆点直径 / 圆点与文字间距 / 右内边距 */
    private const val GROUP_PILL_PAD_L = 26
    private const val GROUP_PILL_DOT = 16
    private const val GROUP_PILL_DOT_GAP = 16
    private const val GROUP_PILL_PAD_R = 24

    /** 页脚高度 / 内部节奏（上留白 / 文字行高 / 文字与品牌条间距 / 品牌条厚度） */
    private const val FOOTER_H = 64
    private const val FOOTER_TOP_GAP = 8
    private const val FOOTER_TEXT_H = 40
    private const val FOOTER_STRIP_GAP = 8
    private const val FOOTER_STRIP_H = 5f

    /** 顶部信息卡：品牌行高 */
    private const val BRAND_LINE_H = 62

    /** 顶部信息卡：品牌小方块边长 */
    private const val BRAND_BOX = 16

    /** 顶部信息卡：主标题行高 */
    private const val HEADER_TITLE_LINE_H = 86

    /** 顶部信息卡：副标题 / 生成时间行高 */
    private const val HEADER_META_LINE_H = 52

    /** 顶部信息卡：品牌行与主标题之间留白 */
    private const val HEADER_BRAND_TITLE_GAP = 12

    /** 顶部信息卡：主标题与副标题之间留白 */
    private const val HEADER_TITLE_SUB_GAP = 10

    /** 顶部信息卡：副标题与生成时间之间留白 */
    private const val HEADER_SUB_GEN_GAP = 6

    /** 顶部信息卡头像边长 */
    private const val AVATAR_SIZE = 112

    /** 头像与右侧文字列的水平间距 */
    private const val AVATAR_TEXT_GAP = 30

    /** 右上角范围徽章高度 */
    private const val BADGE_H = 56

    /** 右上角范围徽章水平内边距（单侧） */
    private const val BADGE_PAD_H = 26

    /** 右上角范围徽章最大宽度（超出则截断，防止挤占品牌行） */
    private const val BADGE_MAX_W = 360

    /** 列间距 */
    private const val COL_GAP = 20

    /** 条形轨高度 */
    private const val BAR_TRACK_H = 22f

    /** 条形最小可见宽度 */
    private const val BAR_MIN_W = 6f

    /** 文本 / 标签在列内的横向内缩 */
    private const val CELL_INSET = 6

    /** 排行行序号徽章（与 App 内报告视图同款）：边长 / 与标签的间距 / 字号 */
    private const val RANK_BOX = 42
    private const val RANK_GAP = 14
    private const val FS_RANK = 24f

    /**
     * KPI 网格内部节奏（单元高度由内部节奏推导，不再写死）：
     *   上内边距 + 标签行 + 标签↔数值间隙 + 数值行 + 下内边距 = KPI_CELL_H
     * 单位过长时（放不进数值行）额外加一行单位：KPI_CELL_H_TALL。
     */
    private const val KPI_PAD_H = 26
    private const val KPI_PAD_V = 26
    private const val KPI_COL_GAP = 20
    private const val KPI_ROW_GAP = 22
    private const val KPI_LABEL_ROW_H = 36
    private const val KPI_LABEL_VALUE_GAP = 10
    private const val KPI_VALUE_ROW_H = 62
    private const val KPI_UNIT_GAP = 4
    private const val KPI_UNIT_ROW_H = 32
    private const val KPI_CELL_H = KPI_PAD_V * 2 + KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP + KPI_VALUE_ROW_H
    private const val KPI_CELL_H_TALL = KPI_CELL_H + KPI_UNIT_GAP + KPI_UNIT_ROW_H

    /** 单位短于等于这个长度才与数值同排；更长则另起一行（绝不因省略号丢信息） */
    private const val KPI_UNIT_INLINE_MAX = 4

    /** 顶部卡片 / 卡片的最大显示行数（超出按测量宽度省略，绝不溢出） */
    private const val HEADER_NAME_MAX_LINES = 3
    private const val HEADER_META_MAX_LINES = 2

    /** 画布最小高度 */
    private const val MIN_H = 700

    /** 画布高度上限（超过直接拒绝导出，避免 OOM） */
    private const val MAX_HEIGHT = 30000

    // ---- 由上面的常量推导：卡片 / 内容区 ----
    private const val CARD_LEFT = CANVAS_PAD                                  // 48
    private const val CARD_RIGHT = W - CANVAS_PAD                             // 1032
    private const val CONTENT_LEFT = CARD_LEFT + CARD_PAD_H                   // 84
    private const val CONTENT_RIGHT = CARD_RIGHT - CARD_PAD_H                 // 996
    private const val CONTENT_W = CONTENT_RIGHT - CONTENT_LEFT                // 912

    // ---- 由上面的常量推导：条形行三列（42% / 22% / 余量 − 2×COL_GAP）----
    private const val LABEL_W = CONTENT_W * 42 / 100                          // 383
    private const val VALUE_W = CONTENT_W * 22 / 100                          // 200
    private const val BAR_W = CONTENT_W - LABEL_W - VALUE_W - COL_GAP * 2     // 289

    private const val LABEL_LEFT = CONTENT_LEFT                               // 84
    private const val LABEL_RIGHT = LABEL_LEFT + LABEL_W                      // 467
    private const val VALUE_LEFT = LABEL_RIGHT + COL_GAP                      // 487
    private const val VALUE_RIGHT = VALUE_LEFT + VALUE_W                      // 687
    private const val BAR_LEFT = VALUE_RIGHT + COL_GAP                        // 707
    private const val BAR_RIGHT = BAR_LEFT + BAR_W                            // 996

    /** KPI 单元宽度（一行两格，两格 + 列间距恰好占满内容区） */
    private const val KPI_CELL_W = (CONTENT_W - KPI_COL_GAP) / 2               // 446

    // ---- 字号（px）：标题 56 / 章节 40 / 正文 30 / 注释 26 ----
    private const val FS_BRAND = 28f
    private const val FS_TITLE = 56f
    private const val FS_META = 28f
    private const val FS_SMALL = 26f
    private const val FS_AVATAR = 48f
    private const val FS_BADGE = 26f
    private const val FS_GROUP = 30f
    private const val FS_SECTION = 40f
    private const val FS_SECTION_NO = 30f
    private const val FS_BODY = 30f
    private const val FS_ROW = 28f
    private const val FS_KPI_LABEL = 26f
    private const val FS_KPI_VALUE = 46f
    private const val FS_KPI_UNIT = 26f

    // ---- 配色（集中常量：主色 / 强调色 / 成功 / 警示 / 文本主次 / 分隔线）----
    private const val COLOR_BG_TOP = 0xFFF1F7FC.toInt()
    private const val COLOR_BG_MID = 0xFFF8FBFE.toInt()
    private const val COLOR_BG_BOTTOM = 0xFFFFFFFF.toInt()
    private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    private const val COLOR_TITLE = 0xFF12233A.toInt()
    private const val COLOR_BODY = 0xFF2B3A4B.toInt()
    private const val COLOR_META = 0xFF7C8CA0.toInt()
    private const val COLOR_ACCENT = 0xFF2E7DD1.toInt()
    private const val COLOR_ACCENT2 = 0xFF12B3A8.toInt()
    private const val COLOR_SUCCESS = 0xFF1F9D55.toInt()
    private const val COLOR_WARN = 0xFFD89A16.toInt()
    private const val COLOR_RULE = 0xFFE1E9F2.toInt()
    private const val COLOR_TRACK = 0xFFE7EEF6.toInt()
    private const val COLOR_SHADOW = 0x14000000
    private const val COLOR_RANK_GOLD = 0xFFD89A16.toInt()
    private const val COLOR_RANK_SILVER = 0xFF7F8C9B.toInt()
    private const val COLOR_RANK_BRONZE = 0xFFB9754A.toInt()

    /** 固定品牌文案（顶部品牌行 / 页脚水印） */
    private const val BRAND_TEXT = "WeKit · 聊天记录分析"
    private const val GROUP_STATS = "本地统计报告"
    private const val GROUP_AI = "AI 洞察报告"
    private const val EMPTY_HINT = "该时段没有可统计的文本消息。"

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
        require(BAR_W > 0 && LABEL_W > 0 && VALUE_W > 0 && KPI_CELL_W > 0) { "PNG 列宽非法" }
        require(2 * KPI_CELL_W + KPI_COL_GAP <= CONTENT_W) { "PNG KPI 网格超宽" }
        require(CANVAS_PAD >= 48) { "PNG 画布左右边距不得小于 48px" }
        require(SECTION_HEADER_H >= SECTION_BADGE_BOX + 40) { "PNG 章节头部高度装不下序号徽章" }
        // KPI 单元：内部排版（标签 + 数值 [+ 单位]）必须装得进单元高度，且加单位那档确实更高
        require(KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP + KPI_VALUE_ROW_H + KPI_PAD_V <= KPI_CELL_H) {
            "PNG KPI 单元内部排版超出单元高度"
        }
        require(KPI_CELL_H_TALL > KPI_CELL_H) { "PNG KPI 带单位行的单元高度非法" }
        // 排行行：圆角序号徽章必须装得进行高，且标签列扣掉徽章后仍有余量
        require(ROW_H >= RANK_BOX + 8) { "PNG 行长装不下排行序号徽章" }
        require(LABEL_W - CELL_INSET * 2 - RANK_BOX - RANK_GAP >= 120) { "PNG 排行标签列可用宽度过窄" }
        require(CARD_MIN_H >= CARD_PAD_V * 2 + ROW_H) { "PNG 卡片最小高度小于内边距 + 一行" }
        // 页脚：上留白 + 文字行 + 间距 + 品牌条 必须装得进页脚高度
        require((FOOTER_TOP_GAP + FOOTER_TEXT_H + FOOTER_STRIP_GAP).toFloat() + FOOTER_STRIP_H <=
            FOOTER_H.toFloat()) { "PNG 页脚内部排版超出页脚高度" }
    }

    // ==================================================================
    // 二、数据模型（解析 → 布局 → 绘制三段式）
    // ==================================================================

    /** 报告文本切分出的最小单元 */
    private sealed class Block {
        data class Section(val title: String) : Block()
        data class BarLine(
            val label: String,
            val value: String,
            val ratio: Float,
            val rank: Int,
        ) : Block()
        data class KeyValue(val key: String, val value: String) : Block()
        data class KpiGrid(val items: List<KeyValue>) : Block()
        data class TextLine(val text: String) : Block()
        object Gap : Block()
    }

    /** 已定位的一行：top / height 都是相对卡片正文区顶部的偏移 */
    private class Row(
        val unit: Block,
        val top: Int,
        val height: Int,
        val lines: List<String> = emptyList(),
    )

    /** 绘制单元：分组 pill 或章节卡片 */
    private sealed class Item {
        data class Pill(val label: String, val accent: Int, val width: Int) : Item()
        data class Card(
            val title: String?,
            val index: Int,
            val accent: Int,
            val rows: List<Row>,
            val height: Int,
        ) : Item()
    }

    /** 顶部信息卡的布局结果 */
    private class HeaderSpec(
        val avatarChar: String,
        val nameLines: List<String>,
        val subLines: List<String>,
        val genLines: List<String>,
        val nameTop: Int,
        val subTop: Int,
        val genTop: Int,
        val avatarTop: Int,
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

    /** 数值 + 单位拆分："12,345 条" → ("12,345", "条")；纯文本则单位为空串 */
    private val VALUE_UNIT = Regex("^([0-9][0-9 .,%:+\\-]*)(.*)$")

    /** KPI 行的判定阈值（与 App 内报告视图的键值行判定保持一致，避免两处观感割裂） */
    private const val KV_MAX_LINE_LEN = 40
    private const val KV_MAX_KEY_LEN = 20
    private const val KV_MAX_VALUE_LEN = 18

    private fun parseBlocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        if (text.isBlank()) return out
        for (line in text.split("\n")) {
            val t = line.trim()
            when {
                t.isEmpty() -> out.add(Block.Gap)
                t.startsWith("【") && t.endsWith("】") ->
                    out.add(Block.Section(t.removeSurrounding("【", "】")))
                t.contains("█") -> out.add(parseBarLine(t))
                t.contains("：") && t.length <= KV_MAX_LINE_LEN -> {
                    val idx = t.indexOf("：")
                    val key = t.substring(0, idx).trim()
                    val value = t.substring(idx + 1).trim()
                    if (key.isNotEmpty() && key.length <= KV_MAX_KEY_LEN &&
                        value.isNotEmpty() && value.length <= KV_MAX_VALUE_LEN
                    ) {
                        out.add(Block.KeyValue(key, value))
                    } else {
                        out.add(Block.TextLine(t))
                    }
                }
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

    /** 把连续的 KeyValue 行聚合成 KPI 网格（≥2 项才聚合，单项按普通键值行绘制） */
    private fun groupKpis(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>(blocks.size)
        var run = ArrayList<Block.KeyValue>()
        fun flush() {
            if (run.size >= 2) out.add(Block.KpiGrid(run.toList())) else out.addAll(run)
            run = ArrayList()
        }
        for (b in blocks) {
            if (b is Block.KeyValue) {
                run.add(b)
            } else {
                flush()
                out.add(b)
            }
        }
        flush()
        return out
    }

    /** 去掉卡片正文首尾的空行，并把连续空行折叠成一行（节奏统一，不留黑洞） */
    private fun trimGaps(units: List<Block>): List<Block> {
        val out = ArrayList<Block>(units.size)
        for (u in units) {
            if (u is Block.Gap) {
                if (out.isEmpty() || out.last() is Block.Gap) continue
                out.add(u)
            } else {
                out.add(u)
            }
        }
        while (out.isNotEmpty() && out.last() is Block.Gap) out.removeAt(out.size - 1)
        return out
    }

    // ==================================================================
    // 五、布局（纯几何，先量后排）
    // ==================================================================

    private fun layoutHeader(
        sessionName: String,
        sessionWxid: String,
        period: String,
        generated: String,
    ): HeaderSpec {
        val titleP = paint(FS_TITLE, COLOR_TITLE, bold = true)
        val metaP = paint(FS_META, COLOR_META)
        val smallP = paint(FS_SMALL, COLOR_META)
        val badgeP = paint(FS_BADGE, Color.WHITE, bold = true)

        // 徽章只显示范围标签（period = "范围 · 会话名"，会话名已在标题里，不重复）
        val badgeText = period.substringBefore(" · ").trim().ifEmpty { period.trim() }
        val badgeW = if (badgeText.isEmpty()) {
            0
        } else {
            (badgeP.measureText(badgeText).toInt() + BADGE_PAD_H * 2).coerceAtMost(BADGE_MAX_W)
        }

        val textLeft = CONTENT_LEFT + AVATAR_SIZE + AVATAR_TEXT_GAP
        val colW = (CONTENT_RIGHT - textLeft).toFloat().coerceAtLeast(160f)
        val nameLines = wrapLinesLimited(
            sessionName.trim().ifEmpty { "聊天记录分析" }, colW, titleP, HEADER_NAME_MAX_LINES,
        )
        val subLines = wrapLinesLimited(
            sessionWxid.trim().ifEmpty { "（未知会话）" }, colW, metaP, HEADER_META_MAX_LINES,
        )
        val genLines = wrapLinesLimited(generated, colW, smallP, HEADER_META_MAX_LINES)

        val nameTop = BRAND_LINE_H + HEADER_BRAND_TITLE_GAP
        val nameH = nameLines.size.coerceAtLeast(1) * HEADER_TITLE_LINE_H
        val subTop = nameTop + nameH + HEADER_TITLE_SUB_GAP
        val subH = subLines.size.coerceAtLeast(1) * HEADER_META_LINE_H
        val genTop = subTop + subH + HEADER_SUB_GEN_GAP
        val genH = genLines.size.coerceAtLeast(1) * HEADER_META_LINE_H

        val avatarTop = nameTop
        val contentH = maxOf(genTop + genH, avatarTop + AVATAR_SIZE)

        return HeaderSpec(
            avatarChar = firstGlyph(sessionName),
            nameLines = nameLines,
            subLines = subLines,
            genLines = genLines,
            nameTop = nameTop,
            subTop = subTop,
            genTop = genTop,
            avatarTop = avatarTop,
            textLeft = textLeft,
            textRight = CONTENT_RIGHT,
            badgeText = badgeText,
            badgeW = badgeW,
            height = CARD_PAD_V * 2 + contentH,
        )
    }

    private fun layoutRows(units: List<Block>, bodyP: Paint): List<Row> {
        val rows = ArrayList<Row>(units.size)
        var y = 0
        for (u in units) {
            when (u) {
                is Block.Gap -> {
                    rows.add(Row(u, y, GAP_H))
                    y += GAP_H
                }
                // 章节标题由卡片头部承担，正文里不会再出现 Section
                is Block.Section -> Unit
                is Block.KpiGrid -> {
                    // 高度必须与 drawKpiGrid 的分行方式完全一致（含"单位另起一行"那档）
                    val h = kpiGridHeight(u.items)
                    rows.add(Row(u, y, h))
                    y += h
                }
                is Block.KeyValue -> {
                    rows.add(Row(u, y, ROW_H))
                    y += ROW_H
                }
                is Block.BarLine -> {
                    rows.add(Row(u, y, ROW_H))
                    y += ROW_H
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

    /** 把 block 流按 Section 切成章节卡片（每张卡片自己算高度），返回卡片列表 */
    private fun buildCards(
        units: List<Block>,
        accent: Int,
        startNo: Int,
        bodyP: Paint,
    ): List<Item.Card> {
        val cards = ArrayList<Item.Card>()
        var no = startNo
        var title: String? = null
        var bucket = ArrayList<Block>()

        fun flush() {
            val body = trimGaps(bucket)
            bucket = ArrayList()
            if (title == null && body.isEmpty()) return
            val rows = layoutRows(body, bodyP)
            val headerH = if (title != null) SECTION_HEADER_H else 0
            val lastBottom = rows.lastOrNull()?.let { it.top + it.height } ?: 0
            val height = maxOf(CARD_MIN_H, CARD_PAD_V * 2 + headerH + lastBottom)
            val index = if (title != null) ++no else 0
            cards.add(Item.Card(title, index, accent, rows, height))
        }

        for (u in units) {
            if (u is Block.Section) {
                flush()
                title = u.title
            } else {
                bucket.add(u)
            }
        }
        flush()
        return cards
    }

    private fun buildItems(stats: String, ai: String, bodyP: Paint): List<Item> {
        val items = ArrayList<Item>()
        var nextNo = 1
        val statsCards = buildCards(groupKpis(parseBlocks(stats)), COLOR_ACCENT, nextNo, bodyP)
        nextNo += statsCards.count { it.title != null }
        val aiCards = buildCards(groupKpis(parseBlocks(ai)), COLOR_ACCENT2, nextNo, bodyP)

        if (statsCards.isNotEmpty()) {
            items.add(Item.Pill(GROUP_STATS, COLOR_ACCENT, pillWidth(GROUP_STATS, COLOR_ACCENT)))
            items.addAll(statsCards)
        }
        if (aiCards.isNotEmpty()) {
            items.add(Item.Pill(GROUP_AI, COLOR_ACCENT2, pillWidth(GROUP_AI, COLOR_ACCENT2)))
            items.addAll(aiCards)
        }
        if (items.isEmpty()) {
            // 空报告：也给一张卡片说明，避免只剩一个光秃秃的标题卡
            val cards = buildCards(listOf(Block.TextLine(EMPTY_HINT)), COLOR_WARN, 0, bodyP)
            items.addAll(cards)
        }
        return items
    }

    private fun pillWidth(label: String, accent: Int): Int {
        val p = paint(FS_GROUP, accent, bold = true)
        val available = (GROUP_PILL_MAX_W - GROUP_PILL_PAD_L - GROUP_PILL_DOT - GROUP_PILL_DOT_GAP - GROUP_PILL_PAD_R).toFloat()
        val text = truncateToWidth(label, available, p)
        return (GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP +
            p.measureText(text).toInt() + GROUP_PILL_PAD_R).coerceAtMost(GROUP_PILL_MAX_W)
    }

    private fun itemHeight(item: Item): Int = when (item) {
        is Item.Pill -> GROUP_PILL_H
        is Item.Card -> item.height
    }

    /** 绘制前的间距节奏：首块紧接顶部卡；分组 pill 前留大间距、后留小间距 */
    private fun gapBefore(items: List<Item>, index: Int): Int {
        val item = items[index]
        return when {
            index == 0 -> CARD_GAP
            item is Item.Pill -> GROUP_PILL_GAP
            items[index - 1] is Item.Pill -> GROUP_PILL_CARD_GAP
            else -> CARD_GAP
        }
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
        val header = layoutHeader(sessionName, sessionWxid, period, generated)
        val items = buildItems(stats, ai, bodyP)

        var total = CANVAS_PAD + header.height
        for (i in items.indices) total += gapBefore(items, i) + itemHeight(items[i])
        total += CARD_GAP + FOOTER_H + BOTTOM_PAD
        if (total < MIN_H) total = MIN_H
        if (total > MAX_HEIGHT) throw Exception("报告过长，请缩小分析范围后再导出")

        // 单张 Bitmap（即画布本身），不产生任何全图拷贝
        val bmp = Bitmap.createBitmap(W, total, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // 背景：极浅的竖向渐变（上浅蓝 → 下纯白），给白卡片留出层次
        cv.drawRect(0f, 0f, W.toFloat(), total.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, total.toFloat(),
                intArrayOf(COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_BOTTOM),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
        })

        // ---- 第二遍：按第一遍算出的同一份几何绘制 ----
        var y = CANVAS_PAD
        drawHeaderCard(cv, y, header)
        y += header.height

        for (i in items.indices) {
            y += gapBefore(items, i)
            when (val item = items[i]) {
                is Item.Pill -> {
                    drawGroupPill(cv, item, y.toFloat())
                    y += GROUP_PILL_H
                }
                is Item.Card -> {
                    drawCardGroup(cv, item, y, bodyP)
                    y += item.height
                }
            }
        }

        y += CARD_GAP
        drawFooter(cv, y)

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

    /** 统一的卡片外观：完全相同的左右边界、圆角、细描边、轻阴影、左侧渐变强调条 */
    private fun drawCard(cv: Canvas, top: Int, bottom: Int, accent: Int) {
        val rect = RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), bottom.toFloat())
        val cardP = Paint().apply {
            isAntiAlias = true
            color = COLOR_CARD
            style = Paint.Style.FILL
            setShadowLayer(18f, 0f, 6f, COLOR_SHADOW)
        }
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, cardP)
        cardP.clearShadowLayer()

        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint().apply {
            isAntiAlias = true
            color = COLOR_RULE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })

        // 左侧强调条：竖向渐变（实色 → 提亮），比纯色更有质感；clip 到卡片圆角内
        val barTop = top.toFloat() + CARD_RADIUS * 0.5f
        val barBottom = bottom.toFloat() - CARD_RADIUS * 0.5f
        if (barBottom > barTop) {
            cv.save()
            cv.clipRect(
                CARD_LEFT.toFloat(), top.toFloat(),
                CARD_LEFT + CARD_RADIUS, bottom.toFloat(),
            )
            cv.drawRoundRect(
                RectF(
                    CARD_LEFT.toFloat(), barTop,
                    (CARD_LEFT + CARD_ACCENT_W).toFloat(), barBottom,
                ),
                4f, 4f,
                Paint().apply {
                    isAntiAlias = true
                    shader = LinearGradient(
                        0f, barTop, 0f, barBottom,
                        intArrayOf(accent, lighten(accent)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
            cv.restore()
        }
    }

    private fun drawHeaderCard(cv: Canvas, top: Int, spec: HeaderSpec) {
        val bottom = top + spec.height
        drawCard(cv, top, bottom, COLOR_ACCENT)
        // 整卡硬裁剪：任何内容都不可能画到卡片之外
        cv.save()
        cv.clipRect(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), bottom.toFloat())

        val inner = (top + CARD_PAD_V).toFloat()
        val innerBottom = (bottom - CARD_PAD_V).toFloat()

        // ---- 品牌行：左侧品牌块，右侧范围徽章（互不挤占）----
        val brandP = paint(FS_BRAND, COLOR_ACCENT, bold = true)
        val brandTop = inner
        val brandRight = if (spec.badgeW > 0) {
            (CONTENT_RIGHT - spec.badgeW - COL_GAP).toFloat()
        } else {
            CONTENT_RIGHT.toFloat()
        }
        if (brandRight > CONTENT_LEFT + 120f) {
            val markTop = brandTop + (BRAND_LINE_H - BRAND_BOX) / 2f
            cv.drawRoundRect(
                RectF(
                    CONTENT_LEFT.toFloat(), markTop,
                    (CONTENT_LEFT + BRAND_BOX).toFloat(), markTop + BRAND_BOX,
                ),
                5f, 5f,
                Paint().apply { isAntiAlias = true; color = COLOR_ACCENT2 },
            )
            val brandClip = RectF(
                (CONTENT_LEFT + BRAND_BOX + 14).toFloat(), brandTop,
                brandRight, brandTop + BRAND_LINE_H,
            )
            val brand = truncateToWidth(BRAND_TEXT, brandClip.width(), brandP)
            drawClipped(
                cv, brand, brandClip.left,
                fitBaseline(brandTop, BRAND_LINE_H.toFloat(), brandP, brandClip.bottom),
                brandClip, brandP,
            )
        }

        // ---- 右上角范围徽章 ----
        if (spec.badgeW > 0) {
            val bTop = brandTop + (BRAND_LINE_H - BADGE_H) / 2f
            val bRect = RectF(
                (CONTENT_RIGHT - spec.badgeW).toFloat(), bTop,
                CONTENT_RIGHT.toFloat(), bTop + BADGE_H,
            )
            cv.drawRoundRect(bRect, BADGE_H / 2f, BADGE_H / 2f, Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    bRect.left, 0f, bRect.right, 0f,
                    intArrayOf(COLOR_ACCENT, COLOR_ACCENT2),
                    null, Shader.TileMode.CLAMP,
                )
            })
            val btP = paint(FS_BADGE, Color.WHITE, bold = true)
            val bt = truncateToWidth(spec.badgeText, (spec.badgeW - BADGE_PAD_H * 2).toFloat(), btP)
            drawClipped(
                cv, bt,
                bRect.centerX() - btP.measureText(bt) / 2f,
                fitBaseline(bRect.top, BADGE_H.toFloat(), btP, bRect.bottom),
                bRect, btP,
            )
        }

        // ---- 头像（渐变圆角方块 + 首字）----
        val avRect = RectF(
            CONTENT_LEFT.toFloat(), inner + spec.avatarTop,
            (CONTENT_LEFT + AVATAR_SIZE).toFloat(), inner + spec.avatarTop + AVATAR_SIZE,
        )
        cv.drawRoundRect(avRect, 26f, 26f, Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                avRect.left, avRect.top, avRect.right, avRect.bottom,
                intArrayOf(COLOR_ACCENT, COLOR_ACCENT2),
                null, Shader.TileMode.CLAMP,
            )
        })
        val avP = paint(FS_AVATAR, Color.WHITE, bold = true)
        val avW = avP.measureText(spec.avatarChar)
        drawClipped(
            cv, spec.avatarChar,
            avRect.centerX() - avW / 2f,
            fitBaseline(avRect.top, AVATAR_SIZE.toFloat(), avP, avRect.bottom),
            avRect, avP,
        )

        // ---- 三行文字：标题 / 副标题 / 生成时间，各自矩形内硬裁剪 + 底边硬约束 ----
        val titleP = paint(FS_TITLE, COLOR_TITLE, bold = true)
        val metaP = paint(FS_META, COLOR_META)
        val smallP = paint(FS_SMALL, COLOR_META)
        drawTextLines(
            cv, spec.nameLines, spec.textLeft.toFloat(), inner + spec.nameTop,
            HEADER_TITLE_LINE_H, titleP, spec.textRight.toFloat(), innerBottom,
        )
        drawTextLines(
            cv, spec.subLines, spec.textLeft.toFloat(), inner + spec.subTop,
            HEADER_META_LINE_H, metaP, spec.textRight.toFloat(), innerBottom,
        )
        drawTextLines(
            cv, spec.genLines, spec.textLeft.toFloat(), inner + spec.genTop,
            HEADER_META_LINE_H, smallP, spec.textRight.toFloat(), innerBottom,
        )

        cv.restore()
    }

    private fun drawCardGroup(cv: Canvas, card: Item.Card, top: Int, bodyP: Paint) {
        drawCard(cv, top, top + card.height, card.accent)
        cv.save()
        cv.clipRect(
            CARD_LEFT.toFloat(), top.toFloat(),
            CARD_RIGHT.toFloat(), (top + card.height).toFloat(),
        )

        // 章节标题：先落成局部变量再判空，避免任何智能转换歧义
        val title = card.title
        if (title != null) drawCardHeader(cv, top, title, card.index, card.accent)
        val bodyTop = top + CARD_PAD_V + (if (title != null) SECTION_HEADER_H else 0)
        val innerBottom = (top + card.height - CARD_PAD_V).toFloat()
        for (row in card.rows) {
            val rowTop = (bodyTop + row.top).toFloat()
            when (val u = row.unit) {
                is Block.KpiGrid -> drawKpiGrid(cv, u.items, rowTop, card.accent, innerBottom)
                is Block.KeyValue -> drawKeyValueRow(cv, u, rowTop, row.height.toFloat(), innerBottom)
                is Block.BarLine -> drawBarRow(cv, u, rowTop, row.height.toFloat(), innerBottom, card.accent)
                is Block.TextLine -> drawTextLines(
                    cv, row.lines, CONTENT_LEFT.toFloat(), rowTop,
                    TEXT_LINE_H, bodyP, CONTENT_RIGHT.toFloat(), innerBottom,
                )
                is Block.Section -> Unit
                is Block.Gap -> Unit
            }
        }

        cv.restore()
    }

    /** 章节卡片头部：序号徽章 + 标题（标题 40px 加粗）+ 渐变下划线 */
    private fun drawCardHeader(cv: Canvas, cardTop: Int, title: String, index: Int, accent: Int) {
        val headerTop = (cardTop + CARD_PAD_V).toFloat()

        val badge = RectF(
            CONTENT_LEFT.toFloat(), headerTop + 4f,
            (CONTENT_LEFT + SECTION_BADGE_BOX).toFloat(), headerTop + 4f + SECTION_BADGE_BOX,
        )
        cv.drawRoundRect(badge, 16f, 16f, Paint().apply {
            isAntiAlias = true
            color = withAlpha(accent, 0x1F)
        })
        cv.drawRoundRect(badge, 16f, 16f, Paint().apply {
            isAntiAlias = true
            color = withAlpha(accent, 0x3D)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })
        if (index > 0) {
            val noP = paint(FS_SECTION_NO, accent, bold = true)
            val noText = index.toString()
            drawClipped(
                cv, noText,
                badge.centerX() - noP.measureText(noText) / 2f,
                fitBaseline(badge.top, SECTION_BADGE_BOX.toFloat(), noP, badge.bottom),
                badge, noP,
            )
        }

        val titleP = paint(FS_SECTION, COLOR_TITLE, bold = true)
        val titleLeft = CONTENT_LEFT + SECTION_BADGE_BOX + 22
        val titleRowH = SECTION_BADGE_BOX.toFloat() + 10f
        val titleClip = RectF(
            titleLeft.toFloat(), headerTop,
            CONTENT_RIGHT.toFloat(), headerTop + titleRowH,
        )
        val text = truncateToWidth(title, titleClip.width() - CELL_INSET, titleP)
        drawClipped(
            cv, text, (titleLeft + CELL_INSET).toFloat(),
            fitBaseline(headerTop, titleRowH, titleP, titleClip.bottom),
            titleClip, titleP,
        )

        val ulTop = headerTop + SECTION_BADGE_BOX + 24f
        cv.drawRoundRect(
            RectF(
                CONTENT_LEFT.toFloat(), ulTop,
                (CONTENT_LEFT + SECTION_UNDERLINE_W).toFloat(), ulTop + SECTION_UNDERLINE_H,
            ),
            SECTION_UNDERLINE_H / 2f, SECTION_UNDERLINE_H / 2f,
            Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    CONTENT_LEFT.toFloat(), 0f,
                    (CONTENT_LEFT + SECTION_UNDERLINE_W).toFloat(), 0f,
                    intArrayOf(accent, withAlpha(accent, 0x00)),
                    null, Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /** 分组 pill：左对齐、与卡片同左边界的圆角标签（本地统计报告 / AI 洞察报告） */
    private fun drawGroupPill(cv: Canvas, pill: Item.Pill, top: Float) {
        val rect = RectF(
            CARD_LEFT.toFloat(), top,
            (CARD_LEFT + pill.width).toFloat(), top + GROUP_PILL_H,
        )
        val radius = GROUP_PILL_H / 2f
        cv.drawRoundRect(rect, radius, radius, Paint().apply {
            isAntiAlias = true
            color = withAlpha(pill.accent, 0x16)
        })
        cv.drawRoundRect(rect, radius, radius, Paint().apply {
            isAntiAlias = true
            color = withAlpha(pill.accent, 0x3A)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })

        val dotLeft = (CARD_LEFT + GROUP_PILL_PAD_L).toFloat()
        val dotTop = top + (GROUP_PILL_H - GROUP_PILL_DOT) / 2f
        cv.drawOval(
            RectF(dotLeft, dotTop, dotLeft + GROUP_PILL_DOT, dotTop + GROUP_PILL_DOT),
            Paint().apply { isAntiAlias = true; color = pill.accent },
        )

        val textLeft = CARD_LEFT + GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP
        val p = paint(FS_GROUP, pill.accent, bold = true)
        val clip = RectF(
            textLeft.toFloat(), top,
            rect.right - GROUP_PILL_PAD_R + CELL_INSET, top + GROUP_PILL_H,
        )
        val text = truncateToWidth(pill.label, clip.width(), p)
        drawClipped(
            cv, text, textLeft.toFloat(),
            fitBaseline(top, GROUP_PILL_H.toFloat(), p, clip.bottom),
            clip, p,
        )
    }

    // ---- KPI 网格：两遍布局共用的分行/高度规则（布局与绘制必须用同一套，否则必然串位）----

    /** 该单元是否需要把单位另起一行（单位太长时同排会被省略号吃掉信息） */
    private fun needsUnitRow(kv: Block.KeyValue): Boolean =
        splitValueUnit(kv.value).second.length > KPI_UNIT_INLINE_MAX

    /** 一行（最多两张单元）的高度：任一张需要单位行就整行加高，保证同一行底色等高 */
    private fun kpiRowCellH(row: List<Block.KeyValue>): Int =
        if (row.any { needsUnitRow(it) }) KPI_CELL_H_TALL else KPI_CELL_H

    /** KPI 网格整体高度：与 [drawKpiGrid] 的排布完全一致 */
    private fun kpiGridHeight(items: List<Block.KeyValue>): Int {
        val rows = items.chunked(2)
        return rows.sumOf { kpiRowCellH(it) } + (rows.size - 1).coerceAtLeast(0) * KPI_ROW_GAP
    }

    /** KPI 网格：每行两张浅色底卡片，标签小、数字大（强调色）、单位轻 */
    private fun drawKpiGrid(
        cv: Canvas,
        items: List<Block.KeyValue>,
        rowTop: Float,
        accent: Int,
        limitBottom: Float,
    ) {
        var cellTop = rowTop
        items.chunked(2).forEach { pair ->
            val cellH = kpiRowCellH(pair)
            pair.forEachIndexed { c, kv ->
                drawKpiCell(
                    cv, kv,
                    CONTENT_LEFT + c * (KPI_CELL_W + KPI_COL_GAP),
                    cellTop, cellH, accent, limitBottom,
                )
            }
            cellTop += cellH + KPI_ROW_GAP
        }
    }

    private fun drawKpiCell(
        cv: Canvas,
        kv: Block.KeyValue,
        left: Int,
        top: Float,
        cellH: Int,
        accent: Int,
        limitBottom: Float,
    ) {
        val rect = RectF(left.toFloat(), top, (left + KPI_CELL_W).toFloat(), top + cellH)
        cv.drawRoundRect(rect, 20f, 20f, shapePaint(withAlpha(accent, 0x12)))
        cv.drawRoundRect(rect, 20f, 20f, shapePaint(withAlpha(accent, 0x33), stroke = true, strokeWidth = 2f))

        val innerLeft = (left + KPI_PAD_H).toFloat()
        val innerRight = (left + KPI_CELL_W - KPI_PAD_H).toFloat()

        // 标签（小字、次要色）
        val labelP = paint(FS_KPI_LABEL, COLOR_META)
        val labelTop = top + KPI_PAD_V
        val labelClip = RectF(innerLeft, labelTop, innerRight, labelTop + KPI_LABEL_ROW_H)
        val labelText = truncateToWidth(kv.key, labelClip.width(), labelP)
        drawClipped(
            cv, labelText, innerLeft,
            fitBaseline(labelTop, KPI_LABEL_ROW_H.toFloat(), labelP, minOf(labelClip.bottom, limitBottom)),
            labelClip, labelP,
        )

        // 数值（大数字 + 轻单位）。单位短就同排；长则另起一行，绝不因宽度不足丢字。
        val valueTop = labelTop + KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP
        val valueClip = RectF(innerLeft, valueTop, innerRight, valueTop + KPI_VALUE_ROW_H)
        val bottomLimit = minOf(valueClip.bottom, limitBottom)
        val numP = paint(FS_KPI_VALUE, accent, bold = true)
        val unitP = paint(FS_KPI_UNIT, COLOR_META)
        val (num, unit) = splitValueUnit(kv.value)
        val inlineUnit = unit.isNotEmpty() && unit.length <= KPI_UNIT_INLINE_MAX
        val unitW = if (inlineUnit) unitP.measureText(unit) + 10f else 0f
        val numText = truncateToWidth(num, (valueClip.width() - unitW).coerceAtLeast(0f), numP)
        drawClipped(
            cv, numText, innerLeft,
            fitBaseline(valueTop, KPI_VALUE_ROW_H.toFloat(), numP, bottomLimit),
            valueClip, numP,
        )
        if (inlineUnit) {
            val ux = innerLeft + numP.measureText(numText) + 10f
            val unitClip = RectF(ux, valueTop, innerRight, valueClip.bottom)
            if (unitClip.width() > 0f) {
                drawClipped(
                    cv, unit, ux,
                    fitBaseline(valueTop, KPI_VALUE_ROW_H.toFloat(), unitP, bottomLimit),
                    unitClip, unitP,
                )
            }
        } else if (unit.isNotEmpty()) {
            // 单位行：整行给单位，最多一行（超长自动省略），永远落在单元内
            val unitTop = valueTop + KPI_VALUE_ROW_H + KPI_UNIT_GAP
            val unitClip = RectF(innerLeft, unitTop, innerRight, unitTop + KPI_UNIT_ROW_H)
            val unitBottom = minOf(unitClip.bottom, limitBottom)
            if (unitClip.width() > 0f && unitBottom > unitTop) {
                val unitText = truncateToWidth(unit, unitClip.width(), unitP)
                drawClipped(
                    cv, unitText, innerLeft,
                    fitBaseline(unitTop, KPI_UNIT_ROW_H.toFloat(), unitP, unitBottom),
                    unitClip, unitP,
                )
            }
        }
    }

    /**
     * 条形行：严格三列。
     * 1) 先铺满整列淡轨道（给所有条形一个 100% 参照，图表留白不贴边）
     * 2) 再画比例填充（只在 bar 列内，长度按比例、最小 BAR_MIN_W、最大不超过列宽）
     * 3) 再画标签（label 列内；排行行先画序号徽章，昵称去掉重复的 "1." 前缀）
     * 4) 再画数值（value 列内右对齐）
     */
    private fun drawBarRow(
        cv: Canvas,
        u: Block.BarLine,
        rowTop: Float,
        rowH: Float,
        limitBottom: Float,
        accent: Int,
    ) {
        val rowBottom = rowTop + rowH
        val labelP = paint(FS_ROW, COLOR_BODY)
        val valueP = paint(FS_ROW, valueColor(u.rank), bold = true)
        val baseline = fitBaseline(rowTop, rowH, labelP, minOf(rowBottom, limitBottom))
        val barTop = rowTop + (rowH - BAR_TRACK_H) / 2f

        // ---- 1) bar 列：整列淡轨道 + 渐变填充 ----
        cv.drawRoundRect(
            RectF(BAR_LEFT.toFloat(), barTop, BAR_RIGHT.toFloat(), barTop + BAR_TRACK_H),
            BAR_TRACK_H / 2f, BAR_TRACK_H / 2f,
            shapePaint(COLOR_TRACK),
        )
        if (u.ratio > 0f) {
            val fillW = (BAR_W * u.ratio).coerceIn(BAR_MIN_W, BAR_W.toFloat())
            val fill = RectF(BAR_LEFT.toFloat(), barTop, BAR_LEFT + fillW, barTop + BAR_TRACK_H)
            drawClippedRect(
                cv, fill, BAR_LEFT.toFloat(), BAR_RIGHT.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // 条形颜色跟随所属卡片的主色（统计卡=蓝、AI 卡=青），并用渐变提亮右端
                    shader = LinearGradient(
                        BAR_LEFT.toFloat(), 0f, BAR_RIGHT.toFloat(), 0f,
                        intArrayOf(accent, lighten(accent)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
        }

        // ---- 2) label 列：排行行先画圆角序号徽章（金银铜），并去掉文本里重复的序号 ----
        var labelLeft = LABEL_LEFT + CELL_INSET
        if (u.rank > 0) {
            drawRankBadge(cv, u.rank, labelLeft, rowTop + rowH / 2f, accent)
            labelLeft += RANK_BOX + RANK_GAP
        }
        val labelClip = RectF(LABEL_LEFT.toFloat(), rowTop, LABEL_RIGHT.toFloat(), rowBottom)
        val pureLabel = if (u.rank > 0) u.label.replaceFirst(RANK_PREFIX, "").trim() else u.label
        val labelAvailW = (LABEL_RIGHT - CELL_INSET - labelLeft).toFloat()
        if (labelAvailW > 0f) {
            val labelText = truncateToWidth(pureLabel, labelAvailW, labelP)
            drawClipped(cv, labelText, labelLeft.toFloat(), baseline, labelClip, labelP)
        }

        // ---- 3) value 列（右对齐到列右边界）----
        if (u.value.isNotEmpty()) {
            val valueClip = RectF(VALUE_LEFT.toFloat(), rowTop, VALUE_RIGHT.toFloat(), rowBottom)
            val vx = (VALUE_RIGHT - CELL_INSET).toFloat() - valueP.measureText(u.value)
            drawClipped(cv, u.value, vx, baseline, valueClip, valueP)
        }
    }

    /**
     * 排行序号徽章：Top1-3 用金银铜实底 + 白字，其余用强调色浅底 + 强调色字
     * （与 App 内报告视图的 RankBadge 观感一致）。徽章只在自己的矩形内绘制。
     */
    private fun drawRankBadge(cv: Canvas, rank: Int, left: Int, centerY: Float, accent: Int) {
        val box = RANK_BOX.toFloat()
        val top = centerY - box / 2f
        val rect = RectF(left.toFloat(), top, left + box, top + box)
        val medal = rank in 1..3
        cv.drawRoundRect(
            rect, box / 2.6f, box / 2.6f,
            shapePaint(if (medal) valueColor(rank) else withAlpha(accent, 0x1C)),
        )
        val p = paint(FS_RANK, if (medal) Color.WHITE else accent, bold = true)
        val s = rank.toString()
        val tx = rect.centerX() - p.measureText(s) / 2f
        drawClipped(
            cv, s, tx,
            fitBaseline(top, box, p, minOf(rect.bottom, top + box)),
            rect, p,
        )
    }

    /** 单行键值（未聚合成 KPI 网格时）：key 左、value 右，两边都有限宽 */
    private fun drawKeyValueRow(
        cv: Canvas,
        u: Block.KeyValue,
        rowTop: Float,
        rowH: Float,
        limitBottom: Float,
    ) {
        val keyP = paint(FS_ROW, COLOR_META)
        val valueP = paint(FS_ROW, COLOR_TITLE, bold = true)
        val baseline = fitBaseline(rowTop, rowH, keyP, minOf(rowTop + rowH, limitBottom))
        val half = (CONTENT_W - COL_GAP) / 2

        val keyClip = RectF(CONTENT_LEFT.toFloat(), rowTop, (CONTENT_LEFT + half).toFloat(), rowTop + rowH)
        val keyText = truncateToWidth(u.key, keyClip.width() - CELL_INSET * 2f, keyP)
        drawClipped(cv, keyText, (CONTENT_LEFT + CELL_INSET).toFloat(), baseline, keyClip, keyP)

        val valueClip = RectF(
            (CONTENT_LEFT + half + COL_GAP).toFloat(), rowTop,
            CONTENT_RIGHT.toFloat(), rowTop + rowH,
        )
        val valueText = truncateToWidth(u.value, valueClip.width() - CELL_INSET * 2f, valueP)
        val vx = valueClip.right - CELL_INSET - valueP.measureText(valueText)
        drawClipped(cv, valueText, vx, baseline, valueClip, valueP)
    }

    /** 页脚：顶部渐变细线 + 左品牌 + 右页码，底部再加一条品牌渐变条当水印 */
    private fun drawFooter(cv: Canvas, top: Int) {
        cv.drawRect(
            RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), top + 3f),
            Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                    intArrayOf(withAlpha(COLOR_ACCENT, 0x00), withAlpha(COLOR_ACCENT, 0x4D), withAlpha(COLOR_ACCENT2, 0x00)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )

        // 页脚内部节奏全部来自常量：上留白 → 文字行 → 间距 → 品牌条，
        // 文字行与品牌条严格分离（避免文字下缘与渐变条压在一起）
        val textTop = (top + FOOTER_TOP_GAP).toFloat()
        val textH = FOOTER_TEXT_H.toFloat()
        val limitBottom = textTop + textH
        val pageP = paint(FS_SMALL, COLOR_ACCENT, bold = true)
        val pageText = "第 1 / 1 页"
        val pageW = pageP.measureText(pageText)
        val pageClip = RectF(
            CARD_RIGHT - pageW - 8f, textTop,
            CARD_RIGHT.toFloat(), textTop + textH,
        )
        drawClipped(
            cv, pageText, pageClip.left,
            fitBaseline(textTop, textH, pageP, minOf(pageClip.bottom, limitBottom)),
            pageClip, pageP,
        )

        val brandP = paint(FS_SMALL, COLOR_META)
        val brandClip = RectF(
            CARD_LEFT.toFloat(), textTop,
            maxOf(CARD_LEFT.toFloat() + 80f, pageClip.left - COL_GAP), textTop + textH,
        )
        val brandText = truncateToWidth("$BRAND_TEXT · 由 WeKit 本地生成", brandClip.width(), brandP)
        drawClipped(
            cv, brandText, brandClip.left,
            fitBaseline(textTop, textH, brandP, minOf(brandClip.bottom, limitBottom)),
            brandClip, brandP,
        )

        // 底部品牌渐变条（水印感，同时收住整张图的下边缘）
        val stripTop = textTop + textH + FOOTER_STRIP_GAP
        cv.drawRoundRect(
            RectF(
                CARD_LEFT.toFloat(), stripTop,
                CARD_RIGHT.toFloat(), stripTop + FOOTER_STRIP_H,
            ),
            FOOTER_STRIP_H / 2f, FOOTER_STRIP_H / 2f,
            Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                    intArrayOf(COLOR_ACCENT, COLOR_ACCENT2, withAlpha(COLOR_SUCCESS, 0xB3)),
                    null, Shader.TileMode.CLAMP,
                )
            },
        )
    }

    // ==================================================================
    // 七、绘制工具（文本永远不越界）
    // ==================================================================

    /**
     * 画笔缓存（文本用）。
     *
     * 一份报告可能有上百个单元格 / 行，逐个 new Paint 会产生大量临时对象；
     * 宿主堆只有 512MB，导出时还要一次性分配整张位图，所以这里按
     * (字号, 颜色, 是否加粗) 复用同一个 Paint —— 返回的实例只读，调用方不得修改。
     * 用 ConcurrentHashMap 是因为导出可能发生在后台线程。
     */
    private class PaintKey(val size: Float, val color: Int, val bold: Boolean)

    private val textPaints = ConcurrentHashMap<PaintKey, Paint>()

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
        textPaints.getOrPut(PaintKey(size, color, bold)) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                if (bold) typeface = Typeface.DEFAULT_BOLD
                isSubpixelText = true
            }
        }

    /** 纯色 / 描边形状画笔缓存（不含 shader 的形状才可共享） */
    private class ShapeKey(val color: Int, val stroke: Boolean, val strokeWidth: Float)

    private val shapePaints = ConcurrentHashMap<ShapeKey, Paint>()

    private fun shapePaint(color: Int, stroke: Boolean = false, strokeWidth: Float = 0f): Paint =
        shapePaints.getOrPut(ShapeKey(color, stroke, strokeWidth)) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                if (stroke) {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
            }
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
        if (clipRight <= clipLeft) return
        cv.save()
        cv.clipRect(clipLeft, rect.top, clipRight, rect.bottom)
        cv.drawRoundRect(rect, BAR_TRACK_H / 2f, BAR_TRACK_H / 2f, p)
        cv.restore()
    }

    /**
     * 二分求「从 start 起能塞进 maxW 的最长前缀末尾」（依赖 measureText 对 end 单调不减）。
     *
     * 原来是逐个字符回退：每个长段落 O(n) 次 measureText、每次 measureText 又是 O(n)，
     * AI 长段落会把导出明显拖慢。二分后是 O(log n)，结果与回退法完全一致
     * （都是「最长的仍然放得下的前缀」）。
     */
    private fun maxPrefixEnd(text: String, start: Int, maxW: Float, p: Paint): Int {
        var lo = start
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(text, start, mid) <= maxW) lo = mid else hi = mid - 1
        }
        return lo
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
                // 二分出本行能放下的最长前缀（原来是逐字回退，长段落会变慢）
                var end = maxPrefixEnd(para, start, maxW, p)
                if (end == start) end = start + 1
                // 不要把代理对（emoji 等）截成半截字符
                if (end < para.length && Character.isLowSurrogate(para[end])) end++
                out.add(para.substring(start, end))
                start = end
            }
        }
        return out
    }

    /** 限定行数的换行：超出部分并入最后一行并按测量宽度省略 */
    private fun wrapLinesLimited(text: String, maxW: Float, p: Paint, maxLines: Int): List<String> {
        val all = wrapLines(text, maxW, p)
        if (maxLines <= 0 || all.size <= maxLines) return all
        val head = all.take(maxLines).toMutableList()
        val rest = all.drop(maxLines).joinToString("")
        head[maxLines - 1] = truncateToWidth(head[maxLines - 1] + rest, maxW, p)
        return head
    }

    /** 超宽则按 Paint.measureText 精确截断并补 "…"（二分定位，不猜宽度、不越出容器） */
    private fun truncateToWidth(text: String, maxW: Float, p: Paint): String {
        if (text.isEmpty() || maxW <= 0f) return if (maxW <= 0f) "" else text
        if (p.measureText(text) <= maxW) return text
        val ellW = p.measureText("…")
        var end = maxPrefixEnd(text, 0, maxW - ellW, p)
        // 不要把代理对（emoji 等）截成半截字符
        if (end in 1 until text.length &&
            Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])
        ) {
            end--
        }
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    /** 数值 / 单位拆分："12,345 条" → ("12,345", "条")；非数字开头则整串当数值 */
    private fun splitValueUnit(value: String): Pair<String, String> {
        val t = value.trim()
        if (t.isEmpty()) return "" to ""
        val m = VALUE_UNIT.find(t) ?: return t to ""
        val num = m.groupValues[1].trim()
        val unit = m.groupValues[2].trim()
        if (num.isEmpty()) return t to ""
        return num to unit
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
