package dev.ujhhgtg.wekit.features.items.chat

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.History
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.ShowComposeDialogScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast

/** 关闭 showComposeDialog 弹窗的便捷扩展（对应 scope.onDismiss） */
private fun ShowComposeDialogScope.dismiss() = onDismiss()

/**
 * 聊天记录分析（重写版）
 *
 * 迁移自 WeKit Java 脚本「聊天记录分析 2.0」（微信 8.0.72 验证通过）：
 *  - 完整统计口径：核心指标 / 载体偏好 / 活跃频次 / 发言排行 / 高频词 / 情绪指纹 / 废话鉴定
 *  - AI 总结：OpenAI 兼容流式请求，多模型配置 + 测试连接
 *  - 美化 PNG 导出
 *  - 全部 UI 走 WeKit 标准 AlertDialogContent，长内容限高内部滚动
 *
 * 稳定性改造（相对旧版）：
 *  - 数据库查询改为分页读取，防止大群全量 OOM
 *  - 数据库未就绪时给出明确提示，不再静默空结果
 *  - 所有后台任务统一忙锁，避免重复点击并发
 */
object ChatRecordAnalysis : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "聊天记录分析"
    override val nameRes = R.string.feature_chat_record_analysis_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_record_analysis_description

    /**
     * 本地统计读取的消息条数上限（0 = 不限制，读该时段全部）。
     *
     * 用户 2026-09-22 反馈「条数只有 20000 的上限真的极少」：默认值从 20000 提到 50000，
     * 输入框位数上限同步从 7 位放到 9 位（最大 999,999,999），并且明确提示 0 = 全部。
     * 引擎是分页读取（PAGE_SIZE=1000），调大只会变慢，不会一次性撑爆内存。
     */
    private var maxCount by prefOption("chat_analysis_max_count", 50000)

    /**
     * 喂给 AI 的抽样条数上限。
     * 旧默认 500 条、上一版 1500 条，用户仍反馈太少 → 默认 5000 条；
     * 单条长度与整段总量上限见下方 [lineMax] / [transcriptMaxChars]。
     */
    private var sampleLimit by prefOption("chat_analysis_sample_limit", 5000)

    /** 单条消息喂给 AI 的字符上限（默认 2000）。 */
    private var lineMax by prefOption("chat_analysis_line_max", ChatAnalysisEngine.TRANSCRIPT_LINE_MAX_DEFAULT)

    /**
     * 整段喂给 AI 的文本总量上限（默认 24 万字）。
     * 这是防止服务端「上下文超限」的兜底：模型上下文小就把这个值调小。
     */
    private var transcriptMaxChars by prefOption(
        "chat_analysis_transcript_chars",
        ChatAnalysisEngine.TRANSCRIPT_MAX_CHARS_DEFAULT,
    )

    private val rangeLabels = listOf("今天", "昨天", "本周", "上周", "本月", "上月")

    @Volatile
    private var busy = false

    private var gTalker = ""
    private var gLabel = ""
    private var gMode = 0
    private var gTranscript = ""
    private var gStats = ""
    private var gAi = ""
    private var gReportDismiss: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadFeatures(): Set<String> {
        val cur = WePrefs.getStringSet("chat_analysis_features")
        return cur?.filter { it in ChatAnalysisEngine.ALL_FEATURES }?.toSet()
            ?: ChatAnalysisEngine.ALL_FEATURES.toSet()
    }

    private fun saveFeatures(s: Set<String>) {
        WePrefs.putStringSet("chat_analysis_features", s)
    }

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = 777266,
            text = "分析",
            drawable = MenuIcons.res(R.drawable.ic_menu_analysis),
            imageVector = MaterialSymbols.Outlined.History,
            isSupported = { _ -> true },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                if (busy) {
                    showToast("请等待当前任务完成")
                } else {
                    gTalker = msgInfo.talker
                    gLabel = runCatching { WeDatabaseApi.getDisplayName(msgInfo.talker) }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: msgInfo.talker
                    showRangePicker(view)
                }
            },
        )
    )

    // ---------------- 时间范围选择 ----------------

    private fun showRangePicker(view: View) {
        showComposeDialog(view.context) {
            ChatAnalysisUi.RangePickerContent(
                sessionName = gLabel,
                onPick = { mode ->
                    dismiss()
                    startAnalysis(view, mode)
                },
                onSettings = {
                    dismiss()
                    showSettings(view)
                },
                onClose = { dismiss() },
            )
        }
    }

    // ---------------- 设置 ----------------

    private fun showSettings(view: View) {
        showComposeDialog(view.context) {
            ChatAnalysisUi.SettingsContent(
                features = loadFeatures(),
                maxCount = maxCount,
                sampleLimit = sampleLimit,
                lineMax = lineMax,
                transcriptMaxChars = transcriptMaxChars,
                selectedModelName = ChatAnalysisModelStore.selectedModel()?.name ?: "",
                onToggleFeature = { f, on ->
                    val s = loadFeatures().toMutableSet()
                    if (on) s.add(f) else s.remove(f)
                    saveFeatures(s)
                },
                onEditMaxCount = {
                    editInt(
                        view,
                        "分析条数上限",
                        "0 = 全部（读该时段所有消息）。数值越大读取越慢、越全；建议大群 20000~200000。",
                        maxCount,
                    ) { maxCount = it }
                },
                onEditSampleLimit = {
                    editInt(
                        view,
                        "抽样上限",
                        "喂给 AI 的最大文本条数，建议 1000~20000。条数越多 AI 看得越全，但请求也越大。",
                        sampleLimit,
                    ) { sampleLimit = it }
                },
                onEditLineMax = {
                    editInt(
                        view,
                        "单条文本上限（字）",
                        "一条消息最多喂给 AI 多少字（超出截断），默认 2000。长文多可调到 5000~10000。",
                        lineMax,
                    ) { lineMax = it }
                },
                onEditTranscriptMaxChars = {
                    editInt(
                        view,
                        "喂给 AI 的文本上限（字）",
                        "整段聊天记录的总字数上限，默认 240000。模型上下文小（如 32K/128K）请调小，" +
                            "否则服务端会返回上下文超限错误。",
                        transcriptMaxChars,
                    ) { transcriptMaxChars = it }
                },
                onModelManager = { showModelManager(view) },
                onTestModel = { testCurrentModel(view) },
                onClose = { dismiss() },
            )
        }
    }

    private fun editInt(view: View, title: String, hint: String, current: Int, onSave: (Int) -> Unit) {
        showComposeDialog(view.context) {
            ChatAnalysisUi.IntInputContent(
                title = title,
                hint = hint,
                initial = current,
                onSave = onSave,
                onClose = { dismiss() },
            )
        }
    }

    // ---------------- 模型管理 ----------------

    private fun showModelManager(view: View) {
        showComposeDialog(view.context) {
            var models by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(ChatAnalysisModelStore.loadModels())
            }
            var selected by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(ChatAnalysisModelStore.selectedName())
            }
            ChatAnalysisUi.ModelManagerContent(
                models = models,
                selectedName = selected,
                onSelect = { m ->
                    ChatAnalysisModelStore.select(m.name)
                    selected = m.name
                    showToast("已选择模型：${m.name}")
                },
                onEdit = { m ->
                    dismiss()
                    showModelEdit(view, m)
                },
                onDelete = { m ->
                    ChatAnalysisModelStore.remove(m.name)
                    models = ChatAnalysisModelStore.loadModels()
                    selected = ChatAnalysisModelStore.selectedName()
                    showToast("已删除模型：${m.name}")
                },
                onAdd = {
                    dismiss()
                    showModelEdit(view, AiModelConfig("", "", "", "", "/chat/completions"))
                },
                onClose = { dismiss() },
            )
        }
    }

    private fun showModelEdit(view: View, model: AiModelConfig) {
        showComposeDialog(view.context) {
            ChatAnalysisUi.ModelEditContent(
                model = model,
                onSave = { m ->
                    ChatAnalysisModelStore.addOrUpdate(m)
                    showToast("已保存模型：${m.name}")
                    dismiss()
                    showModelManager(view)
                },
                onTest = { testRawModel(view, model) },
                onClose = {
                    dismiss()
                    showModelManager(view)
                },
            )
        }
    }

    // ---------------- 测试连接 ----------------

    private fun testCurrentModel(view: View) {
        val model = ChatAnalysisModelStore.selectedModel()
        if (model == null || model.apiKey.isBlank()) {
            showToast("未配置 AI 模型，请先在设置中添加")
            return
        }
        // 拉取 /models 列表 → 弹窗点选模型 → 流式+非流式验证
        runTest(view, model, autoTestModel = null)
    }

    private fun testRawModel(view: View, model: AiModelConfig) {
        if (model.baseUrl.isBlank() || model.apiKey.isBlank()) {
            showToast("请先填写 Base URL 和 API Key")
            return
        }
        // 编辑弹窗里测试：优先直接测当前填写的模型
        runTest(view, model, autoTestModel = model.model.trim().ifEmpty { null })
    }

    private fun runTest(view: View, model: AiModelConfig, autoTestModel: String?) {
        if (busy) return
        busy = true
        var dialogDismiss: (() -> Unit)? = null
        showComposeDialog(view.context) {
            dialogDismiss = { dismiss() }
            ChatAnalysisUi.TestResultContent(
                state = ChatAnalysisUi.TestUiState.LoadingModels,
                onTestModel = {},
                onClose = { dismiss() },
            )
        }
        Thread {
            val listResult = runCatching { ChatAnalysisAi.fetchModels(model.baseUrl, model.apiKey) }
            mainHandler.post {
                busy = false
                dialogDismiss?.invoke()
                val list = listResult.getOrNull() ?: emptyList()
                val err = listResult.exceptionOrNull()?.message
                val target = autoTestModel?.trim()?.takeIf { it.isNotEmpty() }
                if (target != null) {
                    // 已有指定模型：直接进入验证
                    showModelTesting(view, model, target)
                } else if (list.isNotEmpty() || err == null) {
                    // 展示模型列表供点选测试
                    showComposeDialog(view.context) {
                        ChatAnalysisUi.TestResultContent(
                            state = ChatAnalysisUi.TestUiState.ModelList(list, err),
                            onTestModel = { m -> dismiss(); showModelTesting(view, model, m) },
                            onClose = { dismiss() },
                        )
                    }
                } else {
                    showComposeDialog(view.context) {
                        ChatAnalysisUi.TestResultContent(
                            state = ChatAnalysisUi.TestUiState.Result(
                                AiTestResult(false, "拉取模型列表失败：$err\n请检查 Base URL / API Key"),
                                err,
                            ),
                            onTestModel = {},
                            onClose = { dismiss() },
                        )
                    }
                }
            }
        }.start()
    }

    /** 验证指定模型：流式 + 非流式请求，展示结果；可再测一次或设为当前模型 */
    private fun showModelTesting(view: View, model: AiModelConfig, targetModel: String) {
        if (busy) return
        busy = true
        var dialogDismiss: (() -> Unit)? = null
        showComposeDialog(view.context) {
            dialogDismiss = { dismiss() }
            ChatAnalysisUi.TestResultContent(
                state = ChatAnalysisUi.TestUiState.Testing(targetModel),
                onTestModel = {},
                onClose = { dismiss() },
            )
        }
        Thread {
            val result = runCatching { ChatAnalysisAi.testModel(model, targetModel) }
            mainHandler.post {
                busy = false
                dialogDismiss?.invoke()
                val r = result.getOrNull()
                showComposeDialog(view.context) {
                    ChatAnalysisUi.TestResultContent(
                        state = ChatAnalysisUi.TestUiState.Result(
                            r ?: AiTestResult(false, "测试失败：${result.exceptionOrNull()?.message}"),
                            result.exceptionOrNull()?.message,
                        ),
                        onTestModel = { m -> dismiss(); showModelTesting(view, model, m) },
                        onUseModel = { m ->
                            // 「同步为当前模型」：把刚验证通过的 baseURL / APIKey 与新模型名一起落盘并选中。
                            // 只写模型名的话，选中项会指向一个并不存在的条目，用户还得手动重填地址与密钥。
                            if (model.name.isBlank()) {
                                showToast("请先给该模型配置命名，再同步为当前模型")
                            } else {
                                ChatAnalysisModelStore.addOrUpdate(model.copy(model = m))
                                showToast("已同步并切换当前模型：$m")
                            }
                        },
                        onClose = { dismiss() },
                    )
                }
            }
        }.start()
    }

    // ---------------- 分析流程 ----------------

    private fun startAnalysis(view: View, mode: Int) {
        if (busy) return
        busy = true
        gMode = mode
        showToast("分析中，请稍候…")
        Thread {
            try {
                if (!ChatAnalysisEngine.dbReady) {
                    mainHandler.post {
                        busy = false
                        showToast("数据库服务未就绪，请先在模块设置中启用「数据库服务」")
                    }
                    return@Thread
                }
                val features = loadFeatures()
                val result = ChatAnalysisEngine.analyze(
                    talker = gTalker,
                    mode = mode,
                    maxCount = maxCount,
                    sampleLimit = sampleLimit,
                    features = features,
                    lineMax = lineMax,
                    transcriptMaxChars = transcriptMaxChars,
                )
                mainHandler.post {
                    busy = false
                    if (result.textN == 0) {
                        showToast("该时段没有纯文本消息")
                        return@post
                    }
                    gStats = result.statsReport
                    gTranscript = result.transcript
                    gAi = ""
                    showReport(view)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    busy = false
                    showToast("分析失败：${e.message}")
                }
            }
        }.start()
    }

    // ---------------- 报告展示 ----------------

    private fun showReport(view: View) {
        val units = rememberUnits(gStats)
        showComposeDialog(view.context) {
            gReportDismiss = { dismiss() }
            ChatAnalysisUi.ReportDialogContent(
                sessionName = gLabel,
                periodLabel = currentPeriodLabel(),
                stats = gStats,
                ai = gAi,
                units = units,
                hasTranscript = gTranscript.isNotBlank(),
                onAiSummary = { startAiSummary(view) },
                onExportPng = { exportPng(view) },
                onCopy = { copyReport(view) },
                onClose = { dismiss() },
            )
        }
    }

    private fun rememberUnits(stats: String): List<ChatAnalysisUi.ReportUnit> =
        ChatAnalysisUi.parseReport(stats)

    private fun currentPeriodLabel(): String =
        rangeLabels.getOrElse(gMode) { "分析范围" } + " · " + gLabel

    private fun copyReport(view: View) {
        val txt = buildString {
            append("【").append(gLabel).append("】聊天记录分析\n")
            append(gStats)
            if (gAi.isNotBlank()) {
                append("\n\n【AI 总结】\n").append(gAi)
            }
        }
        runCatching { copyToClipboard(view.context, txt) }
        showToast("已复制到剪贴板")
    }

    // ---------------- AI 总结 ----------------

    private fun startAiSummary(view: View) {
        if (busy) return
        if (gTranscript.isBlank()) {
            showToast("该时段没有可分析的文本记录")
            return
        }
        val model = ChatAnalysisModelStore.selectedModel()
        if (model == null || model.apiKey.isBlank() || model.baseUrl.isBlank()) {
            showToast("未配置 AI 模型，请先在设置中添加")
            showSettings(view)
            return
        }
        showAiExtraInput(view, model)
    }

    private fun showAiExtraInput(view: View, model: AiModelConfig) {
        showComposeDialog(view.context) {
            ChatAnalysisUi.AiExtraContent(
                onStart = { extra -> doAiSummary(view, model, extra) },
                onClose = { dismiss() },
            )
        }
    }

    private fun doAiSummary(view: View, model: AiModelConfig, extra: String) {
        if (busy) return
        busy = true
        showToast("AI 生成中…")
        Thread {
            try {
                val sys = "你是一名资深的微信聊天记录分析师，擅长从碎片对话中还原事实、洞察人心。请基于用户提供的聊天记录，输出一份详尽、专业、有深度的中文分析报告。\n" +
                    "输出要求：\n" +
                    "1. 报告总字数 1500~2500 字，宁详勿略，禁止敷衍、禁止只列要点不展开。\n" +
                    "2. 按话题/主题分段：每段先【事实】客观完整地复述该话题下发生了什么（涉及谁、时间线、关键对话内容、数字与结论），再写【深度剖析】给出精辟评价（动机、立场、矛盾点、潜在影响、可借鉴之处），剖析必须具体、有洞察，不能空泛。\n" +
                    "3. 额外覆盖以下章节（同样要求事实+剖析）：\n" +
                    "   - 话题主线与讨论脉络（从开头到结尾的推进逻辑）\n" +
                    "   - 关键信息与决策（重要结论、待办、约定）\n" +
                    "   - 人物角色与发言风格（谁主导、谁附和、谁带节奏）\n" +
                    "   - 情绪氛围与变化（紧张/轻松/分歧/共识的转折点）\n" +
                    "   - 风险与机会（可能踩的坑、值得抓住的点）\n" +
                    "4. 引用对话原文时保留说话人称呼，让报告读起来有据可依。\n" +
                    "5. 使用小标题（【】）和编号要点，段落完整，不要使用过于口语化的表达。"
                val user = buildString {
                    if (extra.isNotBlank()) {
                        append("【附加要求】").append(extra).append("\n\n")
                    }
                    append("【聊天记录（抽样）】\n").append(gTranscript)
                }
                var text = ""
                var lastToastLen = 0
                try {
                    text = ChatAnalysisAi.stream(model, sys, user) { delta ->
                        // 流式进度反馈：每累计约 600 字提示一次，避免长时间无反馈
                        lastToastLen += delta.length
                        if (lastToastLen >= 600) {
                            val n = lastToastLen
                            mainHandler.post { showToast("AI 生成中… 已生成 $n 字") }
                            lastToastLen = 0
                        }
                    }
                } catch (e: Exception) {
                    // 流式失败 → 非流式降级
                    text = ChatAnalysisAi.plain(model, sys, user).orEmpty()
                }
                mainHandler.post {
                    busy = false
                    if (text.isBlank()) {
                        showToast("AI 返回为空，请检查模型配置")
                    } else {
                        gAi = text
                        gReportDismiss?.invoke()
                        gReportDismiss = null
                        showAiReport(view)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    busy = false
                    showToast("AI 失败：${e.message}")
                }
            }
        }.start()
    }

    private fun showAiReport(view: View) {
        val units = ChatAnalysisUi.parseReport(gAi)
        showComposeDialog(view.context) {
            gReportDismiss = { dismiss() }
            ChatAnalysisUi.AiReportDialogContent(
                sessionName = gLabel,
                ai = gAi,
                units = units,
                onExportPng = { exportPng(view) },
                onCopy = { copyReport(view) },
                onClose = { dismiss() },
            )
        }
    }

    // ---------------- PNG 导出 ----------------

    private fun exportPng(view: View) {
        if (busy) return
        busy = true
        showToast("正在导出 PNG…")
        Thread {
            try {
                val period = currentPeriodLabel()
                val path = ChatAnalysisPng.export(
                    stats = gStats,
                    ai = gAi,
                    sessionName = gLabel,
                    sessionWxid = gTalker,
                    period = period,
                )
                mainHandler.post {
                    busy = false
                    showToast("已导出：$path")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    busy = false
                    showToast("导出失败：${e.message}")
                }
            }
        }.start()
    }
}
