package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

/**
 * 聊天记录分析 —— Compose UI 组件
 *
 * 全部弹窗基于 showComposeDialog + AlertDialogContent（WeKit 标准），
 * 长内容一律使用限高 LazyColumn 内部滚动，避免弹窗超出屏幕。
 */
internal object ChatAnalysisUi {

    // ---------------- 通用小组件 ----------------

    @Composable
    fun SectionHeader(title: String, accent: Color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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

    // ---------------- 时间范围选择 ----------------

    @Composable
    fun RangePickerContent(
        sessionName: String,
        onPick: (Int) -> Unit,
        onSettings: () -> Unit,
        onClose: () -> Unit,
    ) {
        val labels = listOf("今天", "昨天", "本周", "上周", "本月", "上月")
        val icons = listOf(
            MaterialSymbols.Outlined.Sunny,
            MaterialSymbols.Outlined.Schedule,
            MaterialSymbols.Outlined.Star,
            MaterialSymbols.Outlined.History,
            MaterialSymbols.Outlined.Tune,
            MaterialSymbols.Outlined.Refresh,
        )
        val hints = listOf(
            "今天 00:00 至今",
            "昨天全天",
            "本周一 00:00 至今",
            "上周一至上周日",
            "本月 1 日至今",
            "上个月整月",
        )
        AlertDialogContent(
            title = { Text("分析时间范围") },
            text = {
                LazyColumn(Modifier.heightIn(max = 430.dp)) {
                    itemsIndexed(labels) { index, label ->
                        BaseWidget(
                            icon = icons[index],
                            iconPlaceholder = true,
                            title = label,
                            description = "统计并总结「$sessionName」该时段内的纯文本聊天记录 · ${hints[index]}",
                            onClick = { onPick(index) },
                            trailingDivider = true,
                            trailingContent = {
                                Icon(MaterialSymbols.Outlined.Chevron_right, null)
                            },
                        )
                    }
                    item {
                        HorizontalDivider()
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
                                Icon(MaterialSymbols.Outlined.Chevron_right, null)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClose) { Text("关闭") }
            },
        )
    }

    // ---------------- 设置 ----------------

    @Composable
    fun SettingsContent(
        features: Set<String>,
        maxCount: Int,
        sampleLimit: Int,
        selectedModelName: String,
        onToggleFeature: (String, Boolean) -> Unit,
        onEditMaxCount: () -> Unit,
        onEditSampleLimit: () -> Unit,
        onModelManager: () -> Unit,
        onTestModel: () -> Unit,
        onClose: () -> Unit,
    ) {
        AlertDialogContent(
            title = { Text("聊天记录分析 · 设置") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        SectionHeader("功能开关")
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Smart_toy,
                            iconPlaceholder = true,
                            title = "AI 总结",
                            description = "用大模型总结该时段聊天内容",
                            checked = ChatAnalysisEngine.FEATURE_AI in features,
                            onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_AI, it) },
                        )
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Tune,
                            iconPlaceholder = true,
                            title = "本地统计",
                            description = "核心指标 / 载体偏好 / 活跃频次 / 高频词 / 情绪指纹",
                            checked = ChatAnalysisEngine.FEATURE_STATS in features,
                            onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_STATS, it) },
                        )
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Sort,
                            iconPlaceholder = true,
                            title = "发言排行",
                            description = "发言对比 / 群成员发言 Top10",
                            checked = ChatAnalysisEngine.FEATURE_RANK in features,
                            onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_RANK, it) },
                        )
                    }
                    item {
                        SectionHeader("分析参数")
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Tune,
                            iconPlaceholder = true,
                            title = "分析条数上限",
                            description = if (maxCount <= 0) "0 = 全部（越大越慢）" else "当前：$maxCount 条",
                            onClick = onEditMaxCount,
                            trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                        )
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Tune,
                            iconPlaceholder = true,
                            title = "抽样上限",
                            description = "喂给 AI 的最大文本条数（当前：$sampleLimit）",
                            onClick = onEditSampleLimit,
                            trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                        )
                    }
                    item {
                        SectionHeader("AI 模型")
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Memory,
                            iconPlaceholder = true,
                            title = "当前模型",
                            description = selectedModelName.ifEmpty { "未配置" },
                            onClick = onModelManager,
                            trailingDivider = true,
                            trailingContent = {
                                Icon(MaterialSymbols.Outlined.Chevron_right, null)
                            },
                        )
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Settings,
                            iconPlaceholder = true,
                            title = "模型管理",
                            description = "新增 / 编辑 / 删除 / 选择（支持多套 baseURL + APIKey）",
                            onClick = onModelManager,
                            trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                        )
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
            },
            confirmButton = {
                Button(onClose) { Text("关闭") }
            },
        )
    }

    // ---------------- 模型管理 ----------------

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
        AlertDialogContent(
            title = { Text("AI 模型管理") },
            text = {
                if (models.isEmpty()) {
                    Text(
                        "还没有模型配置，点击下方「新增模型」添加。",
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
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
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                    }
                                    IconButton(onClick = { onEdit(model) }) {
                                        Icon(MaterialSymbols.Outlined.Edit, "编辑")
                                    }
                                    IconButton(onClick = { onDelete(model) }) {
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
            confirmButton = {
                Button(onAdd) {
                    Icon(MaterialSymbols.Outlined.Add, null)
                    Text("新增模型")
                }
                Button(onClose) { Text("关闭") }
            },
        )
    }

    // ---------------- 模型编辑 ----------------

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

        AlertDialogContent(
            title = { Text(if (model.name.isBlank()) "新增模型" else "编辑模型") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                } else {
                                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                                },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showKey = !showKey }) {
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
            confirmButton = {
                Button(onTest) {
                    Icon(MaterialSymbols.Outlined.Bolt, null)
                    Text("测试")
                }
                Button({
                    onSave(
                        AiModelConfig(
                            name = name.trim().ifEmpty { "未命名模型" },
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = modelId.trim(),
                            path = path.trim().ifEmpty { "/chat/completions" },
                        )
                    )
                }) { Text("保存") }
                Button(onClose) { Text("取消") }
            },
        )
    }

    // ---------------- 报告渲染 ----------------

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
                    out.add(ReportUnit.Gap)
                    out.add(ReportUnit.Section(t.removeSurrounding("【", "】")))
                }
                t.contains("█") -> {
                    val barLen = t.count { it == '█' }
                    val clean = t.replace("█", "").replace(" ", "").trim()
                    val m = Regex("^(.*?)(\\d+)$").find(clean)
                    val label = m?.groupValues?.get(1) ?: clean
                    val value = m?.groupValues?.get(2) ?: ""
                    out.add(ReportUnit.BarRow(label, value, barLen / 16f))
                }
                t.matches(Regex("^([^\\s×]+×\\d+[\\s　]*)+$")) -> {
                    // 高频词行：word×n word×n ...
                    val words = Regex("([^\\s]+?)×(\\d+)").findAll(t)
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

    @Composable
    fun ReportContent(
        units: List<ReportUnit>,
        accent: Color = MaterialTheme.colorScheme.primary,
    ) {
        LazyColumn(Modifier.heightIn(max = 460.dp).fillMaxWidth()) {
            itemsIndexed(units) { _, unit ->
                when (unit) {
                    is ReportUnit.Gap -> Spacer(Modifier.height(6.dp))
                    is ReportUnit.Section -> SectionHeader(unit.title, accent)
                    is ReportUnit.BarRow -> BarRowView(unit, accent)
                    is ReportUnit.KeyValue -> KeyValueView(unit)
                    is ReportUnit.WordChips -> WordChipsView(unit.words)
                    is ReportUnit.TextLine -> Text(
                        unit.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun BarRowView(unit: ReportUnit.BarRow, accent: Color) {
        Column(Modifier.padding(vertical = 3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    unit.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    unit.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { unit.ratio.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }

    @Composable
    private fun KeyValueView(unit: ReportUnit.KeyValue) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(unit.key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                unit.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun WordChipsView(words: List<Pair<String, Int>>) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            words.take(12).forEach { (word, count) ->
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "$word ×$count",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }

    // ---------------- 输入弹窗 ----------------

    @Composable
    fun IntInputContent(
        title: String,
        hint: String,
        initial: Int,
        onSave: (Int) -> Unit,
        onClose: () -> Unit,
    ) {
        var text by remember { mutableStateOf(initial.toString()) }
        AlertDialogContent(
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() }.take(7) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button({
                    text.trim().toIntOrNull()?.let(onSave)
                    onClose()
                }) { Text("保存") }
                Button(onClose) { Text("取消") }
            },
        )
    }

    @Composable
    fun AiExtraContent(
        onStart: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        var text by remember { mutableStateOf("") }
        AlertDialogContent(
            title = { Text("AI 附加要求") },
            text = {
                Column {
                    Text(
                        "可留空。例如：重点总结讨论的事项、语气更毒舌一点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("可留空…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button({ onStart(text.trim()); onClose() }) { Text("开始") }
                Button(onClose) { Text("取消") }
            },
        )
    }

    // ---------------- 报告对话框 ----------------

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
        AlertDialogContent(
            title = {
                Column {
                    Text(
                        "📊 $sessionName",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$periodLabel · 纯文本 ${countText(stats)} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                Column {
                    if (hasTranscript) {
                        ReportContent(units)
                    } else {
                        Text("分析完成，但该时段没有可统计的文本消息。")
                    }
                }
            },
            confirmButton = {
                Button(onAiSummary) {
                    Icon(MaterialSymbols.Outlined.Smart_toy, null)
                    Text("AI 总结")
                }
                Button(onExportPng) {
                    Icon(MaterialSymbols.Outlined.Download, null)
                    Text("导出 PNG")
                }
                Button(onCopy) {
                    Icon(MaterialSymbols.Outlined.Content_copy, null)
                    Text("复制")
                }
                Button(onClose) { Text("关闭") }
            },
        )
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
        AlertDialogContent(
            title = {
                Column {
                    Text(
                        "🤖 AI 总结 · $sessionName",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "基于抽样转录的大模型洞察",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                if (units.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                    ) {                        Text(ai, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    ReportContent(units, MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Button(onExportPng) {
                    Icon(MaterialSymbols.Outlined.Download, null)
                    Text("导出 PNG")
                }
                Button(onCopy) {
                    Icon(MaterialSymbols.Outlined.Content_copy, null)
                    Text("复制")
                }
                Button(onClose) { Text("关闭") }
            },
        )
    }

    private fun countText(stats: String): Int {
        val m = Regex("纯文本 (\\d+)").find(stats)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ---------------- 测试结果 ----------------

    @Composable
    fun TestResultContent(
        loading: Boolean,
        result: AiTestResult?,
        onClose: () -> Unit,
        onApplyModels: (List<String>) -> Unit = {},
    ) {
        AlertDialogContent(
            title = { Text(if (loading) "测试连接" else if (result?.success == true) "✅ 测试通过" else "❌ 测试失败") },
            text = {
                Column(Modifier.heightIn(max = 360.dp)) {
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text("正在请求模型接口，请稍候…")
                    } else if (result != null) {
                        Text(result.message)
                        if (result.models.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("可用模型（${result.models.size} 个）：", fontWeight = FontWeight.Bold)
                            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                                itemsIndexed(result.models) { _, m ->
                                    Text(m, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!loading) {
                    Button(onClose) { Text("关闭") }
                }
            },
        )
    }
}
