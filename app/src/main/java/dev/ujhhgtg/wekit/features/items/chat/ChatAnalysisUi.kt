package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Memory
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.composables.icons.materialsymbols.outlined.Sort
import com.composables.icons.materialsymbols.outlined.Star
import com.composables.icons.materialsymbols.outlined.Sunny
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

/**
 * 聊天记录分析 —— Compose UI 组件（排版规范版 v2）
 *
 * 全部弹窗基于 showComposeDialog + AlertDialogContent（WeKit 标准）。
 *
 * ── 三条排版铁律（本文件所有弹窗都遵守）────────────────────────────────
 *  1. **绝不越出窗口**：任何弹窗都用 [DialogBudget] 先算出「窗口可用高度」，
 *     再以此同时约束卡片高度（Surface）与正文滚动区高度（LazyColumn）。
 *     正文一律内部滚动，按钮行永远留在可视区内。
 *  2. **绝不越出容器**：所有文本都带 maxLines + TextOverflow.Ellipsis
 *     （长文本该换行的换行、该省略的省略），所有 Row 的宽度都由 weight /
 *     fillMaxWidth 分配，不靠"内容自己撑"，因此不存在把兄弟节点顶出去的情况。
 *  3. **数值只有一个来源**：圆角、间距、字号、进度条厚度全部取自下文的
 *     设计规范常量（Design Tokens），不在各处写魔法数字。
 */
internal object ChatAnalysisUi {

    // ==================================================================
    // 一、设计规范（Design Tokens）—— 全弹窗唯一的数值来源
    // ==================================================================

    // ---- 圆角梯度：卡片 20 / 卡片内块 16 / 胶囊 10 / 徽章 8 ----
    private val RadiusCard = 20.dp
    private val RadiusInner = 16.dp
    private val RadiusChip = 10.dp
    private val RadiusBadge = 8.dp

    // ---- 间距梯度（4dp 基准）：2 / 4 / 6 / 8 / 10 / 12 / 16 / 20 ----
    private val Space2 = 2.dp
    private val Space4 = 4.dp
    private val Space6 = 6.dp
    private val Space8 = 8.dp
    private val Space10 = 10.dp
    private val Space12 = 12.dp
    private val Space16 = 16.dp

    // ---- 结构节奏 ----
    /** 卡片四边内边距（上下左右一致，保证卡片内部左右留白对称） */
    private val CardPad = Space16

    /** 卡片内「标题 → 正文」的间距 */
    private val CardInnerGap = Space12

    /** 相邻分节卡片之间的留白（比行距大一档，分节感来自留白而不是线） */
    private val SectionGap = Space12

    /** 胶囊之间的留白 */
    private val ChipGap = Space6

    // ---- 组件尺寸 ----
    /** KPI 数字卡片的最小高度（保证一行两格高度一致） */
    private val KpiCellMinH = 92.dp

    /** KPI 网格的列/行间距 */
    private val KpiGap = Space10

    /** KPI 网格降为单列的宽度阈值（窄屏 / 大字号下两列会挤到读不清） */
    private val KpiTwoColumnMinW = 320.dp

    /** 进度条厚度（自绘，圆角不会被压平） */
    private val BarThickness = 10.dp

    /** 触控友好的图标按钮边长（44dp ≥ 无障碍最小触控尺寸） */
    private val IconTouchSize = 44.dp

    /** 报告弹窗正文区的兜底高度（无高度预算上下文时使用） */
    private val DialogBodyFallback = 360.dp

    // ---- 弹窗高度预算 ----
    /** 弹窗总高上限 = 可用高度 × 0.9（留出上下呼吸空间，绝不顶满） */
    private const val DialogScreenFraction = 0.9f

    /** 预留：标题 + 分隔线 + 按钮行 + 内边距（实测 ~160dp，留足余量） */
    private val DialogChrome = 184.dp

    /** 弹窗总高下限 / 正文高度下限（超小屏也要能看能点） */
    private val MinDialogHeight = 320.dp
    private val MinBodyHeight = 160.dp

    /** 判断宿主约束是否"无界"的阈值（超过即视为无界，退回屏幕高度估算） */
    private const val UnboundedDp = 4000f

    // ---- 语义配色（与 PNG 导出保持同一套观感）----
    private val RankGold = Color(0xFFD89A16)
    private val RankSilver = Color(0xFF7F8C9B)
    private val RankBronze = Color(0xFFB9754A)

    /** 统一卡片描边：浅色细边，深浅主题下都规整 */
    private val CardStroke: BorderStroke
        @Composable
        get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

    /** 卡片内分隔线（比描边更淡，只做行间暗示，不抢视觉） */
    private val RowDivider: Color
        @Composable
        get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    /**
     * 弹窗高度仲裁 —— 本文件所有弹窗的统一入口。
     *
     * 为什么需要：AlertDialogContent 的正文区若用固定 420/440dp 上限，
     * 「正文 + 标题 + 分隔线 + 按钮行」在小屏 / 横屏 / 系统大字号下会高于窗口可用高度，
     * 于是内容被窗口裁掉、按钮被顶出可视区（用户反馈的"很紧凑、经常超出画布"）。
     *
     * 做法：取「真实窗口约束」与「屏幕高度」的较小值 × 0.9 作为弹窗总高上限，
     * 再扣掉弹窗框架的预留高度，得到正文可用高度。因为 [totalDp] 直接约束 Surface，
     * 正文滚动区又被父级二次夹紧，所以任何字号下都不会越出窗口。
     */
    @Composable
    private fun DialogBudget(content: @Composable (totalDp: Dp, bodyDp: Dp) -> Unit) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val screen = LocalConfiguration.current.screenHeightDp.dp
            val window = if (maxHeight.value < UnboundedDp) maxHeight else screen
            val total = (minOf(screen, window) * DialogScreenFraction).coerceAtLeast(MinDialogHeight)
            val body = (total - DialogChrome).coerceAtLeast(MinBodyHeight)
            content(total, body)
        }
    }

    // ==================================================================
    // 二、通用小组件
    // ==================================================================

    /**
     * 弹窗标题块：主标题（titleLarge/Bold）+ 可选副标题（bodySmall/次要色）。
     * 主标题限 2 行省略、副标题自由换行，长会话名不会把标题区撑爆。
     */
    @Composable
    private fun DialogTitle(title: String, subtitle: String? = null) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(Space6))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * 分节标题。
     *
     * @param index 可选的序号（从 1 开始）。报告各分节带序号后，读者能一眼看出结构，
     *              设置页的三大块也用同一套序号视觉，全弹窗观感一致。
     */
    @Composable
    fun SectionHeader(
        title: String,
        accent: Color = MaterialTheme.colorScheme.primary,
        index: Int? = null,
    ) {
        SectionHeaderRow(title, accent, Modifier.padding(top = SectionGap), index = index)
    }

    /**
     * 分节标题的实际渲染：3dp 竖条（accent）+ 序号徽章 + titleSmall/Bold。
     * 竖条先 clip 再 background，保证圆角外不会溢出颜色。
     *
     * @param index 分节序号（从 1 开始）。传 null 表示不是报告分节（如设置页的小标题），
     *              不显示序号徽章 —— 报告里加了序号后，读者能一眼看出共有几大块、
     *              现在读到第几块，长篇报告的"结构感"明显更强。
     */
    @Composable
    private fun SectionHeaderRow(
        title: String,
        accent: Color,
        modifier: Modifier = Modifier,
        index: Int? = null,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(Space8))
            if (index != null) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(RadiusBadge))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        index.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(Space6))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.5.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    fun BigButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
        ) { content() }
    }

    /**
     * 元信息胶囊（时段 / 条数 / 模型名这类次级信息）：小字号 + 强调色浅底，
     * 与标题形成明确的字号层级。宽高都由 FlowRow 约束，长文本按测量宽度省略。
     */
    @Composable
    private fun MetaChip(
        text: String,
        accent: Color = MaterialTheme.colorScheme.primary,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(RadiusChip))
                .background(accent.copy(alpha = 0.13f))
                .padding(horizontal = Space8, vertical = Space4)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * 设置页 / 列表页的「分组卡片」：标题（带序号）+ 内容，统一圆角与描边。
     * 把散落的开关收进卡片后，弹窗从"一长条控件流"变成"几块功能"，层级一眼可辨。
     */
    @Composable
    private fun GroupCard(
        title: String,
        index: Int,
        accent: Color = MaterialTheme.colorScheme.primary,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = MaterialTheme.colorScheme.surfaceBright,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                SectionHeaderRow(
                    title = title,
                    accent = accent,
                    index = index,
                    modifier = Modifier.padding(
                        start = CardPad,
                        end = CardPad,
                        top = CardPad,
                        bottom = CardInnerGap,
                    ),
                )
                content()
                Spacer(Modifier.height(Space4))
            }
        }
    }

    /** 卡片内的行间分隔线（左右与内容对齐） */
    @Composable
    private fun InCardDivider() {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Space16),
            color = RowDivider,
        )
    }

    /** 排名徽章：Top1-3 金银铜，其余用主色浅底；数字固定宽度，不会挤压昵称以外的空间 */
    @Composable
    private fun RankBadge(rank: Int, accent: Color) {
        val medal = rank in 1..3
        val container = when (rank) {
            1 -> RankGold
            2 -> RankSilver
            3 -> RankBronze
            else -> accent.copy(alpha = 0.16f)
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(RadiusBadge))
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.Bold,
                color = if (medal) Color.White else accent,
                maxLines = 1,
            )
        }
    }

    // ==================================================================
    // 三、时间范围选择
    // ==================================================================

    private val RangeLabels = listOf("今天", "昨天", "本周", "上周", "本月", "上月")

    private val RangeIcons = listOf(
        MaterialSymbols.Outlined.Sunny,
        MaterialSymbols.Outlined.Schedule,
        MaterialSymbols.Outlined.Star,
        MaterialSymbols.Outlined.History,
        MaterialSymbols.Outlined.Tune,
        MaterialSymbols.Outlined.Refresh,
    )

    private val RangeHints = listOf(
        "今天 00:00 至今",
        "昨天全天",
        "本周一 00:00 至今",
        "上周一至上周日",
        "本月 1 日至今",
        "上个月整月",
    )

    @Composable
    fun RangePickerContent(
        sessionName: String,
        onPick: (Int) -> Unit,
        onSettings: () -> Unit,
        onClose: () -> Unit,
    ) {
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    DialogTitle(
                        title = "分析时间范围",
                        subtitle = "本地统计与 AI 总结都只使用所选范围内的纯文本消息。",
                    )
                },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp),
                        verticalArrangement = Arrangement.spacedBy(Space2),
                    ) {
                        itemsIndexed(RangeLabels) { index, label ->
                            BaseWidget(
                                icon = RangeIcons[index],
                                iconPlaceholder = true,
                                title = label,
                                description = "统计并总结「$sessionName」该时段内的纯文本聊天记录 · ${RangeHints[index]}",
                                onClick = { onPick(index) },
                                trailingDivider = true,
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        item {
                            InCardDivider()
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Settings,
                                iconPlaceholder = true,
                                title = "⚙️ 设置",
                                description = "功能开关 / 分析参数 / AI 模型管理",
                                onClick = onSettings,
                                trailingDivider = true,
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    Button(onClose) { Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            )
        }
    }

    // ==================================================================
    // 四、设置
    // ==================================================================

    @Composable
    fun SettingsContent(
        features: Set<String>,
        maxCount: Int,
        sampleLimit: Int,
        lineMax: Int,
        transcriptMaxChars: Int,
        selectedModelName: String,
        onToggleFeature: (String, Boolean) -> Unit,
        onEditMaxCount: () -> Unit,
        onEditSampleLimit: () -> Unit,
        onEditLineMax: () -> Unit,
        onEditTranscriptMaxChars: () -> Unit,
        onModelManager: () -> Unit,
        onTestModel: () -> Unit,
        onClose: () -> Unit,
    ) {
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    DialogTitle(title = "聊天记录分析 · 设置")
                },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp),
                        verticalArrangement = Arrangement.spacedBy(SectionGap),
                    ) {
                        item {
                            GroupCard(title = "功能开关", index = 1) {
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Smart_toy,
                                    iconPlaceholder = true,
                                    title = "AI 总结",
                                    description = "用大模型总结该时段聊天内容",
                                    checked = ChatAnalysisEngine.FEATURE_AI in features,
                                    onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_AI, it) },
                                )
                                InCardDivider()
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Tune,
                                    iconPlaceholder = true,
                                    title = "本地统计",
                                    description = "核心指标 / 载体偏好 / 活跃频次 / 高频词 / 情绪指纹",
                                    checked = ChatAnalysisEngine.FEATURE_STATS in features,
                                    onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_STATS, it) },
                                )
                                InCardDivider()
                                SwitchWidget(
                                    icon = MaterialSymbols.Outlined.Sort,
                                    iconPlaceholder = true,
                                    title = "发言排行",
                                    description = "发言对比 / 群成员发言 Top10",
                                    checked = ChatAnalysisEngine.FEATURE_RANK in features,
                                    onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_RANK, it) },
                                )
                            }
                        }
                        item {
                            GroupCard(title = "分析参数", index = 2) {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Tune,
                                    iconPlaceholder = true,
                                    title = "分析条数上限",
                                    description = if (maxCount <= 0) {
                                        "0 = 全部（读该时段所有消息，越大越慢）"
                                    } else {
                                        "当前：$maxCount 条（0 = 全部）"
                                    },
                                    onClick = onEditMaxCount,
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                                InCardDivider()
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Tune,
                                    iconPlaceholder = true,
                                    title = "抽样上限",
                                    description = "喂给 AI 的最大文本条数（当前：$sampleLimit）；大群建议 5000~20000",
                                    onClick = onEditSampleLimit,
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                                InCardDivider()
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Tune,
                                    iconPlaceholder = true,
                                    title = "单条文本上限",
                                    description = "一条消息喂给 AI 的最多字数（当前：$lineMax 字）",
                                    onClick = onEditLineMax,
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                                InCardDivider()
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Tune,
                                    iconPlaceholder = true,
                                    title = "喂给 AI 的文本上限",
                                    description = "整段记录的总字数上限（当前：$transcriptMaxChars 字）；" +
                                        "模型上下文小就要调小，否则服务端会报上下文超限",
                                    onClick = onEditTranscriptMaxChars,
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                            }
                        }
                        item {
                            GroupCard(title = "AI 模型", index = 3) {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Memory,
                                    iconPlaceholder = true,
                                    title = "当前模型",
                                    description = selectedModelName.ifEmpty { "未配置" },
                                    onClick = onModelManager,
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Chevron_right,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                                InCardDivider()
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Settings,
                                    iconPlaceholder = true,
                                    title = "模型管理",
                                    description = "新增 / 编辑 / 删除 / 选择（支持多套 baseURL + APIKey）",
                                    onClick = onModelManager,
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                                InCardDivider()
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Bolt,
                                    iconPlaceholder = true,
                                    title = "测试连接",
                                    description = "验证当前模型能否正常请求（拉取模型列表 + 最小对话）",
                                    onClick = onTestModel,
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Refresh, null) },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    Button(onClose) { Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            )
        }
    }

    // ==================================================================
    // 五、模型管理
    // ==================================================================

    @Composable
    fun ModelManagerContent(
        models: List<AiModelConfig>,
        selectedName: String,
        onSelect: (AiModelConfig) -> Unit,
        onEdit: (AiModelConfig) -> Unit,
        onDelete: (AiModelConfig) -> Unit,
        onAdd: () -> Unit,
        onClose: () -> Unit,
    ) {
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    DialogTitle(title = "AI 模型管理")
                },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp),
                    ) {
                        if (models.isEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(RadiusCard),
                                    color = MaterialTheme.colorScheme.surfaceBright,
                                    border = CardStroke,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "还没有模型配置，点击下方「新增模型」添加。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(CardPad),
                                    )
                                }
                            }
                        }
                        itemsIndexed(models) { _, model ->
                            val selected = model.name == selectedName
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Memory,
                                iconPlaceholder = true,
                                title = if (selected) "✓ ${model.name}" else model.name,
                                description = "${model.model}\n${model.baseUrl}",
                                selected = selected,
                                onClick = { onSelect(model) },
                                trailingDivider = true,
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selected) {
                                            Icon(
                                                MaterialSymbols.Outlined.Check_circle,
                                                contentDescription = "当前使用",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(end = Space4),
                                            )
                                        }
                                        IconButton(
                                            onClick = { onEdit(model) },
                                            modifier = Modifier.size(IconTouchSize),
                                        ) {
                                            Icon(MaterialSymbols.Outlined.Edit, "编辑")
                                        }
                                        IconButton(
                                            onClick = { onDelete(model) },
                                            modifier = Modifier.size(IconTouchSize),
                                        ) {
                                            Icon(
                                                MaterialSymbols.Outlined.Delete,
                                                "删除",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    Button(onClose) { Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                confirmButton = {
                    Button(onAdd) {
                        Icon(MaterialSymbols.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space6))
                        Text("新增模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        }
    }

    // ==================================================================
    // 六、模型编辑
    // ==================================================================

    @Composable
    fun ModelEditContent(
        model: AiModelConfig,
        onSave: (AiModelConfig) -> Unit,
        onTest: () -> Unit,
        onClose: () -> Unit,
    ) {
        var name by remember { mutableStateOf(model.name) }
        var baseUrl by remember { mutableStateOf(model.baseUrl) }
        var apiKey by remember { mutableStateOf(model.apiKey) }
        var modelId by remember { mutableStateOf(model.model) }
        var path by remember { mutableStateOf(model.path) }
        var showKey by remember { mutableStateOf(false) }

        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    DialogTitle(if (model.name.isBlank()) "新增模型" else "编辑模型")
                },
                text = {
                    // 输入区内部滚动：横屏时也不会把输入框挤成一条缝
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp),
                        verticalArrangement = Arrangement.spacedBy(Space10),
                    ) {
                        item {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("模型名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("Base URL") },
                                placeholder = { Text("https://api.deepseek.com/v1") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    label = { Text("API Key") },
                                    singleLine = true,
                                    visualTransformation = if (showKey) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { showKey = !showKey },
                                    modifier = Modifier.size(IconTouchSize),
                                ) {
                                    Icon(
                                        if (showKey) MaterialSymbols.Outlined.Visibility_off
                                        else MaterialSymbols.Outlined.Visibility,
                                        "显示/隐藏",
                                    )
                                }
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = modelId,
                                onValueChange = { modelId = it },
                                label = { Text("模型 ID") },
                                placeholder = { Text("deepseek-chat / gpt-4o-mini") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = path,
                                onValueChange = { path = it },
                                label = { Text("请求路径（默认 /chat/completions）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                dismissButton = {
                    Button(onClose) { Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                confirmButton = {
                    // 等分宽度 + 省略号：任何字号下两个动作按钮都不会顶出弹窗
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space8),
                    ) {
                        Button(
                            onTest,
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                        ) {
                            Icon(MaterialSymbols.Outlined.Bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Space6))
                            Text("测试", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            {
                                onSave(
                                    AiModelConfig(
                                        name = name.trim().ifEmpty { "未命名模型" },
                                        baseUrl = baseUrl.trim(),
                                        apiKey = apiKey.trim(),
                                        model = modelId.trim(),
                                        path = path.trim().ifEmpty { "/chat/completions" },
                                    )
                                )
                            },
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                        ) {
                            Icon(MaterialSymbols.Outlined.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Space6))
                            Text("保存", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
            )
        }
    }

    // ==================================================================
    // 七、报告渲染
    // ==================================================================

    sealed class ReportUnit {
        data class Section(val title: String) : ReportUnit()
        data class BarRow(val label: String, val value: String, val ratio: Float) : ReportUnit()
        data class KeyValue(val key: String, val value: String) : ReportUnit()
        data class TextLine(val text: String) : ReportUnit()
        data class WordChips(val words: List<Pair<String, Int>>) : ReportUnit()
        object Gap : ReportUnit()
    }

    fun parseReport(text: String): List<ReportUnit> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<ReportUnit>()
        for (line in text.split("\n")) {
            val t = line.trim()
            when {
                t.isEmpty() -> out.add(ReportUnit.Gap)
                t.startsWith("【") && t.endsWith("】") -> {
                    // 引擎每个段前已有一个空行（Gap），此处不再额外插 Gap，
                    // 分节间距统一由 SectionHeader / 分节卡片承担。
                    out.add(ReportUnit.Section(t.removeSurrounding("【", "】")))
                }
                t.contains("█") -> {
                    val barLen = t.count { it == '█' }
                    val clean = t.replace("█", "").trim()
                    var label = clean
                    var value = ""
                    // 格式1：排行行 "1. 张三：45 条 ████" → label=排名+昵称, value=45条
                    val rankM = Regex("^(\\d+[.．、]?\\s*.*?)[:：]\\s*(\\d+)\\s*条?$").find(clean)
                    if (rankM != null) {
                        label = rankM.groupValues[1].trim()
                        value = rankM.groupValues[2] + "条"
                    } else {
                        // 格式2/3：载体偏好 "图片 5 ██"、活跃频次 "凌晨0-5点 3 ██" → 最后一个空格分隔
                        val lastSpace = clean.lastIndexOf(' ')
                        if (lastSpace > 0 && clean.substring(lastSpace + 1).trim().all { it.isDigit() }) {
                            label = clean.substring(0, lastSpace).trim()
                            value = clean.substring(lastSpace + 1).trim()
                        }
                    }
                    out.add(ReportUnit.BarRow(label, value, (barLen / 16f).coerceIn(0f, 1f)))
                }
                t.matches(Regex("^([^\\s×]+×\\d+[\\s　]*)+$")) -> {
                    // 高频词行：word×n word×n ...
                    val words = Regex("([^\\s×]+)×(\\d+)").findAll(t)
                        .map { it.groupValues[1] to it.groupValues[2].toInt() }
                        .toList()
                    if (words.isNotEmpty()) out.add(ReportUnit.WordChips(words))
                    else out.add(ReportUnit.TextLine(t))
                }
                t.contains("：") && t.length <= 40 -> {
                    val idx = t.indexOf("：")
                    val key = t.substring(0, idx)
                    val value = t.substring(idx + 1)
                    out.add(ReportUnit.KeyValue(key, value))
                }
                else -> out.add(ReportUnit.TextLine(t))
            }
        }
        return out
    }

    /** 一个【段】= 一张卡片：[title] 为该段标题（null 表示报告开头无标题的前置内容）。 */
    private data class ReportBlock(val title: String?, val units: List<ReportUnit>)

    /** 把线性 unit 流按 Section 切块；Gap 不再产生任何间距（节奏由卡片与标题承担）。 */
    private fun groupIntoBlocks(units: List<ReportUnit>): List<ReportBlock> {
        val blocks = mutableListOf<ReportBlock>()
        var title: String? = null
        var bucket = mutableListOf<ReportUnit>()
        for (unit in units) {
            when (unit) {
                is ReportUnit.Section -> {
                    if (bucket.isNotEmpty() || title != null) blocks.add(ReportBlock(title, bucket))
                    title = unit.title
                    bucket = mutableListOf()
                }
                is ReportUnit.Gap -> Unit // no-op：间距节奏统一为 分节 12dp → 卡片内 16dp → 行间 6dp
                else -> bucket.add(unit)
            }
        }
        if (bucket.isNotEmpty() || title != null) blocks.add(ReportBlock(title, bucket))
        return blocks
    }

    /** 段位配色分流：核心指标/发言排行 → primary；载体偏好/高频词 → secondary；活跃频次/情绪指纹 → tertiary。 */
    @Composable
    private fun sectionAccent(title: String?, fallback: Color): Color = when {
        title == null -> fallback
        title.contains("载体偏好") || title.contains("高频词") -> MaterialTheme.colorScheme.secondary
        title.contains("活跃频次") || title.contains("情绪指纹") -> MaterialTheme.colorScheme.tertiary
        title.contains("核心指标") || title.contains("发言排行") -> MaterialTheme.colorScheme.primary
        else -> fallback
    }

    /**
     * 统一分节卡片：20dp 圆角 + 浅色细描边 + 极轻投影，顶部一条渐变发丝线标明归属色。
     * 卡片宽度始终 fillMaxWidth，卡内所有文本都限行/省略，绝不会顶出卡片。
     */
    @Composable
    private fun SectionCard(
        title: String?,
        accent: Color,
        modifier: Modifier = Modifier,
        index: Int? = null,
        content: @Composable () -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = MaterialTheme.colorScheme.surfaceBright,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 顶部渐变发丝线（章节主色 → 透明）：卡片有自己的"归属色"，分区一眼可辨
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent, accent.copy(alpha = 0.04f))
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(CardPad)
                ) {
                    if (title != null) {
                        SectionHeaderRow(title, accent, index = index)
                        Spacer(Modifier.height(CardInnerGap))
                    }
                    content()
                }
            }
        }
    }

    @Composable
    fun ReportContent(
        units: List<ReportUnit>,
        accent: Color = MaterialTheme.colorScheme.primary,
        maxHeight: Dp = DialogBodyFallback,
    ) {
        val blocks = remember(units) { groupIntoBlocks(units) }
        // 分节序号预计算（只数"有标题"的块）。不能放在 itemsIndexed 里用可变计数器累加：
        // 列表项在滚动/重组时会反复执行，累加会导致序号越滚越大。
        val sectionNos = remember(blocks) {
            var n = 0
            blocks.map { b -> if (b.title != null) ++n else null }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = maxHeight),
            contentPadding = PaddingValues(bottom = Space4),
        ) {
            itemsIndexed(blocks) { index, block ->
                val blockAccent = sectionAccent(block.title, accent)
                val no = sectionNos.getOrNull(index)
                SectionCard(
                    title = block.title,
                    accent = blockAccent,
                    index = no,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else SectionGap),
                ) {
                    // 「核心指标」这类纯 key/value 段改用 KPI 网格（大数字卡片）渲染：
                    // 一行行"指标 … 数值"读起来像表格，网格卡片才像数据看板，
                    // 这是用户要求的"数据分析的美化"里最直观的一处。
                    if (block.isKpiLike) {
                        KpiGrid(block.units.filterIsInstance<ReportUnit.KeyValue>(), blockAccent)
                    } else {
                        block.units.forEach { unit -> ReportUnitView(unit, blockAccent) }
                    }
                }
            }
        }
    }

    /** 是否是「核心指标」式的纯 KeyValue 段（≥3 项且没有其它类型），适合用 KPI 网格渲染。 */
    private val ReportBlock.isKpiLike: Boolean
        get() = units.size >= 3 && units.all { it is ReportUnit.KeyValue }

    /** 数值 / 单位拆分用的正则（与 PNG 导出用同一套规则，弹窗与导图观感一致） */
    private val ValueUnitRegex = Regex("^([0-9][0-9 .,%:+\\-]*)(.*)$")

    /** 排行行前缀："1. 张三" → rank=1；不是排行行则 rank=0 */
    private val RankPrefixRegex = Regex("^(\\d+)[.．、]\\s*(.+)$")

    /** 拆出排行行序号与昵称；非排行行原样返回（rank=0） */
    private fun splitRank(label: String): Pair<Int, String> {
        val m = RankPrefixRegex.matchEntire(label.trim()) ?: return 0 to label
        val no = m.groupValues[1].toIntOrNull() ?: return 0 to label
        val name = m.groupValues[2].trim()
        if (no <= 0 || name.isEmpty()) return 0 to label
        return no to name
    }

    /**
     * KPI 网格：默认每行 2 张大数字卡片；窄屏/特大字号自动降为单列（宁可长，不要挤）。
     * 这里手写 Column + Row 而不是 LazyVerticalGrid —— 后者嵌在 LazyColumn 里高度无界会直接崩。
     * 数值按"数字 + 单位"拆开：数字大而重、单位小而轻，读起来才像数据看板而不是表格。
     */
    @Composable
    private fun KpiGrid(items: List<ReportUnit.KeyValue>, accent: Color) {
        val shape = RoundedCornerShape(RadiusInner)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < KpiTwoColumnMinW) 1 else 2
            Column(Modifier.fillMaxWidth()) {
                items.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (rowIndex == 0) 0.dp else KpiGap)
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(KpiGap),
                    ) {
                        row.forEach { item ->
                            KpiCell(item, accent, shape, Modifier.weight(1f).fillMaxHeight())
                        }
                        // 末行不满时补空占位，保证最后一张卡片不会被拉伸成整行
                        if (row.size < columns) {
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KpiCell(
        item: ReportUnit.KeyValue,
        accent: Color,
        shape: Shape,
        modifier: Modifier = Modifier,
    ) {
        val (number, unit) = remember(item.value) { splitValueUnit(item.value) }
        // 单位很短才做"大数字 + 小单位"同排；单位较长（含补充说明）改排到下一行，
        // 否则会被省略号吃掉信息 —— 排版只改位置，一个字都不丢。
        val inlineUnit = unit.isNotEmpty() && unit.length <= 4
        Box(
            modifier
                .clip(shape)
                .background(accent.copy(alpha = 0.09f))
                .padding(horizontal = Space12, vertical = Space10)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = KpiCellMinH)
            ) {
                Text(
                    item.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space4))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        number,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inlineUnit) {
                        Spacer(Modifier.width(Space4))
                        Text(
                            unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                if (!inlineUnit && unit.isNotEmpty()) {
                    Spacer(Modifier.height(Space2))
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /**
     * "12,345 条" → ("12,345", "条")：把数值和单位拆开渲染（单位用轻字号）。
     * 非数字开头的值（如模型名）整串当数值，不丢字。
     */
    private fun splitValueUnit(value: String): Pair<String, String> {
        val t = value.trim()
        if (t.isEmpty()) return "" to ""
        val m = ValueUnitRegex.find(t) ?: return t to ""
        val number = m.groupValues[1].trim()
        val unit = m.groupValues[2].trim()
        return if (number.isEmpty()) t to "" else number to unit
    }

    @Composable
    private fun ReportUnitView(unit: ReportUnit, accent: Color) {
        when (unit) {
            is ReportUnit.Gap -> Unit
            is ReportUnit.Section -> SectionHeaderRow(unit.title, accent, Modifier.padding(top = Space6))
            is ReportUnit.BarRow -> BarRowView(unit, accent)
            is ReportUnit.KeyValue -> KeyValueView(unit)
            is ReportUnit.WordChips -> WordChipsView(unit.words)
            is ReportUnit.TextLine -> {
                Text(
                    unit.text,
                    // 正文行距放开一点（bodyMedium 默认 20sp → 23sp）：长段落更透气
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space4),
                )
            }
        }
    }

    /**
     * 排行 / 占比行：第一行「序号徽章 + 标签 …… 数值」，第二行渐变进度条。
     * 标签与数值各占一份权重且都限行省略，长昵称只会被省略，不会把数值顶出卡片。
     */
    @Composable
    private fun BarRowView(unit: ReportUnit.BarRow, accent: Color) {
        val density = LocalDensity.current
        val fs = density.fontScale
        val barShape = RoundedCornerShape(BarThickness / 2)
        val ratio = unit.ratio.coerceIn(0f, 1f)
        val (rank, name) = remember(unit.label) { splitRank(unit.label) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rank > 0) {
                    RankBadge(rank, accent)
                    Spacer(Modifier.width(Space6))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unit.value.isNotBlank()) {
                    Spacer(Modifier.width(Space8))
                    Text(
                        unit.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height((3 * fs).dp))
            // 自绘进度条：M3 的 LinearProgressIndicator 在小高度下会把圆角压平，
            // 这里用 Box 轨道 + Box 填充，显式 clip 保证两端圆角完整。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BarThickness * fs)
                    .clip(barShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(barShape)
                        // 渐变填充（浅→实）比纯色更有"数据条"的层次感，且不改变任何布局尺寸
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent.copy(alpha = 0.55f), accent)
                            )
                        )
                )
            }
        }
    }

    @Composable
    private fun KeyValueView(unit: ReportUnit.KeyValue) {
        val fs = LocalDensity.current.fontScale
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // key / value 各占一半且都设上限，避免长文本把对方顶出卡片边界
            Text(
                unit.key,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(Space12))
            Text(
                unit.value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun WordChipsView(words: List<Pair<String, Int>>) {
        val fs = LocalDensity.current.fontScale
        val chipShape = RoundedCornerShape(RadiusChip)
        // FlowRow 自动换行，避免词太多挤成一排被截断
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
            verticalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            words.take(20).forEachIndexed { index, (word, count) ->
                // 按热度分档：Top1-3 / 4-8 / 9-20
                val container = when (index) {
                    in 0..2 -> MaterialTheme.colorScheme.primaryContainer
                    in 3..7 -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
                val onContainer = when (index) {
                    in 0..2 -> MaterialTheme.colorScheme.onPrimaryContainer
                    in 3..7 -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                }
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(container)
                        .padding(horizontal = Space8, vertical = Space4)
                ) {
                    Text(
                        "$word ×$count",
                        style = MaterialTheme.typography.labelMedium,
                        color = onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ==================================================================
    // 八、输入弹窗
    // ==================================================================

    @Composable
    fun IntInputContent(
        title: String,
        hint: String,
        initial: Int,
        onSave: (Int) -> Unit,
        onClose: () -> Unit,
    ) {
        var text by remember { mutableStateOf(initial.toString()) }
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = { DialogTitle(title) },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space10))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.filter { c -> c.isDigit() }.take(9) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                dismissButton = {
                    Button(onClose) { Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                confirmButton = {
                    Button({
                        text.trim().toIntOrNull()?.let(onSave)
                        onClose()
                    }) { Text("保存", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            )
        }
    }

    @Composable
    fun AiExtraContent(
        onStart: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        var text by remember { mutableStateOf("") }
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = { DialogTitle("AI 附加要求") },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "可留空。例如：重点总结讨论的事项、语气更毒舌一点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space10))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text("可留空…") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                dismissButton = {
                    Button(onClose) { Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                confirmButton = {
                    Button({ onStart(text.trim()); onClose() }) {
                        Text("开始", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        }
    }

    // ==================================================================
    // 九、报告对话框
    // ==================================================================

    /**
     * 报告弹窗底部操作栏：次级动作（导出 / 复制）用图标按钮靠左，
     * 主级动作（关闭 / AI 总结）等分宽度靠右。整行 fillMaxWidth + weight，
     * 任何屏宽与字号下都不会换行、不会溢出弹窗（AlertDialogContent 会裁剪，
     * 越界 = 按钮被切掉，所以必须由权重兜住）。
     */
    @Composable
    private fun ReportActionBar(
        onExportPng: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
        onAiSummary: (() -> Unit)? = null,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExportPng, modifier = Modifier.size(IconTouchSize)) {
                Icon(
                    MaterialSymbols.Outlined.Download,
                    "导出 PNG",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(IconTouchSize)) {
                Icon(
                    MaterialSymbols.Outlined.Content_copy,
                    "复制报告",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(0.35f))
            Button(
                onClose,
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
            ) {
                Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onAiSummary != null) {
                Button(
                    onAiSummary,
                    Modifier.weight(1.15f),
                    contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                ) {
                    Text("AI 总结", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    /** 报告头部信息胶囊（时段 / 条数 / AI 状态）：长会话名不会把标题区撑爆 */
    @Composable
    private fun ReportMetaFlow(periodLabel: String, stats: String, ai: String) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
            verticalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            MetaChip(periodLabel)
            MetaChip("纯文本 ${countText(stats)} 条", MaterialTheme.colorScheme.tertiary)
            if (ai.isNotBlank()) {
                MetaChip("已生成 AI 总结", MaterialTheme.colorScheme.secondary)
            }
        }
    }

    @Composable
    fun ReportDialogContent(
        sessionName: String,
        periodLabel: String,
        stats: String,
        ai: String,
        units: List<ReportUnit>,
        hasTranscript: Boolean,
        onAiSummary: () -> Unit,
        onExportPng: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
    ) {
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "📊 $sessionName",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Space6))
                        ReportMetaFlow(periodLabel, stats, ai)
                    }
                },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        if (hasTranscript) {
                            ReportContent(units, maxHeight = bodyDp)
                        } else {
                            Text(
                                "分析完成，但该时段没有可统计的文本消息。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Space8),
                            )
                        }
                    }
                },
                confirmButton = {
                    ReportActionBar(
                        onExportPng = onExportPng,
                        onCopy = onCopy,
                        onClose = onClose,
                        onAiSummary = onAiSummary,
                    )
                },
            )
        }
    }

    @Composable
    fun AiReportDialogContent(
        sessionName: String,
        ai: String,
        units: List<ReportUnit>,
        onExportPng: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
    ) {
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "🤖 AI 总结 · $sessionName",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Space6))
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ChipGap),
                            verticalArrangement = Arrangement.spacedBy(ChipGap),
                        ) {
                            MetaChip("基于抽样转录的大模型洞察", MaterialTheme.colorScheme.tertiary)
                        }
                    }
                },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        if (units.isEmpty()) {
                            // 长文本分支：显式限高 + 内部滚动，保证按钮行始终可见、内容能滚到底
                            Text(
                                ai,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = bodyDp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        } else {
                            ReportContent(units, MaterialTheme.colorScheme.tertiary, maxHeight = bodyDp)
                        }
                    }
                },
                confirmButton = {
                    ReportActionBar(
                        onExportPng = onExportPng,
                        onCopy = onCopy,
                        onClose = onClose,
                    )
                },
            )
        }
    }

    private fun countText(stats: String): Int {
        val m = Regex("纯文本 (\\d+)").find(stats)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ==================================================================
    // 十、测试结果
    // ==================================================================

    /** 模型测试弹窗状态机：拉取列表 → 点选模型 → 流式/非流式验证 → 结果 */
    sealed interface TestUiState {
        object LoadingModels : TestUiState
        data class ModelList(val models: List<String>, val error: String?) : TestUiState
        data class Testing(val model: String) : TestUiState
        data class Result(val result: AiTestResult, val error: String?) : TestUiState
    }

    @Composable
    fun TestResultContent(
        state: TestUiState,
        onTestModel: (String) -> Unit,
        onUseModel: (String) -> Unit = {},
        onClose: () -> Unit,
    ) {
        val titleText = when (state) {
            is TestUiState.LoadingModels -> "测试连接"
            is TestUiState.ModelList -> "选择要测试的模型（${state.models.size} 个）"
            is TestUiState.Testing -> "测试中 · ${state.model}"
            is TestUiState.Result ->
                if (state.result.success) "✅ 测试通过" else "❌ 测试失败"
        }
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when (state) {
                            is TestUiState.Testing -> state.model
                            is TestUiState.Result -> state.result.testedModel
                            else -> ""
                        }
                        if (subtitle.isNotBlank()) {
                            Spacer(Modifier.height(Space6))
                            MetaChip(subtitle)
                        }
                    }
                },
                text = {
                    when (state) {
                        is TestUiState.LoadingModels -> {
                            Column(Modifier.fillMaxWidth()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(Space10))
                                Text("正在拉取模型列表（GET /models），请稍候…")
                            }
                        }
                        is TestUiState.ModelList -> {
                            Column(Modifier.fillMaxWidth()) {
                                if (!state.error.isNullOrBlank()) {
                                    Text(
                                        "⚠️ 拉取列表失败：${state.error}（仍可手动测试下方默认模型）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(bottom = Space6),
                                    )
                                }
                                Text(
                                    "点击某个模型即可发起流式 + 非流式请求验证其可用性：",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(Space8))
                                LazyColumn(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = bodyDp)
                                ) {
                                    itemsIndexed(state.models) { _, m ->
                                        BaseWidget(
                                            icon = MaterialSymbols.Outlined.Smart_toy,
                                            iconPlaceholder = true,
                                            title = m,
                                            description = "点击测试此模型",
                                            onClick = { onTestModel(m) },
                                            trailingDivider = true,
                                            trailingContent = {
                                                Icon(
                                                    MaterialSymbols.Outlined.Chevron_right,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        is TestUiState.Testing -> {
                            Column(Modifier.fillMaxWidth()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(Space10))
                                Text("正在验证「${state.model}」（流式 + 非流式请求），请稍候…")
                            }
                        }
                        is TestUiState.Result -> {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = bodyDp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    state.result.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!state.error.isNullOrBlank()) {
                                    Spacer(Modifier.height(Space8))
                                    Text(
                                        "注意：${state.error}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    when (state) {
                        is TestUiState.ModelList -> {
                            Button(onClose) {
                                Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        is TestUiState.Result -> {
                            // 三个动作放进同一行并等分宽度：原来的自然宽度相加会超过弹窗宽度，
                            // 被 AlertDialogContent 的 Surface 裁掉右半截（即"组件超出边框"）。
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Space8),
                            ) {
                                Button(
                                    onClose,
                                    Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                                ) {
                                    Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (state.result.testedModel.isNotBlank()) {
                                    Button(
                                        { onTestModel(state.result.testedModel) },
                                        Modifier.weight(1.25f),
                                        contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                                    ) {
                                        Icon(MaterialSymbols.Outlined.Refresh, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(Space6))
                                        Text("再测一次", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (state.result.success && state.result.testedModel.isNotBlank()) {
                                    Button(
                                        { onUseModel(state.result.testedModel) },
                                        Modifier.weight(1.6f),
                                        contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                                    ) {
                                        Icon(MaterialSymbols.Outlined.Check, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(Space6))
                                        Text("同步为当前模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                },
            )
        }
    }
}
