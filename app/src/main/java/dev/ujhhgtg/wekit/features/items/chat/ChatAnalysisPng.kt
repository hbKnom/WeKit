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
 * 聊天记录分析 —— 报告 PNG 导出（排版重构版 v4 · 1440px 专业报告画布）
 *
 * ── 为什么是 1440 宽 ──────────────────────────────────────────────────────
 * v3 的 1080px 在微信里二次压缩后标题与 KPI 数字发虚。v4 把画布加宽到 1440px
 * （+33% 分辨率），同时把高度上限从 30000 收到 20000：
 *   峰值内存 1440 × 20000 × 4B ≈ 115MB < v3 的 1080 × 30000 × 4B ≈ 130MB。
 * 即"更清晰"和"更省内存"同时成立 —— 宿主堆只有 512MB，这条底线不能破，
 * 因此 [MEMORY_BUDGET_BYTES] 与 init 里的 require 会把预算钉死在编译期常量上。
 *
 * ── 几何模型（全部 px，基于 1440 宽画布）─────────────────────────────────
 *
 *   0    CANVAS_PAD(72)                                     W-CANVAS_PAD(1368) 1440
 *   │◄───────►│                                                        │◄────────►│
 *   ┌───────────────────────────────────────────────────────────────────────────┐
 *   │ 卡片：CARD_LEFT(72) … CARD_RIGHT(1368)（圆角 / 细描边 / 左侧渐变强调条统一）│
 *   │  ┌ CARD_PAD_H(56) ─────────────────────────────────────── CARD_PAD_H(56) ┐ │
 *   │  │          内容区 CONTENT_LEFT(128) … CONTENT_RIGHT(1312)              │ │
 *   │  └──────────────────────────────────────────────────────────────────────┘ │
 *   └───────────────────────────────────────────────────────────────────────────┘
 *
 *   条形行严格三列（列间 COL_GAP(24)，两两互不相交）：
 *     [ label ← LABEL_W(473) ] gap [ value ← VALUE_W(213) ] gap [ bar ← BAR_W(450) ]
 *
 * ── 每个【】章节 = 一张独立卡片 ──────────────────────────────────────────
 *   卡片头部：序号徽章（渐变底 + 白色数字）→ 标题 → 渐变下划线 + 全宽细分隔线。
 *   正文里连续的 "键：值" 行聚合成 KPI 大数字网格（每行 2 格、等宽等高、单位同行或另起一行、
 *   百分比数值额外带一根份额进度条）。
 *
 *   三类"数据形状"会被自动图形化（只按结构判定，不依赖章节标题字符串，报告改字也不会失效）：
 *     · 无序号、标签不含数字的 2~8 项分布 → 环形图 + 图例（数值 + 占比）
 *     · 无序号、标签含数字的 2~24 项分布 → 柱状图（网格线 / 均值虚线 / 峰值标注 / x 轴刻度）
 *     · 含 ≥3 个 "词×次数" 的长整行       → 自适应换行的标签云
 *   判不出来的一律退回原来的条形行/正文，绝不猜。
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
 *  7. 尺寸常量是 Int，凡流入 RectF / drawText / fitBaseline 一律 .toFloat()
 *     （这条踩过 4 次编译坑，属于硬性纪律）。
 */
object ChatAnalysisPng {

    // ==================================================================
    // 一、统一度量常量（全部 px，基于 1440 宽画布）
    // ==================================================================

    /** 画布宽度 */
    private const val W = 1440

    /** 画布左右安全边距（硬约束：≥ 48px，所有卡片都对它对齐） */
    private const val CANVAS_PAD = 72

    /** 卡片内水平内边距 */
    private const val CARD_PAD_H = 56

    /** 卡片内垂直内边距（上下一致） */
    private const val CARD_PAD_V = 48

    /** 卡片与卡片之间的间距 */
    private const val CARD_GAP = 52

    /** 卡片圆角半径（全文件所有卡片、徽章、KPI 单元共用一套圆角语言） */
    private const val CARD_RADIUS = 40f

    /** 卡片左侧强调条宽度 */
    private const val CARD_ACCENT_W = 10

    /** 卡片最小高度 */
    private const val CARD_MIN_H = 220

    /** 卡片投影半径 / 垂直偏移 */
    private const val CARD_SHADOW_RADIUS = 24f
    private const val CARD_SHADOW_DY = 8f

    /** 卡片底色渐变到"强调色 6% 混白"的透明度（很淡，只做层次不抢内容） */
    private const val CARD_TINT_ALPHA = 0x10

    /** 卡片描边粗细 / 顶部高光细线高度与透明度 */
    private const val CARD_STROKE_W = 2f
    private const val CARD_TOP_LIGHT_H = 2f
    private const val CARD_TOP_LIGHT_ALPHA = 0x22

    /** 画布底部收尾留白 */
    private const val BOTTOM_PAD = 88

    /** 条形行 / 单行键值行高 */
    private const val ROW_H = 88

    /** 正文文本行高 */
    private const val TEXT_LINE_H = 74

    /** 正文文本行之间的额外留白 */
    private const val TEXT_LINE_GAP = 24

    /** 空行占位高度（段间距） */
    private const val GAP_H = 34

    /** 章节卡片头部高度（序号徽章 + 标题 + 渐变下划线） */
    private const val SECTION_HEADER_H = 168

    /** 章节标题下方的渐变下划线宽度 / 厚度 */
    private const val SECTION_UNDERLINE_W = 240
    private const val SECTION_UNDERLINE_H = 8f

    /** 章节头部下划线与右侧细分隔线之间的留白 */
    private const val SECTION_RULE_GAP = 28

    /** 章节序号徽章与标题之间的间距 / 标题带到下划线的留白 */
    private const val SECTION_TITLE_GAP = 24
    private const val SECTION_UNDERLINE_GAP = 26

    /** 章节序号徽章边长 */
    private const val SECTION_BADGE_BOX = 76

    /** 分组 pill（本地统计报告 / AI 洞察报告）高度 */
    private const val GROUP_PILL_H = 92

    /** 分组 pill 与上一张卡片的间距（分组间隔更大，节奏分明） */
    private const val GROUP_PILL_GAP = 60

    /** 分组 pill 与紧随其后的卡片之间的间距 */
    private const val GROUP_PILL_CARD_GAP = 20

    /** 分组 pill 最大宽度 */
    private const val GROUP_PILL_MAX_W = 700

    /** 分组 pill 内部：左内边距 / 圆点直径 / 圆点与文字间距 / 右内边距 */
    private const val GROUP_PILL_PAD_L = 34
    private const val GROUP_PILL_DOT = 22
    private const val GROUP_PILL_DOT_GAP = 22
    private const val GROUP_PILL_PAD_R = 32

    /** 页脚高度 / 内部节奏（上留白 / 文字行高 / 文字与品牌条间距 / 品牌条厚度） */
    private const val FOOTER_H = 96
    private const val FOOTER_TOP_GAP = 12
    private const val FOOTER_TEXT_H = 54
    private const val FOOTER_STRIP_GAP = 12
    private const val FOOTER_STRIP_H = 7f

    /** 顶部信息卡：品牌行高 */
    private const val BRAND_LINE_H = 84

    /** 顶部信息卡：品牌小方块边长 */
    private const val BRAND_BOX = 22

    /** 顶部信息卡：品牌行与主标题之间的细分隔线留白 */
    private const val HEADER_DIVIDER_GAP = 14

    /** 顶部信息卡：主标题行高 */
    private const val HEADER_TITLE_LINE_H = 116

    /** 顶部信息卡：副标题 / 生成时间行高 */
    private const val HEADER_META_LINE_H = 70

    /** 顶部信息卡：品牌行与主标题之间留白 */
    private const val HEADER_BRAND_TITLE_GAP = 30

    /** 顶部信息卡：主标题与副标题之间留白 */
    private const val HEADER_TITLE_SUB_GAP = 14

    /** 顶部信息卡：副标题与生成时间之间留白 */
    private const val HEADER_SUB_GEN_GAP = 10

    /** 顶部信息卡右上角装饰圆的半径（纯装饰，被卡片 clip 裁掉） */
    private const val HEADER_GLOW_R = 300f

    /** 顶部信息卡头像边长 */
    private const val AVATAR_SIZE = 152

    /** 头像与右侧文字列的水平间距 */
    private const val AVATAR_TEXT_GAP = 40

    /** 右上角范围徽章高度 */
    private const val BADGE_H = 76

    /** 右上角范围徽章水平内边距（单侧） */
    private const val BADGE_PAD_H = 34

    /** 右上角范围徽章最大宽度（超出则截断，防止挤占品牌行） */
    private const val BADGE_MAX_W = 480

    /** 列间距 */
    private const val COL_GAP = 24

    /** 条形轨高度 */
    private const val BAR_TRACK_H = 30f

    /** 条形最小可见宽度 */
    private const val BAR_MIN_W = 8f

    /** 文本 / 标签在列内的横向内缩 */
    private const val CELL_INSET = 8

    /** 排行行序号徽章（与 App 内报告视图同款）：边长 / 与标签的间距 / 字号 */
    private const val RANK_BOX = 56
    private const val RANK_GAP = 18
    private const val FS_RANK = 32f

    /**
     * KPI 网格内部节奏（单元高度由内部节奏推导，不再写死）：
     *   上内边距 + 标签行 + 标签↔数值间隙 + 数值行 + 下内边距 = KPI_CELL_H
     * 单位过长时（放不进数值行）额外加一行单位：+KPI_UNIT_GAP+KPI_UNIT_ROW_H。
     * 数值是百分比时再额外加一根份额进度条：[shareFraction] 非空才算。
     */
    private const val KPI_PAD_H = 34
    private const val KPI_PAD_V = 34
    private const val KPI_COL_GAP = 24
    private const val KPI_ROW_GAP = 28
    private const val KPI_LABEL_ROW_H = 46
    private const val KPI_LABEL_VALUE_GAP = 12
    private const val KPI_VALUE_ROW_H = 84
    private const val KPI_UNIT_GAP = 6
    private const val KPI_UNIT_ROW_H = 42
    private const val KPI_SHARE_GAP = 16
    private const val KPI_SHARE_BAR_H = 12
    private const val KPI_CELL_H = KPI_PAD_V * 2 + KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP + KPI_VALUE_ROW_H
    private const val KPI_CELL_H_TALL = KPI_CELL_H + KPI_UNIT_GAP + KPI_UNIT_ROW_H

    /** 单位短于等于这个长度才与数值同排；更长则另起一行（绝不因省略号丢信息） */
    private const val KPI_UNIT_INLINE_MAX = 4

    // ---- 图形化的规模上限（超过就退回条形行：环太细 / 柱太密都不好看）----

    /** 环形图最多扇区数 / 柱状图最多柱数 / 判定标签云的最少词条数 */
    private const val DONUT_MAX_SLICES = 8
    private const val CHART_MAX_BARS = 24
    private const val CHIP_MIN_TOKENS = 3

    // ---- 环形图（分布）----

    /** 环外径 / 环厚 / 扇区间隙（度） */
    private const val DONUT_SIZE = 380
    private const val DONUT_RING_W = 62f
    private const val DONUT_GAP_ANGLE = 2.2f

    /** 环与图例的水平间距 / 图例行高 / 色块边长 / 色块与文字间距 */
    private const val DONUT_LEGEND_GAP = 64
    private const val DONUT_LEGEND_ROW_H = 74
    private const val DONUT_LEGEND_SWATCH = 30
    private const val DONUT_LEGEND_SWATCH_GAP = 20

    /** 图例"数值 + 占比"之间的间距 */
    private const val DONUT_LEGEND_VALUE_GAP = 18

    /** 环心合计的说明文字（数值本身就是报告里各项之和，不做任何改写） */
    private const val DONUT_SUM_LABEL = "合计"

    // ---- 柱状图（时间分布）----

    /** 柱状图：顶部留白（够放峰值数字）/ 柱区高度 / x 轴标签区高度 / 轴标签与轴线的间距 */
    private const val CHART_TOP_PAD = 48
    private const val CHART_BAR_AREA_H = 300
    private const val CHART_AXIS_H = 60
    private const val CHART_AXIS_GAP = 10

    /** 柱状图左侧刻度槽宽度（放 y 轴数值） */
    private const val CHART_GUTTER_W = 110

    /** 柱宽上限 / 下限，以及柱底与轴线的留白（让柱体像"浮"在轴上，更像设计稿） */
    private const val CHART_BAR_MAX_W = 40f
    private const val CHART_BAR_MIN_W = 6f
    private const val CHART_BAR_BASE_GAP = 5f

    /** 均值虚线的短划长度 / 间隙 / 线厚 */
    private const val CHART_DASH_W = 18f
    private const val CHART_DASH_GAP = 14f
    private const val CHART_DASH_H = 3f

    /** 柱状图 x 轴标签间隔（每 N 根标一个，最后一根一定标） */
    private const val CHART_LABEL_EVERY = 3

    /** 柱状图整体高度 */
    private const val COLUMN_CHART_H = CHART_TOP_PAD + CHART_BAR_AREA_H + CHART_AXIS_H

    /** 均值虚线的说明前缀（值是各柱的算术平均，如实展示） */
    private const val AVG_LABEL = "均值"

    /** 均值标注：与虚线的间距 / 标注行高 */
    private const val CHART_AVG_LABEL_GAP = 34f
    private const val CHART_AVG_LABEL_H = 30f

    // ---- 标签云（词频）----

    /** 标签高度 / 标签内水平内边距 / 标签间距 / 行间距 */
    private const val CHIP_H = 64
    private const val CHIP_PAD_H = 26
    private const val CHIP_GAP = 16
    private const val CHIP_LINE_GAP = 18

    /** 顶部卡片 / 卡片的最大显示行数（超出按测量宽度省略，绝不溢出） */
    private const val HEADER_NAME_MAX_LINES = 3
    private const val HEADER_META_MAX_LINES = 2

    /** 画布最小高度 */
    private const val MIN_H = 900

    /** 画布高度上限（超过直接拒绝导出，避免 OOM） */
    private const val MAX_HEIGHT = 20000

    /** 单张 ARGB_8888 画布的像素内存预算（512MB 宿主堆下的安全上限） */
    private const val MEMORY_BUDGET_BYTES = 120 * 1024 * 1024

    // ---- 由上面的常量推导：卡片 / 内容区 ----
    private const val CARD_LEFT = CANVAS_PAD                                  // 72
    private const val CARD_RIGHT = W - CANVAS_PAD                             // 1368
    private const val CONTENT_LEFT = CARD_LEFT + CARD_PAD_H                   // 128
    private const val CONTENT_RIGHT = CARD_RIGHT - CARD_PAD_H                 // 1312
    private const val CONTENT_W = CONTENT_RIGHT - CONTENT_LEFT                // 1184

    // ---- 由上面的常量推导：条形行三列（40% / 18% / 余量 − 2×COL_GAP）----
    private const val LABEL_W = CONTENT_W * 40 / 100                          // 473
    private const val VALUE_W = CONTENT_W * 18 / 100                          // 213
    private const val BAR_W = CONTENT_W - LABEL_W - VALUE_W - COL_GAP * 2     // 450

    private const val LABEL_LEFT = CONTENT_LEFT                               // 128
    private const val LABEL_RIGHT = LABEL_LEFT + LABEL_W                      // 601
    private const val VALUE_LEFT = LABEL_RIGHT + COL_GAP                      // 625
    private const val VALUE_RIGHT = VALUE_LEFT + VALUE_W                      // 838
    private const val BAR_LEFT = VALUE_RIGHT + COL_GAP                        // 862
    private const val BAR_RIGHT = BAR_LEFT + BAR_W                            // 1312

    /** KPI 单元宽度（一行两格，两格 + 列间距恰好占满内容区） */
    private const val KPI_CELL_W = (CONTENT_W - KPI_COL_GAP) / 2               // 580

    // ---- 字号（px）：标题 74 / 章节 54 / 正文 40 / 行 38 / 刻度 30 ----
    private const val FS_BRAND = 38f
    private const val FS_TITLE = 74f
    private const val FS_META = 38f
    private const val FS_SMALL = 34f
    private const val FS_TICK = 30f
    private const val FS_AVATAR = 66f
    private const val FS_BADGE = 34f
    private const val FS_GROUP = 40f
    private const val FS_SECTION = 54f
    private const val FS_SECTION_NO = 40f
    private const val FS_BODY = 40f
    private const val FS_ROW = 38f
    private const val FS_KPI_LABEL = 34f
    private const val FS_KPI_VALUE = 62f
    private const val FS_KPI_UNIT = 34f

    // ---- 配色（集中常量：主色 / 强调色 / 成功 / 警示 / 危险 / 文本主次 / 分隔线）----
    private const val COLOR_BG_TOP = 0xFFEEF5FC.toInt()
    private const val COLOR_BG_MID = 0xFFF7FAFE.toInt()
    private const val COLOR_BG_BOTTOM = 0xFFFFFFFF.toInt()
    private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    private const val COLOR_TITLE = 0xFF12233A.toInt()
    private const val COLOR_BODY = 0xFF2B3A4B.toInt()
    private const val COLOR_META = 0xFF7C8CA0.toInt()
    private const val COLOR_ACCENT = 0xFF2E7DD1.toInt()
    private const val COLOR_ACCENT2 = 0xFF12B3A8.toInt()
    private const val COLOR_SUCCESS = 0xFF1F9D55.toInt()
    private const val COLOR_WARN = 0xFFD89A16.toInt()
    private const val COLOR_DANGER = 0xFFD64545.toInt()
    private const val COLOR_RULE = 0xFFE1E9F2.toInt()
    private const val COLOR_TRACK = 0xFFE7EEF6.toInt()
    private const val COLOR_SHADOW = 0x14000000
    private const val COLOR_RANK_GOLD = 0xFFD89A16.toInt()
    private const val COLOR_RANK_SILVER = 0xFF7F8C9B.toInt()
    private const val COLOR_RANK_BRONZE = 0xFFB9754A.toInt()

    /** 固定品牌文案（顶部品牌行 / 页脚水印）与空报告文案 */
    private const val BRAND_TEXT = "WeKit · 聊天记录分析"
    private const val FOOTER_PAGE_TEXT = "第 1 / 1 页"
    private const val GROUP_STATS = "本地统计报告"
    private const val GROUP_AI = "AI 洞察报告"
    private const val EMPTY_TITLE = "无可统计内容"
    private const val EMPTY_HINT = "该时段没有可统计的文本消息。"
    private const val EMPTY_HINT_SUB = "请换一个时间范围，或确认该会话在此范围内确实有文本消息。"

    /** 孤字控制：段落末行短于等于这个字符数时，从上一行挪一个字下来 */
    private const val ORPHAN_MAX_CHARS = 2

    /** 环形图配色（按扇区顺序取用；都是中明度色，白底上够清晰） */
    private val DONUT_PALETTE = intArrayOf(
        0xFF2E7DD1.toInt(),
        0xFF12B3A8.toInt(),
        0xFF7A5AF8.toInt(),
        0xFFD89A16.toInt(),
        0xFF1F9D55.toInt(),
        0xFFE0625E.toInt(),
        0xFF3C8CFF.toInt(),
        0xFF8A6E4B.toInt(),
    )

    /**
     * KPI 语义染色关键词：同样的数字在不同语境下含义不同，用颜色把"亮眼 / 需注意 / 风险"提前说清。
     * 只匹配强信号词，匹配不到就用卡片主色 —— 宁可不染色，也不乱染色。
     */
    private val KEY_SUCCESS = listOf("最多", "峰值", "最高", "增长", "上升")
    private val KEY_WARN = listOf("最少", "最低", "深夜", "凌晨", "废话")
    private val KEY_DANGER = listOf("风险", "异常", "冲突", "警告", "负面")

    init {
        // ---- 画布内存预算：单张 ARGB_8888 位图必须留在宿主堆能承受的范围内 ----
        require(W * MAX_HEIGHT * 4 <= MEMORY_BUDGET_BYTES) {
            "PNG 画布内存预算超限：${W}×${MAX_HEIGHT}×4B 超过 120MB"
        }
        require(MAX_HEIGHT >= MIN_H) { "PNG 画布高度上限小于最小高度" }

        // ---- 列几何：三列两两不相交，且恰好占满卡片内容区 ----
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

        // ---- 行高必须装得下对应字号（CJK 的 descent 比拉丁文更吃高度）----
        require(
            TEXT_LINE_H > FS_BODY && ROW_H > FS_ROW && HEADER_TITLE_LINE_H > FS_TITLE &&
                HEADER_META_LINE_H > FS_META && BRAND_LINE_H > FS_BRAND &&
                KPI_LABEL_ROW_H > FS_KPI_LABEL && KPI_VALUE_ROW_H > FS_KPI_VALUE &&
                KPI_UNIT_ROW_H > FS_KPI_UNIT && SECTION_BADGE_BOX > FS_SECTION_NO &&
                RANK_BOX > FS_RANK && BADGE_H > FS_BADGE && GROUP_PILL_H > FS_GROUP &&
                FOOTER_TEXT_H > FS_SMALL && AVATAR_SIZE > FS_AVATAR && CHIP_H > FS_ROW &&
                DONUT_LEGEND_ROW_H > FS_ROW,
        ) { "PNG 行高与字号不匹配，文字会溢出" }

        // ---- 章节头部 / 徽章 / 分组 pill ----
        require(SECTION_HEADER_H >= SECTION_BADGE_BOX + 40) { "PNG 章节头部高度装不下序号徽章" }
        require(SECTION_UNDERLINE_W <= CONTENT_W) { "PNG 章节下划线超过内容区宽度" }
        require(SECTION_BADGE_BOX > SECTION_UNDERLINE_H) { "PNG 章节下划线比徽章还厚" }
        require(BADGE_H <= BRAND_LINE_H) { "PNG 范围徽章比品牌行还高" }
        require(GROUP_PILL_DOT + GROUP_PILL_DOT_GAP < GROUP_PILL_H) { "PNG 分组 pill 圆点装不下" }
        require(GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP + GROUP_PILL_PAD_R <=
            GROUP_PILL_MAX_W) { "PNG 分组 pill 最小宽度超过最大宽度" }

        // ---- KPI 单元：内部排版（标签 + 数值 [+ 单位] [+ 份额条]）必须装得进单元高度 ----
        require(KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP + KPI_VALUE_ROW_H + KPI_PAD_V <= KPI_CELL_H) {
            "PNG KPI 单元内部排版超出单元高度"
        }
        require(KPI_CELL_H_TALL > KPI_CELL_H) { "PNG KPI 带单位行的单元高度非法" }
        require(KPI_SHARE_BAR_H in 8..KPI_VALUE_ROW_H) { "PNG KPI 份额条高度非法" }

        // ---- 排行行：圆角序号徽章必须装得进行高，且标签列扣掉徽章后仍有余量 ----
        require(ROW_H >= RANK_BOX + 8) { "PNG 行长装不下排行序号徽章" }
        require(LABEL_W - CELL_INSET * 2 - RANK_BOX - RANK_GAP >= 120) { "PNG 排行标签列可用宽度过窄" }
        require(CARD_MIN_H >= CARD_PAD_V * 2 + ROW_H) { "PNG 卡片最小高度小于内边距 + 一行" }

        // ---- 环形图：环 + 间距 + 图例必须都装得进内容区 ----
        require(DONUT_SIZE > DONUT_RING_W * 2f + 80f) { "PNG 环形图内孔过小，放不下合计数字" }
        require(CONTENT_W - DONUT_SIZE - DONUT_LEGEND_GAP >= 400) { "PNG 环形图图例可用宽度过窄" }
        require(DONUT_LEGEND_SWATCH + DONUT_LEGEND_SWATCH_GAP < DONUT_LEGEND_ROW_H) {
            "PNG 环形图图例色块装不下"
        }

        // ---- 柱状图：刻度槽 + 柱区必须占满内容区且留得下柱宽 ----
        require(CHART_GUTTER_W >= 80 && CHART_GUTTER_W < CONTENT_W / 4) { "PNG 柱状图刻度槽宽度非法" }
        require(CONTENT_W - CHART_GUTTER_W >= 600) { "PNG 柱状图柱区过窄" }
        require(CHART_BAR_AREA_H >= 200) { "PNG 柱状图柱区高度过矮" }
        require(CHART_TOP_PAD >= CHART_DASH_H * 4f) { "PNG 柱状图顶部留白放不下峰值标注" }
        require(CHART_BAR_MAX_W > CHART_BAR_MIN_W) { "PNG 柱状图柱宽上下限非法" }
        require(COLUMN_CHART_H == CHART_TOP_PAD + CHART_BAR_AREA_H + CHART_AXIS_H) {
            "PNG 柱状图高度定义不一致"
        }

        // ---- 标签云：单个标签的左右内边距不能超过内容区一半 ----
        require(CHIP_PAD_H * 2 < CONTENT_W / 2) { "PNG 标签云内边距过大" }

        // ---- 页脚：上留白 + 文字行 + 间距 + 品牌条 必须装得进页脚高度 ----
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

        /** 分布类章节：环形图（环 + 图例，图例带占比） */
        data class Donut(val slices: List<Slice>) : Block()

        /** 时间分布章节：柱状图（网格 + 均值虚线 + 峰值标注 + x 轴刻度） */
        data class ColumnChart(val bars: List<Column>) : Block()

        /** 词频类章节：自适应换行的标签云（原来是一整段挤在一起的文字） */
        data class ChipCloud(val items: List<String>) : Block()

        object Gap : Block()
    }

    /** 环形图扇区：count 只用来算比例，展示时一律用原值文本（不改写报告给出的数字） */
    private data class Slice(val label: String, val value: String, val count: Long)

    /** 柱状图柱：label 即 x 轴标签 */
    private data class Column(val label: String, val value: String, val count: Long)

    /** 已定位的一行：top / height 都是相对卡片正文区顶部的偏移 */
    private class Row(
        val unit: Block,
        val top: Int,
        val height: Int,
        val lines: List<String> = emptyList(),
        /** 标签云换行结果（布局阶段算好，绘制阶段原样使用，保证两遍完全一致） */
        val chipLines: List<List<String>> = emptyList(),
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

    /** 词频标签："哈哈×123"（引擎用 '×' 连接词与次数） */
    private val CHIP_TOKEN = Regex("^[^×\\s]+×\\d+$")

    /**
     * 没有 █ 的分布行："链接 233"。
     * 引擎按 value/max×16 画条形，占比太小的项会算出 0 个方块（整行没有 █），
     * 但它们在语义上仍然是分布项 —— 不认出来，环形图/柱状图就会漏掉这些小项。
     */
    private val PLAIN_COUNT_LINE = Regex("^(\\S+)\\s+(\\d+)$")

    /** 空白切分（词频行用两个空格分段） */
    private val WHITESPACE = Regex("\\s+")

    /** CJK 统一表意文字区间（识别中文标签用） */
    private const val CJK_FIRST = 0x4E00
    private const val CJK_LAST = 0x9FFF

    /** 报告里的全角空格（"≤5字：60%　≤20字：30%" 用它在同一行塞两组指标） */
    private const val IDEO_SPACE = '　'

    /** KPI 行的判定阈值（与 App 内报告视图的键值行判定保持一致，避免两处观感割裂） */
    private const val KV_MAX_LINE_LEN = 40
    private const val KV_MAX_KEY_LEN = 20

    /**
     * 值这一档比 App 内列表视图宽松（列表窄、PNG 单元格宽 580px）：
     * "消息总数：15842 条（纯文本 12033 条）" 这种头条数字在 PNG 里应该走 KPI 大数字，
     * 值太长也只是自动多占一行单位行，不会溢出 —— 见 [drawKpiCell]。
     */
    private const val KV_MAX_VALUE_LEN = 24

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
                else -> {
                    val plain = parsePlainCount(t)
                    if (plain != null) out.add(plain) else out.addAll(parseTextOrKeyValue(t))
                }
            }
        }
        return out
    }

    /**
     * "链接 233" 这种没有条形图的分布行：标签必须是中文词、不能带冒号。
     * 这两条限制是为了不把正文里的 "2026 08"、"结论： 12" 之类误判成分布项。
     */
    private fun parsePlainCount(t: String): Block.BarLine? {
        val m = PLAIN_COUNT_LINE.find(t) ?: return null
        val label = m.groupValues[1]
        val value = m.groupValues[2]
        if (label.contains("：") || label.contains(":")) return null
        if (!label.any { it.code in CJK_FIRST..CJK_LAST }) return null
        return Block.BarLine(label, value, 0f, 0)
    }

    /**
     * 普通行：可能是一行里塞了多组指标的 "键：值" 行，也可能是普通正文。
     * 先用全角空格拆一次（每组都含 "：" 才认），拆不出来再按单组判定 ——
     * 因为 "≤5字：60%　≤20字：30%" 这种行如果整行当一个值，会被省略号吃掉一半信息。
     */
    private fun parseTextOrKeyValue(t: String): List<Block> {
        val segments = t.split(IDEO_SPACE)
        if (segments.size > 1 && segments.all { it.contains("：") }) {
            val pairs = segments.map { asKeyValue(it) }
            if (pairs.none { it == null }) return pairs.filterNotNull()
        }
        val single = asKeyValue(t)
        return if (single != null) listOf(single) else listOf(Block.TextLine(t))
    }

    /** 尝试把一行解析成 "键：值"；不满足阈值就返回 null（交给正文排版） */
    private fun asKeyValue(t: String): Block.KeyValue? {
        if (t.length > KV_MAX_LINE_LEN || !t.contains("：")) return null
        val idx = t.indexOf("：")
        val key = t.substring(0, idx).trim()
        val value = t.substring(idx + 1).trim()
        if (key.isEmpty() || key.length > KV_MAX_KEY_LEN) return null
        if (value.isEmpty() || value.length > KV_MAX_VALUE_LEN) return null
        return Block.KeyValue(key, value)
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

    /**
     * 把连续的 KeyValue 行聚合成 KPI 网格（≥2 项才聚合，单项按普通键值行绘制）。
     * 值里没有数字的 "键：值" 不是指标而是结论（"鉴定：正常人类浓度"），
     * 拿 62px 大字号去渲染只会变成一行怪字，所以这类一律退回正文排版。
     */
    private fun groupKpis(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>(blocks.size)
        var run = ArrayList<Block.KeyValue>()
        fun flush() {
            if (run.size >= 2) out.add(Block.KpiGrid(run.toList())) else out.addAll(run)
            run = ArrayList()
        }
        for (b in blocks) {
            if (b is Block.KeyValue && isMeasurable(b)) {
                run.add(b)
            } else {
                flush()
                out.add(if (b is Block.KeyValue) Block.TextLine("${b.key}：${b.value}") else b)
            }
        }
        flush()
        return out
    }

    /** 指标的值必须含数字，否则它是一句结论 */
    private fun isMeasurable(kv: Block.KeyValue): Boolean = kv.value.any { it.isDigit() }

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

    /**
     * 把一段正文里"连续的无序号条形行"识别成图表（只按数据形状判定，不看章节标题）：
     *   · 标签含数字（"凌晨0-5点"）→ 时间分布 → 柱状图
     *   · 标签不含数字（"文字"/"图片"）→ 类别分布 → 环形图
     * 任何一条不满足（有序号 / 数量超限 / 数值解析不出来）就原样返回，退回条形行 —— 宁可不画，不画错。
     */
    private fun shapeBody(body: List<Block>): List<Block> {
        val out = ArrayList<Block>(body.size)
        var i = 0
        while (i < body.size) {
            val b = body[i]
            if (b is Block.BarLine && b.rank == 0) {
                var j = i
                while (j < body.size && body[j] is Block.BarLine && (body[j] as Block.BarLine).rank == 0) j++
                val run = body.subList(i, j).map { it as Block.BarLine }
                val chart = asColumnChart(run) ?: asDonut(run)
                if (chart != null) {
                    out.add(chart)
                } else {
                    out.addAll(withRunRatios(run))
                }
                i = j
            } else {
                out.add(shapedText(b))
                i++
            }
        }
        return out
    }

    /**
     * 同一段分布内补齐缺失的比例：引擎对小数值不画 █（比例算出来是 0），
     * 直接画就会"有数字没条形"。按该段最大值补算后，条形才有参照意义。
     * 已经有方块的行原样保留 —— 引擎给的比例本来就是 value/max。
     */
    private fun withRunRatios(run: List<Block.BarLine>): List<Block.BarLine> {
        if (run.size < 2) return run
        val counts = run.map { parseCount(it.value) }
        if (counts.any { it < 0L }) return run
        val max = counts.maxOrNull() ?: return run
        if (max <= 0L) return run
        return run.mapIndexed { i, b ->
            if (b.ratio > 0f) b
            else b.copy(ratio = (counts[i].toFloat() / max.toFloat()).coerceIn(0f, 1f))
        }
    }

    /** 单行内容里的标签云替换：词频行是"哈哈×123  你好×99 …"，挤成一段正文很难读 */
    private fun shapedText(b: Block): Block {
        if (b !is Block.TextLine) return b
        val tokens = b.text.split(WHITESPACE).filter { it.isNotEmpty() }
        val chips = tokens.filter { CHIP_TOKEN.matches(it) }
        if (chips.size < CHIP_MIN_TOKENS || chips.size != tokens.size) return b
        return Block.ChipCloud(chips)
    }

    /** 文字分布 → 环形图（2~8 项、标签不含数字、数值全为正） */
    private fun asDonut(run: List<Block.BarLine>): Block.Donut? {
        if (run.size !in 2..DONUT_MAX_SLICES) return null
        if (run.any { it.label.any { c -> c.isDigit() } }) return null
        val counts = run.map { parseCount(it.value) }
        if (counts.any { it <= 0L }) return null
        return Block.Donut(run.mapIndexed { i, b -> Slice(b.label, b.value, counts[i]) })
    }

    /** 时间分布 → 柱状图（2~24 项、标签含数字、数值非负且不全为 0） */
    private fun asColumnChart(run: List<Block.BarLine>): Block.ColumnChart? {
        if (run.size !in 2..CHART_MAX_BARS) return null
        if (run.none { it.label.any { c -> c.isDigit() } }) return null
        val counts = run.map { parseCount(it.value) }
        if (counts.any { it < 0L } || counts.all { it == 0L }) return null
        return Block.ColumnChart(run.mapIndexed { i, b -> Column(b.label, b.value, counts[i]) })
    }

    /** 只取数字：报告里的值可能是 "5,468"/"5468 条"，解析不出来返回 -1 作为哨兵 */
    private fun parseCount(value: String): Long {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return -1L
        return digits.toLongOrNull() ?: -1L
    }

    /** 数值是百分比就返回 0..1 的份额（用于 KPI 单元里的份额进度条），否则 null */
    private fun shareFraction(value: String): Float? {
        val t = value.trim()
        if (t.length < 2 || !t.endsWith("%")) return null
        val n = t.dropLast(1).trim().toFloatOrNull() ?: return null
        return (n / 100f).coerceIn(0f, 1f)
    }

    /** KPI 语义色：匹配不到就用卡片主色 */
    private fun semanticColor(key: String): Int? = when {
        KEY_DANGER.any { key.contains(it) } -> COLOR_DANGER
        KEY_WARN.any { key.contains(it) } -> COLOR_WARN
        KEY_SUCCESS.any { key.contains(it) } -> COLOR_SUCCESS
        else -> null
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
            // 宽度向上取整：.toInt() 会把测量值截掉不到 1px，恰好等于内容宽时
            // 内边距就少了那 1px，短标签（"本月"）会被判成放不下而只剩一个省略号
            (ceilToInt(badgeP.measureText(badgeText)) + BADGE_PAD_H * 2).coerceAtMost(BADGE_MAX_W)
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
                    // 高度必须与 drawKpiGrid 的分行方式完全一致（含"单位另起一行""份额条"两档）
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
                is Block.Donut -> {
                    val h = donutHeight(u)
                    rows.add(Row(u, y, h))
                    y += h
                }
                is Block.ColumnChart -> {
                    rows.add(Row(u, y, COLUMN_CHART_H))
                    y += COLUMN_CHART_H
                }
                is Block.ChipCloud -> {
                    // 换行结果在这里算一次并随行携带，绘制阶段直接复用（两遍必须完全一致）
                    val chipRows = chipLines(u.items)
                    val h = chipCloudHeight(chipRows.size)
                    rows.add(Row(u, y, h, emptyList(), chipRows))
                    y += h
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

    /** 环形图高度：环与图例谁高听谁的（环垂直居中在图例旁边） */
    private fun donutHeight(d: Block.Donut): Int =
        maxOf(DONUT_SIZE, d.slices.size * DONUT_LEGEND_ROW_H)

    /** 标签云高度：行数由 [chipLines] 决定，与绘制完全同源 */
    private fun chipCloudHeight(lineCount: Int): Int =
        lineCount.coerceAtLeast(1) * CHIP_H + (lineCount - 1).coerceAtLeast(0) * CHIP_LINE_GAP

    /** 单个标签宽度：文字宽 + 左右内边距；上限是整行宽（保证永不溢出内容区） */
    private fun chipWidth(item: String, p: Paint): Float =
        (p.measureText(item) + CHIP_PAD_H * 2).coerceAtMost(CONTENT_W.toFloat())

    /** 标签云换行：贪心填行；单个标签最宽只占一整行 */
    private fun chipLines(items: List<String>): List<List<String>> {
        val p = paint(FS_ROW, COLOR_BODY)
        val out = ArrayList<List<String>>()
        var line = ArrayList<String>()
        var used = 0f
        for (item in items) {
            val w = chipWidth(item, p)
            if (line.isNotEmpty() && used + CHIP_GAP + w > CONTENT_W.toFloat()) {
                out.add(line)
                line = ArrayList()
                used = 0f
            }
            if (line.isNotEmpty()) used += CHIP_GAP
            line.add(item)
            used += w
        }
        if (line.isNotEmpty()) out.add(line)
        return out
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
            // 先按数据形状把可图形化的连续行换成图表，再走同一套几何布局
            val rows = layoutRows(shapeBody(body), bodyP)
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
            // 空报告：给一张带标题和引导文案的说明卡，而不是一张光秃秃的白卡
            val rows = layoutRows(
                listOf(Block.TextLine(EMPTY_HINT), Block.TextLine(EMPTY_HINT_SUB)),
                bodyP,
            )
            val lastBottom = rows.lastOrNull()?.let { it.top + it.height } ?: 0
            items.add(
                Item.Card(
                    EMPTY_TITLE, 0, COLOR_WARN, rows,
                    maxOf(CARD_MIN_H, CARD_PAD_V * 2 + SECTION_HEADER_H + lastBottom),
                ),
            )
        }
        return items
    }

    private fun pillWidth(label: String, accent: Int): Int {
        val p = paint(FS_GROUP, accent, bold = true)
        val available = (GROUP_PILL_MAX_W - GROUP_PILL_PAD_L - GROUP_PILL_DOT - GROUP_PILL_DOT_GAP - GROUP_PILL_PAD_R).toFloat()
        val text = truncateToWidth(label, available, p)
        return (GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP +
            ceilToInt(p.measureText(text)) + GROUP_PILL_PAD_R).coerceAtMost(GROUP_PILL_MAX_W)
    }

    /** 测量值向上取整：见 [layoutHeader] 里徽章宽度的注释（截断会吃掉 1px 内边距） */
    private fun ceilToInt(value: Float): Int = Math.ceil(value.toDouble()).toInt()

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
        cv.drawRect(0f, 0f, W.toFloat(), total.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    /**
     * 统一的卡片外观：完全相同的左右边界、圆角、细描边、轻阴影、左侧渐变强调条。
     * 顶部信息卡额外叠一层装饰圆（glow=true），画在底色之后、描边之前，所以不会盖住卡片边线。
     */
    private fun drawCard(cv: Canvas, top: Int, bottom: Int, accent: Int, glow: Boolean = false) {
        val rect = RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), bottom.toFloat())

        // 1) 投影：带阴影的圆角矩形（Bitmap 画布走软件渲染，阴影生效）
        val shadowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CARD
            style = Paint.Style.FILL
            setShadowLayer(CARD_SHADOW_RADIUS, 0f, CARD_SHADOW_DY, COLOR_SHADOW)
        }
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, shadowP)
        shadowP.clearShadowLayer()

        // 2) 卡片底色：白 → 极浅强调色（同一强调色只做明度区分，整体更克制）
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, top.toFloat(), 0f, bottom.toFloat(),
                intArrayOf(COLOR_CARD, blendOnWhite(accent, CARD_TINT_ALPHA)),
                null, Shader.TileMode.CLAMP,
            )
        })

        // 3) 顶部装饰圆（被卡片 clip 限制在卡内），只给单张卡片加，避免整本都是装饰
        if (glow) {
            cv.drawCircle(
                CARD_RIGHT.toFloat() - 40f, top + 40f, HEADER_GLOW_R,
                shapePaint(blendOnWhite(COLOR_ACCENT2, 0x12)),
            )
        }

        // 4) 细描边：分享后被压暗也不糊
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_RULE
            style = Paint.Style.STROKE
            strokeWidth = CARD_STROKE_W
        })

        // 5) 顶部高光细线：两端内缩到圆角之后，避免和圆角打架
        cv.drawRect(
            RectF(CARD_LEFT + CARD_RADIUS, top + 1f, CARD_RIGHT - CARD_RADIUS, top + 1f + CARD_TOP_LIGHT_H),
            shapePaint(blendOnWhite(accent, CARD_TOP_LIGHT_ALPHA)),
        )

        // 6) 左侧强调条：竖向渐变（实色 → 提亮），clip 到卡片圆角内
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
                5f, 5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        drawCard(cv, top, bottom, COLOR_ACCENT, glow = true)
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
        if (brandRight > CONTENT_LEFT + 160f) {
            val markTop = brandTop + (BRAND_LINE_H - BRAND_BOX) / 2f
            cv.drawRoundRect(
                RectF(
                    CONTENT_LEFT.toFloat(), markTop,
                    (CONTENT_LEFT + BRAND_BOX).toFloat(), markTop + BRAND_BOX,
                ),
                6f, 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        CONTENT_LEFT.toFloat(), markTop,
                        (CONTENT_LEFT + BRAND_BOX).toFloat(), markTop + BRAND_BOX,
                        intArrayOf(COLOR_ACCENT, COLOR_ACCENT2),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
            val brandClip = RectF(
                (CONTENT_LEFT + BRAND_BOX + 18).toFloat(), brandTop,
                brandRight, brandTop + BRAND_LINE_H,
            )
            val brand = truncateToWidth(BRAND_TEXT, brandClip.width(), brandP)
            drawClipped(
                cv, brand, brandClip.left,
                fitBaseline(brandTop, BRAND_LINE_H.toFloat(), brandP, minOf(brandClip.bottom, innerBottom)),
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
            cv.drawRoundRect(bRect, BADGE_H / 2f, BADGE_H / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

        // ---- 品牌行与标题之间的细分隔线（把封面分成"品牌"和"会话"两层）----
        val divY = inner + BRAND_LINE_H + HEADER_DIVIDER_GAP
        cv.drawRect(
            RectF(CONTENT_LEFT.toFloat(), divY, CONTENT_RIGHT.toFloat(), divY + 2f),
            shapePaint(COLOR_RULE),
        )

        // ---- 头像（渐变圆角方块 + 首字）----
        val avRect = RectF(
            CONTENT_LEFT.toFloat(), inner + spec.avatarTop,
            (CONTENT_LEFT + AVATAR_SIZE).toFloat(), inner + spec.avatarTop + AVATAR_SIZE,
        )
        cv.drawRoundRect(avRect, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
                is Block.Donut -> drawDonut(cv, u, rowTop, innerBottom)
                is Block.ColumnChart -> drawColumnChart(cv, u, rowTop, card.accent, innerBottom)
                is Block.ChipCloud -> drawChipCloud(cv, row.chipLines, rowTop, card.accent, innerBottom)
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

    /** 章节卡片头部：序号徽章（渐变底 + 白色数字）+ 标题 + 渐变下划线 + 右侧细分隔线 */
    private fun drawCardHeader(cv: Canvas, cardTop: Int, title: String, index: Int, accent: Int) {
        val headerTop = (cardTop + CARD_PAD_V).toFloat()
        val bandH = SECTION_BADGE_BOX.toFloat()
        val badgeW = if (index > 0) SECTION_BADGE_BOX else 0

        if (badgeW > 0) {
            val badge = RectF(
                CONTENT_LEFT.toFloat(), headerTop,
                (CONTENT_LEFT + badgeW).toFloat(), headerTop + bandH,
            )
            cv.drawRoundRect(badge, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    badge.left, badge.top, badge.right, badge.bottom,
                    intArrayOf(accent, lighten(accent)),
                    null, Shader.TileMode.CLAMP,
                )
            })
            val noP = paint(FS_SECTION_NO, Color.WHITE, bold = true)
            val noText = index.toString()
            drawClipped(
                cv, noText,
                badge.centerX() - noP.measureText(noText) / 2f,
                fitBaseline(badge.top, bandH, noP, badge.bottom),
                badge, noP,
            )
        }

        val titleP = paint(FS_SECTION, COLOR_TITLE, bold = true)
        val titleLeft = CONTENT_LEFT + badgeW + if (badgeW > 0) SECTION_TITLE_GAP else 0
        val titleClip = RectF(
            titleLeft.toFloat(), headerTop,
            CONTENT_RIGHT.toFloat(), headerTop + bandH,
        )
        val text = truncateToWidth(title, titleClip.width() - CELL_INSET, titleP)
        drawClipped(
            cv, text, (titleLeft + CELL_INSET).toFloat(),
            fitBaseline(headerTop, bandH, titleP, titleClip.bottom),
            titleClip, titleP,
        )

        // 下划线（强调色渐隐）+ 右侧细分隔线：一条水平视觉线把"标题带"收住
        val ulTop = headerTop + bandH + SECTION_UNDERLINE_GAP
        cv.drawRoundRect(
            RectF(
                CONTENT_LEFT.toFloat(), ulTop,
                (CONTENT_LEFT + SECTION_UNDERLINE_W).toFloat(), ulTop + SECTION_UNDERLINE_H,
            ),
            SECTION_UNDERLINE_H / 2f, SECTION_UNDERLINE_H / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    CONTENT_LEFT.toFloat(), 0f,
                    (CONTENT_LEFT + SECTION_UNDERLINE_W).toFloat(), 0f,
                    intArrayOf(accent, withAlpha(accent, 0x00)),
                    null, Shader.TileMode.CLAMP,
                )
            },
        )
        val ruleLeft = CONTENT_LEFT + SECTION_UNDERLINE_W + SECTION_RULE_GAP
        if (ruleLeft < CONTENT_RIGHT) {
            val ruleY = ulTop + (SECTION_UNDERLINE_H - 2f) / 2f
            cv.drawRect(
                RectF(ruleLeft.toFloat(), ruleY, CONTENT_RIGHT.toFloat(), ruleY + 2f),
                shapePaint(COLOR_RULE),
            )
        }
    }

    /** 分组 pill：左对齐、与卡片同左边界的圆角标签（本地统计报告 / AI 洞察报告） */
    private fun drawGroupPill(cv: Canvas, pill: Item.Pill, top: Float) {
        val rect = RectF(
            CARD_LEFT.toFloat(), top,
            (CARD_LEFT + pill.width).toFloat(), top + GROUP_PILL_H,
        )
        val radius = GROUP_PILL_H / 2f
        cv.drawRoundRect(rect, radius, radius, shapePaint(blendOnWhite(pill.accent, 0x1A)))
        cv.drawRoundRect(
            rect, radius, radius,
            shapePaint(blendOnWhite(pill.accent, 0x48), stroke = true, strokeWidth = 2f),
        )

        val dotLeft = (CARD_LEFT + GROUP_PILL_PAD_L).toFloat()
        val dotTop = top + (GROUP_PILL_H - GROUP_PILL_DOT) / 2f
        cv.drawOval(
            RectF(dotLeft, dotTop, dotLeft + GROUP_PILL_DOT, dotTop + GROUP_PILL_DOT),
            shapePaint(pill.accent),
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

    /**
     * 单个 KPI 单元高度：基础高度 + （单位另起一行）+ （百分比份额条）。
     * [drawKpiCell] 的纵向推进顺序与此函数严格一一对应。
     */
    private fun kpiCellHeight(kv: Block.KeyValue): Int {
        var h = KPI_CELL_H
        if (needsUnitRow(kv)) h += KPI_UNIT_GAP + KPI_UNIT_ROW_H
        if (shareFraction(kv.value) != null) h += KPI_SHARE_GAP + KPI_SHARE_BAR_H
        return h
    }

    /** 一行（最多两张单元）的高度：取两张里更高的那张，保证同一行底色等高 */
    private fun kpiRowCellH(row: List<Block.KeyValue>): Int =
        row.maxOf { kpiCellHeight(it) }

    /** KPI 网格整体高度：与 [drawKpiGrid] 的排布完全一致 */
    private fun kpiGridHeight(items: List<Block.KeyValue>): Int {
        val rows = items.chunked(2)
        return rows.sumOf { kpiRowCellH(it) } + (rows.size - 1).coerceAtLeast(0) * KPI_ROW_GAP
    }

    /** KPI 网格：每行两张浅色底卡片，标签小、数字大（语义色）、单位轻、百分比带份额条 */
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
        // 语义色：命中的关键词决定数字与底色的色调，没命中就用卡片主色
        val tint = semanticColor(kv.key) ?: accent
        val rect = RectF(left.toFloat(), top, (left + KPI_CELL_W).toFloat(), top + cellH)
        cv.drawRoundRect(rect, 26f, 26f, shapePaint(blendOnWhite(tint, 0x12)))
        cv.drawRoundRect(
            rect, 26f, 26f,
            shapePaint(blendOnWhite(tint, 0x3A), stroke = true, strokeWidth = 2f),
        )

        val innerLeft = (left + KPI_PAD_H).toFloat()
        val innerRight = (left + KPI_CELL_W - KPI_PAD_H).toFloat()

        // ---- 标签（小字、次要色）----
        val labelP = paint(FS_KPI_LABEL, COLOR_META)
        val labelTop = top + KPI_PAD_V
        val labelClip = RectF(innerLeft, labelTop, innerRight, labelTop + KPI_LABEL_ROW_H)
        val labelText = truncateToWidth(kv.key, labelClip.width(), labelP)
        drawClipped(
            cv, labelText, innerLeft,
            fitBaseline(labelTop, KPI_LABEL_ROW_H.toFloat(), labelP, minOf(labelClip.bottom, limitBottom)),
            labelClip, labelP,
        )

        // ---- 数值（大数字 + 轻单位）。单位短就同排；长则另起一行，绝不因宽度不足丢字 ----
        val valueTop = labelTop + KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP
        val valueClip = RectF(innerLeft, valueTop, innerRight, valueTop + KPI_VALUE_ROW_H)
        val numP = paint(FS_KPI_VALUE, tint, bold = true)
        val unitP = paint(FS_KPI_UNIT, COLOR_META)
        val (num, unit) = splitValueUnit(kv.value)
        val inlineUnit = unit.isNotEmpty() && unit.length <= KPI_UNIT_INLINE_MAX
        val unitW = if (inlineUnit) unitP.measureText(unit) + 12f else 0f
        val numText = truncateToWidth(num, (valueClip.width() - unitW).coerceAtLeast(0f), numP)
        val valueBaseline = fitBaseline(valueTop, KPI_VALUE_ROW_H.toFloat(), numP, minOf(valueClip.bottom, limitBottom))
        drawClipped(cv, numText, innerLeft, valueBaseline, valueClip, numP)
        if (inlineUnit) {
            val ux = innerLeft + numP.measureText(numText) + 12f
            val unitClip = RectF(ux, valueTop, innerRight, valueClip.bottom)
            if (unitClip.width() > 0f) {
                drawClipped(cv, unit, ux, valueBaseline, unitClip, unitP)
            }
        }
        var nextTop = valueTop + KPI_VALUE_ROW_H
        if (!inlineUnit && unit.isNotEmpty()) {
            // 单位行：整行给单位，最多一行（超长自动省略），永远落在单元内
            val unitTop = nextTop + KPI_UNIT_GAP
            val unitBottom = minOf(unitTop + KPI_UNIT_ROW_H, limitBottom)
            val unitClip = RectF(innerLeft, unitTop, innerRight, unitTop + KPI_UNIT_ROW_H)
            if (unitClip.width() > 0f && unitBottom > unitTop) {
                val unitText = truncateToWidth(unit, unitClip.width(), unitP)
                drawClipped(
                    cv, unitText, innerLeft,
                    fitBaseline(unitTop, KPI_UNIT_ROW_H.toFloat(), unitP, unitBottom),
                    unitClip, unitP,
                )
            }
            nextTop = unitTop + KPI_UNIT_ROW_H
        }

        // ---- 份额进度条：数值本身是百分比时才画（有参照才叫进度）----
        val share = shareFraction(kv.value)
        if (share != null) {
            val barTop = nextTop + KPI_SHARE_GAP
            val barH = KPI_SHARE_BAR_H.toFloat()
            val track = RectF(innerLeft, barTop, innerRight, barTop + barH)
            if (track.bottom <= limitBottom && track.width() > 0f) {
                cv.drawRoundRect(track, barH / 2f, barH / 2f, shapePaint(COLOR_TRACK))
                val fillW = (track.width() * share).coerceAtLeast(barH)
                val fill = RectF(innerLeft, barTop, innerLeft + fillW, barTop + barH)
                cv.drawRoundRect(fill, barH / 2f, barH / 2f, shapePaint(tint))
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
            shapePaint(if (medal) valueColor(rank) else blendOnWhite(accent, 0x1C)),
        )
        val p = paint(FS_RANK, if (medal) Color.WHITE else accent, bold = true)
        val s = rank.toString()
        val tx = rect.centerX() - p.measureText(s) / 2f
        drawClipped(cv, s, tx, fitBaseline(top, box, p, rect.bottom), rect, p)
    }

    /** 单行键值（未聚合成 KPI 网格时）：key 左、value 右，两边都有限宽 */
    private fun drawKeyValueRow(
        cv: Canvas,
        u: Block.KeyValue,
        rowTop: Float,
        rowH: Float,
        limitBottom: Float,
    ) {
        val tint = semanticColor(u.key) ?: COLOR_TITLE
        val keyP = paint(FS_ROW, COLOR_META)
        val valueP = paint(FS_ROW, tint, bold = true)
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

    /**
     * 环形图 + 图例：环在左、图例在右（色块 + 名称 + 占比 + 原值）。
     * 环用 STROKE 弧线画，不建 Path、不建位图；扇区之间留小缝，比严丝合缝更像图表。
     */
    private fun drawDonut(cv: Canvas, d: Block.Donut, rowTop: Float, limitBottom: Float) {
        val total = d.slices.sumOf { it.count }
        if (total <= 0L) return

        val size = DONUT_SIZE.toFloat()
        val ringLeft = CONTENT_LEFT.toFloat()
        val ringTop = rowTop + (donutHeight(d) - DONUT_SIZE) / 2f
        val ring = RectF(ringLeft, ringTop, ringLeft + size, ringTop + size)

        val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = DONUT_RING_W
            strokeCap = Paint.Cap.BUTT
        }
        // 底轨：给不满的占比一个 100% 参照，也让环在任何数据下都完整
        ringP.color = COLOR_TRACK
        cv.drawArc(ring, 0f, 360f, false, ringP)

        var start = -90f
        d.slices.forEachIndexed { i, s ->
            val sweep = (s.count.toDouble() / total.toDouble() * 360.0).toFloat()
            val gap = if (d.slices.size > 1) DONUT_GAP_ANGLE else 0f
            ringP.shader = null
            ringP.color = DONUT_PALETTE[i % DONUT_PALETTE.size]
            cv.drawArc(ring, start + gap / 2f, (sweep - gap).coerceAtLeast(1f), false, ringP)
            start += sweep
        }

        // ---- 环心：合计（展示用的是报告给出的数值之和，不做任何改写）----
        val inner = RectF(
            ringLeft + DONUT_RING_W, ringTop + DONUT_RING_W,
            ringLeft + size - DONUT_RING_W, ringTop + size - DONUT_RING_W,
        )
        val sumP = paint(FS_KPI_VALUE, COLOR_TITLE, bold = true)
        val capP = paint(FS_KPI_LABEL, COLOR_META)
        val sumText = truncateToWidth(total.toString(), inner.width(), sumP)
        val sumH = FS_KPI_VALUE + 12f
        val capH = FS_KPI_LABEL + 8f
        val sumTop = inner.centerY() - (sumH + capH) / 2f
        val sumClip = RectF(inner.left, sumTop, inner.right, sumTop + sumH)
        drawClipped(
            cv, sumText, inner.centerX() - sumP.measureText(sumText) / 2f,
            fitBaseline(sumTop, sumH, sumP, minOf(sumClip.bottom, limitBottom)), sumClip, sumP,
        )
        val capClip = RectF(inner.left, sumClip.bottom, inner.right, sumClip.bottom + capH)
        val capText = DONUT_SUM_LABEL
        drawClipped(
            cv, capText, inner.centerX() - capP.measureText(capText) / 2f,
            fitBaseline(capClip.top, capH, capP, minOf(capClip.bottom, limitBottom)), capClip, capP,
        )

        // ---- 图例：色块 + 名称 + 占比 + 原值（右对齐成一条干净的数值列）----
        val legendLeft = (CONTENT_LEFT + DONUT_SIZE + DONUT_LEGEND_GAP).toFloat()
        val labelP = paint(FS_ROW, COLOR_BODY)
        val valueP = paint(FS_ROW, COLOR_TITLE, bold = true)
        val pctP = paint(FS_SMALL, COLOR_META)
        val swatchH = DONUT_LEGEND_SWATCH.toFloat()
        d.slices.forEachIndexed { i, s ->
            val legendTop = rowTop + i * DONUT_LEGEND_ROW_H
            val rowH = DONUT_LEGEND_ROW_H.toFloat()
            val centerY = legendTop + rowH / 2f

            cv.drawRoundRect(
                RectF(
                    legendLeft, centerY - swatchH / 2f,
                    legendLeft + swatchH, centerY + swatchH / 2f,
                ),
                10f, 10f, shapePaint(DONUT_PALETTE[i % DONUT_PALETTE.size]),
            )

            val pctText = formatPercent(s.count, total)
            val valueW = valueP.measureText(s.value)
            val pctW = pctP.measureText(pctText)
            val valueLeft = CONTENT_RIGHT.toFloat() - valueW
            val pctLeft = valueLeft - DONUT_LEGEND_VALUE_GAP - pctW
            val labelLeft = legendLeft + swatchH + DONUT_LEGEND_SWATCH_GAP
            val labelAvailW = pctLeft - CELL_INSET - labelLeft
            val legendBaseline = fitBaseline(legendTop, rowH, labelP, minOf(legendTop + rowH, limitBottom))
            if (labelAvailW > 0f) {
                val labelText = truncateToWidth(s.label, labelAvailW, labelP)
                drawClipped(
                    cv, labelText, labelLeft, legendBaseline,
                    RectF(labelLeft, legendTop, labelLeft + labelAvailW, legendTop + rowH), labelP,
                )
            }
            if (pctW > 0f) {
                drawClipped(
                    cv, pctText, pctLeft, legendBaseline,
                    RectF(pctLeft - 2f, legendTop, valueLeft - DONUT_LEGEND_VALUE_GAP, legendTop + rowH), pctP,
                )
            }
            drawClipped(
                cv, s.value, valueLeft, legendBaseline,
                RectF(valueLeft - 2f, legendTop, CONTENT_RIGHT.toFloat(), legendTop + rowH), valueP,
            )
        }
    }

    /**
     * 柱状图：左侧刻度槽 + 3 条网格线 + 柱体（顶亮底实的渐变，峰值加深描边并标数值）
     * + 均值虚线 + x 轴刻度。柱与标签都被限制在轴内/自己的槽宽内，绝不互相盖压。
     */
    private fun drawColumnChart(
        cv: Canvas,
        chart: Block.ColumnChart,
        rowTop: Float,
        accent: Int,
        limitBottom: Float,
    ) {
        val bars = chart.bars
        if (bars.isEmpty()) return
        val maxV = bars.maxOf { it.count }.coerceAtLeast(1L)
        val plotLeft = (CONTENT_LEFT + CHART_GUTTER_W).toFloat()
        val plotRight = CONTENT_RIGHT.toFloat()
        val plotTop = rowTop + CHART_TOP_PAD
        val baseY = rowTop + CHART_TOP_PAD + CHART_BAR_AREA_H
        val areaH = CHART_BAR_AREA_H.toFloat()

        cv.save()
        cv.clipRect(
            CONTENT_LEFT.toFloat(), rowTop,
            CONTENT_RIGHT.toFloat(), minOf(rowTop + COLUMN_CHART_H, limitBottom),
        )

        // ---- 网格线 + 左侧刻度（3 档：0 / 半 / 满）----
        val gridP = shapePaint(COLOR_RULE)
        val tickP = paint(FS_TICK, COLOR_META)
        for (k in 0..2) {
            val gy = baseY - areaH * (k / 2f)
            if (k > 0) cv.drawRect(RectF(plotLeft, gy, plotRight, gy + 2f), gridP)
            val tickText = ((maxV * k) / 2).toString()
            val tickW = tickP.measureText(tickText)
            val tickClip = RectF(CONTENT_LEFT.toFloat(), gy - 28f, plotLeft - 16f, gy + 28f)
            drawClipped(
                cv, tickText, plotLeft - 16f - tickW,
                fitBaseline(gy - 28f, 56f, tickP, minOf(tickClip.bottom, limitBottom)),
                tickClip, tickP,
            )
        }
        // 基线
        cv.drawRect(RectF(plotLeft, baseY, plotRight, baseY + 2f), gridP)

        // ---- 柱体 ----
        val n = bars.size
        val slot = (plotRight - plotLeft) / n
        val barW = (slot * 0.62f).coerceIn(CHART_BAR_MIN_W, CHART_BAR_MAX_W)
        val peakIndex = bars.indexOfFirst { it.count == maxV }.coerceAtLeast(0)
        bars.forEachIndexed { i, b ->
            if (b.count <= 0L) return@forEachIndexed
            val h = areaH * (b.count.toFloat() / maxV.toFloat())
            val cx = plotLeft + slot * i + slot / 2f
            val rect = RectF(cx - barW / 2f, baseY - h, cx + barW / 2f, baseY - CHART_BAR_BASE_GAP)
            cv.drawRoundRect(rect, barW / 2f, barW / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, rect.top, 0f, rect.bottom,
                    intArrayOf(lighten(accent), accent),
                    null, Shader.TileMode.CLAMP,
                )
            })
            if (i == peakIndex) {
                cv.drawRoundRect(rect, barW / 2f, barW / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                })
            }
        }

        // ---- 峰值数值：标在峰柱正上方（顶部留白就是给它准备的）----
        val peakBar = bars[peakIndex]
        val peakH = areaH * (peakBar.count.toFloat() / maxV.toFloat())
        val peakCx = plotLeft + slot * peakIndex + slot / 2f
        val peakP = paint(FS_SMALL, accent, bold = true)
        val peakText = truncateToWidth(peakBar.value, plotRight - plotLeft, peakP)
        val peakTop = baseY - peakH - 12f - FS_SMALL
        drawClipped(
            cv, peakText, peakCx - peakP.measureText(peakText) / 2f,
            fitBaseline(peakTop, FS_SMALL + 12f, peakP, minOf(baseY - 4f, limitBottom)),
            RectF(plotLeft, rowTop, plotRight, baseY), peakP,
        )

        // ---- 均值虚线（手绘短划，不引入 DashPathEffect）----
        val avg = bars.sumOf { it.count }.toDouble() / n.toDouble()
        val avgY = baseY - areaH * (avg / maxV.toDouble()).toFloat()
        if (avgY > plotTop - 1f && avgY < baseY - 1f) {
            drawDashedHLine(cv, plotLeft, plotRight, avgY, shapePaint(withAlpha(COLOR_WARN, 0xC0)))
            // 标注贴着虚线、靠左放（左端通常是低柱，不会被柱体压住）；上方放不下就改放下方
            val avgP = paint(FS_TICK, COLOR_WARN)
            val avgText = truncateToWidth("$AVG_LABEL ${Math.round(avg)}", plotRight - plotLeft, avgP)
            val labelTop = if (avgY - CHART_AVG_LABEL_GAP >= plotTop) avgY - CHART_AVG_LABEL_GAP else avgY + 4f
            val avgClip = RectF(plotLeft, labelTop, plotRight, labelTop + CHART_AVG_LABEL_H)
            drawClipped(
                cv, avgText, plotLeft + 6f,
                fitBaseline(labelTop, CHART_AVG_LABEL_H, avgP, minOf(avgClip.bottom, limitBottom)),
                avgClip, avgP,
            )
        }

        // ---- x 轴刻度：每 CHART_LABEL_EVERY 根标一个，最后一根一定标 ----
        val xP = paint(FS_TICK, COLOR_META)
        val labelY = baseY + CHART_AXIS_GAP
        val labelH = (CHART_AXIS_H - CHART_AXIS_GAP).coerceAtLeast(20)
        bars.forEachIndexed { i, b ->
            if (i % CHART_LABEL_EVERY != 0 && i != n - 1) return@forEachIndexed
            val cx = plotLeft + slot * i + slot / 2f
            val text = truncateToWidth(b.label, slot - 8f, xP)
            drawClipped(
                cv, text, cx - xP.measureText(text) / 2f,
                fitBaseline(labelY, labelH.toFloat(), xP, minOf(labelY + labelH, limitBottom)),
                RectF(cx - slot / 2f, labelY, cx + slot / 2f, labelY + labelH), xP,
            )
        }

        cv.restore()
    }

    /** 标签云：把词频行排成一排排圆角小标签（换行结果由布局阶段算好，这里只负责画） */
    private fun drawChipCloud(
        cv: Canvas,
        chipRows: List<List<String>>,
        rowTop: Float,
        accent: Int,
        limitBottom: Float,
    ) {
        val p = paint(FS_ROW, COLOR_BODY)
        val fillP = shapePaint(blendOnWhite(accent, 0x14))
        val edgeP = shapePaint(blendOnWhite(accent, 0x48), stroke = true, strokeWidth = 2f)
        var lineTop = rowTop
        for (line in chipRows) {
            var x = CONTENT_LEFT.toFloat()
            for (item in line) {
                val w = chipWidth(item, p)
                val rect = RectF(x, lineTop, x + w, lineTop + CHIP_H)
                cv.drawRoundRect(rect, CHIP_H / 2f, CHIP_H / 2f, fillP)
                cv.drawRoundRect(rect, CHIP_H / 2f, CHIP_H / 2f, edgeP)
                val clip = RectF(rect.left + CHIP_PAD_H, rect.top, rect.right - CHIP_PAD_H, rect.bottom)
                if (clip.width() > 0f) {
                    val text = truncateToWidth(item, clip.width(), p)
                    drawClipped(
                        cv, text, clip.left,
                        fitBaseline(rect.top, CHIP_H.toFloat(), p, minOf(rect.bottom, limitBottom)),
                        clip, p,
                    )
                }
                x += w + CHIP_GAP
            }
            lineTop += CHIP_H + CHIP_LINE_GAP
        }
    }

    /** 页脚：顶部渐变细线 + 左品牌 + 右页码，底部再加一条品牌渐变条当水印 */
    private fun drawFooter(cv: Canvas, top: Int) {
        cv.drawRect(
            RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), top + 3f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                    intArrayOf(
                        withAlpha(COLOR_ACCENT, 0x00),
                        withAlpha(COLOR_ACCENT, 0x4D),
                        withAlpha(COLOR_ACCENT2, 0x00),
                    ),
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
        val pageText = FOOTER_PAGE_TEXT
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
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    /** 手绘虚线（不用 DashPathEffect）：逐段画短矩形，长度精确、不依赖渲染管线 */
    private fun drawDashedHLine(cv: Canvas, left: Float, right: Float, y: Float, p: Paint) {
        var x = left
        while (x < right) {
            val seg = minOf(CHART_DASH_W, right - x)
            cv.drawRect(x, y, x + seg, y + CHART_DASH_H, p)
            x += CHART_DASH_W + CHART_DASH_GAP
        }
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

    /** 按测量宽度换行（不丢字），空段落保留为空行，并对段落收尾做孤字控制 */
    private fun wrapLines(text: String, maxW: Float, p: Paint): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                out.add("")
                continue
            }
            val paraStart = out.size
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
            rebalanceOrphans(out, paraStart, maxW, p)
        }
        return out
    }

    /**
     * 段落收尾的「孤字」控制：末行只剩一两个字符时，从上一行尾部挪 1 个字符下来，
     * 让最后一行不再是孤零零的一个字（中文排版的老毛病，CJK 尤其扎眼）。
     * 只动相邻两行、只搬一个字符、绝不拆代理对，也绝不把上一行搬空。
     */
    private fun rebalanceOrphans(out: MutableList<String>, paraStart: Int, maxW: Float, p: Paint) {
        if (out.size - paraStart < 2) return
        val last = out[out.size - 1]
        if (last.length < 1 || last.length > ORPHAN_MAX_CHARS) return
        val prev = out[out.size - 2]
        var move = 1
        if (prev.length >= 2 && Character.isHighSurrogate(prev[prev.length - 1])) move = 2
        if (prev.length <= move) return
        val merged = prev.substring(prev.length - move) + last
        if (p.measureText(merged) > maxW) return
        out[out.size - 2] = prev.substring(0, prev.length - move)
        out[out.size - 1] = merged
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

    /** 占比文本（一位小数，例如 "68.2%"）：只用于展示，不参与任何判定 */
    private fun formatPercent(part: Long, total: Long): String {
        if (total <= 0L) return ""
        val tenth = Math.round(part.toDouble() * 1000.0 / total.toDouble())
        return "${tenth / 10}.${tenth % 10}%"
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
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    /**
     * 把 color 以 alpha(0..255) 的强度混到纯白上，得到不透明色。
     * 卡片浅底、浅描边、标签云底色都用它：半透明色叠在页面渐变背景上会随位置变色，
     * 混白后再画就与背景无关，转发到不同 App 里观感一致。
     */
    private fun blendOnWhite(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val r = (255 - (255 - Color.red(color)) * a / 255).coerceIn(0, 255)
        val g = (255 - (255 - Color.green(color)) * a / 255).coerceIn(0, 255)
        val b = (255 - (255 - Color.blue(color)) * a / 255).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lighten(color: Int): Int = Color.rgb(
        (Color.red(color) + 40).coerceAtMost(255),
        (Color.green(color) + 40).coerceAtMost(255),
        (Color.blue(color) + 40).coerceAtMost(255),
    )

    private fun reportDateText(): String =
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA).format(Date())
}
