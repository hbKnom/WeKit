package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.preferences.WePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天记录分析 —— 数据模型与配置存储
 *
 * 迁移自 WeKit Java 脚本「聊天记录分析 v0.3.4」：
 * 支持多套 OpenAI 兼容模型配置（不同 baseUrl + apiKey + model），按需选择。
 */
data class AiModelConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val path: String = "/chat/completions",
) {
    fun endpoint(): String {
        val b = baseUrl.trim().trimEnd('/')
        val p = path.trim().ifEmpty { "/chat/completions" }
        return if (p.startsWith("/")) b + p else b + "/" + p
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("path", path)

    companion object {
        fun fromJson(o: JSONObject): AiModelConfig = AiModelConfig(
            name = o.optString("name", "未命名模型"),
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            path = o.optString("path", "/chat/completions"),
        )
    }
}

/** 分析结果：本地统计报告文本 + AI 报告 + 转录文本 */
data class AnalyzeResult(
    val statsReport: String,
    val aiReport: String = "",
    val transcript: String = "",
    val totalAll: Int = 0,
    val textN: Int = 0,
)

/** 测试连接结果 */
data class AiTestResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList(),
    val testedModel: String = "",
    val streamOk: Boolean = false,
    val plainOk: Boolean = false,
)

/**
 * 模型配置仓库：JSON 数组持久化到 WePrefs，支持多套模型按需切换。
 */
object ChatAnalysisModelStore {

    private const val KEY_MODELS = "chat_analysis_ai_models"
    private const val KEY_SELECTED = "chat_analysis_ai_selected"

    /** 首次使用时的默认模型（用户可自行增删改） */
    private fun defaultModels(): List<AiModelConfig> = listOf(
        AiModelConfig(
            name = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            model = "deepseek-chat",
            path = "/chat/completions",
        ),
        AiModelConfig(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-4o-mini",
            path = "/chat/completions",
        ),
    )

    @Synchronized
    fun loadModels(): List<AiModelConfig> {
        val raw = WePrefs.getString(KEY_MODELS)
        if (raw.isNullOrBlank()) {
            val def = defaultModels()
            saveModels(def)
            return def
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                runCatching { AiModelConfig.fromJson(o) }.getOrNull()
            }
        }.getOrDefault(defaultModels())
    }

    @Synchronized
    fun saveModels(models: List<AiModelConfig>) {
        val arr = JSONArray()
        models.forEach { arr.put(it.toJson()) }
        WePrefs.putString(KEY_MODELS, arr.toString())
        // 选中项失效时回退到第一个
        val sel = selectedName()
        if (sel.isNotBlank() && models.none { it.name == sel }) {
            if (models.isNotEmpty()) WePrefs.putString(KEY_SELECTED, models[0].name)
        }
    }

    fun selectedName(): String = WePrefs.getStringOrDef(KEY_SELECTED, "")

    fun selectedModel(): AiModelConfig? {
        val models = loadModels()
        val sel = selectedName()
        return models.firstOrNull { it.name == sel } ?: models.firstOrNull()
    }

    fun select(name: String) {
        WePrefs.putString(KEY_SELECTED, name)
    }

    fun addOrUpdate(model: AiModelConfig) {
        val models = loadModels().toMutableList()
        val idx = models.indexOfFirst { it.name == model.name }
        if (idx >= 0) models[idx] = model else models.add(model)
        saveModels(models)
        // 新增/更新后默认选中它
        select(model.name)
    }

    fun remove(name: String) {
        val models = loadModels().filter { it.name != name }
        saveModels(models)
    }
}

/**
 * 第 16 轮「扩展维度包」开关。
 *
 * 第 16 轮往报告末尾追加了六个新维度（发言密度 / 每日趋势 / 回应速度画像 / 被接话榜 /
 * 话题时段偏好 / 作息画像）。它们全部是**追加**，旧段落的文本一字未改，所以默认开启
 * 不会改变任何既有解析结果；但报告变长是真的，于是给一个开关：关掉就退回第 15 轮的篇幅。
 *
 * 存的是布尔值，读失败一律按"开启"处理（宁可多显示，也不要因为一次读盘异常把功能吞掉）。
 */
object ChatAnalysisExtraDims {
    private const val KEY_EXTRA_DIMS = "chat_analysis_extra_dims"

    /** 第 16 轮新增的维度数量：设置页与统计口径都用它，避免两处写死数字。 */
    const val EXTRA_DIM_COUNT = 6

    fun isEnabled(): Boolean = runCatching { WePrefs.getBoolOrDef(KEY_EXTRA_DIMS, true) }.getOrDefault(true)

    fun setEnabled(on: Boolean) {
        runCatching { WePrefs.putBool(KEY_EXTRA_DIMS, on) }
    }
}

/**
 * 第 17 轮「观感 + 维度扩展」开关。
 *
 * 第 17 轮往报告末尾**再追加**六个纯计算维度（活跃集中度 / 复读与重复 / 提问与回应 /
 * 特殊消息雷达 / 连续活跃 / 昼夜话量）。与第 16 轮同一套纪律：只在末尾追加，
 * 老段落的文本一字未改，所以默认开启不会改变任何既有解析结果；关掉就退回第 16 轮的篇幅。
 *
 * 存的是布尔值，读失败一律按"开启"处理（宁可多显示，也不要因为一次读盘异常把功能吞掉）。
 */
object ChatAnalysisRound17Dims {
    private const val KEY_DIMS17 = "chat_analysis_dims_v17"

    /** 第 17 轮新增的维度数量：设置页与统计口径都用它，避免两处写死数字。 */
    const val DIM_COUNT = 6

    fun isEnabled(): Boolean = runCatching { WePrefs.getBoolOrDef(KEY_DIMS17, true) }.getOrDefault(true)

    fun setEnabled(on: Boolean) {
        runCatching { WePrefs.putBool(KEY_DIMS17, on) }
    }
}

/**
 * 第 18 轮「事件、节奏与关系网络」开关。
 *
 * 第 18 轮往报告末尾**再追加**七个维度：
 *  - 【撤回与系统事件】 谁最爱说出口又收回去，系统事件占比多少；
 *  - 【对话轮次结构】   一轮连续对话有多长（连续对话长度分布）；
 *  - 【沉默间隔谱】     从"隔一分钟"到"隔一天"的全量静默分档 + 最长沉默的起止时间点；
 *  - 【每人说话画像】   人均字数榜（谁是长文大户）；
 *  - 【表情符号排行】   用得最多的表情 Top N；
 *  - 【默契搭档】       最常互相接话的两个人（关系网络，而不是单向排行）；
 *  - 【每日开场与收尾】 每天第一句 / 最后一句是谁说的。
 *
 * 与第 16/17 轮**同一套纪律**：只在报告末尾追加，老段落的每一行文本一字未改，
 * 所以默认开启不会改变任何既有解析结果；关掉就退回第 17 轮的篇幅。
 *
 * 存的是布尔值，读失败一律按"开启"处理（宁可多显示，也不要因为一次读盘异常把功能吞掉）。
 */
object ChatAnalysisRound18Dims {
    private const val KEY_DIMS18 = "chat_analysis_dims_v18"

    /** 第 18 轮新增的维度数量：设置页与统计口径都用它，避免两处写死数字。 */
    const val DIM_COUNT = 7

    fun isEnabled(): Boolean = runCatching { WePrefs.getBoolOrDef(KEY_DIMS18, true) }.getOrDefault(true)

    fun setEnabled(on: Boolean) {
        runCatching { WePrefs.putBool(KEY_DIMS18, on) }
    }
}
