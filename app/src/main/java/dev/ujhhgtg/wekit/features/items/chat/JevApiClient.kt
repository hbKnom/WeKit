package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.WeLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Jev (System One) 客户端
 *
 * 协议与原脚本完全一致：
 *   POST https://api.typesafe.ai/v1/systemone
 *   Authorization: Bearer <api_key>
 *   body { state, model, questions }
 *
 * questions 是固定骨架（每个维度声明 type + criteria），服务端返回概率分布，因此这里没有
 * 任何生成式提示词拼接。
 *
 * 相对原脚本的差异（并发安全修正，不改变协议）：
 *  - 原脚本把 HTTP 状态码写进脚本级共享字段，多条消息并发分析时会互相覆盖、误判 503 重发；
 *    这里改为每次请求各自返回状态码。
 *  - 所有维度都解析失败时返回 null，不再插入一条只有「Jev:」的系统消息。
 */
object JevApiClient {

    private const val TAG = "JevApiClient"

    const val DEFAULT_API_URL = "https://api.typesafe.ai/v1/systemone"
    const val DEFAULT_MODEL = "jev-latest"

    /** 上游暂时不可用时的重试间隔（原脚本为 3 秒） */
    private const val RETRY_DELAY_MS = 3_000L

    /** 上游引擎暂时不可用 */
    private const val RETRYABLE_HTTP_CODE = 503

    /** 请求抛异常（连不上/超时）时使用的状态码 */
    private const val HTTP_FAILED = -1

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------ 维度定义

    /** 情绪 */
    private val EMOTION = linkedMapOf(
        "开心" to "愉快、满意",
        "生气" to "不满、恼怒",
        "难过" to "失落、委屈",
        "不安" to "担心、焦虑",
        "平静" to "没情绪波动",
    )

    /** 对方诉求 */
    private val INTENT = linkedMapOf(
        "要态度" to "看我认不认、在不在乎",
        "要行动" to "需要我实际去做",
        "要解释" to "想知道原因",
        "要倾诉" to "听着就行",
        "要表态" to "等我明确答复",
    )

    /** 最佳动作 */
    private val ACTION = linkedMapOf(
        "先安抚" to "先共情，别讲道理",
        "给行动" to "少说，直接做",
        "讲清楚" to "解释原因",
        "简短应承" to "一句话到位",
        "先别回" to "停手，别再说话",
    )

    /** 潜台词（noul = 是/否 二分类） */
    private val SUBTEXT = linkedMapOf(
        "true" to "另有意思",
        "false" to "就是字面意思",
    )

    // ------------------------------------------------------------------ 请求体

    /** 构造请求体：固定骨架 + 「潜台词」一句话插值（不调用任何生成式模型） */
    fun buildRequestBody(latestMessage: String, history: List<String>): JSONObject {
        val state = JSONObject()
        state.put("对方最新消息", latestMessage)
        state.put("最近几轮对话", JSONArray(history))

        val questions = JSONObject()
        questions.put("情绪", choice("对方此刻的主要情绪", EMOTION))
        questions.put("对方诉求", choice("对方真正想要什么", INTENT))
        questions.put("最佳动作", choice("我此刻最该做的", ACTION))
        questions.put(
            "潜台词",
            JSONObject().apply {
                put("type", "noul")
                put("instructions", "对方说「${JevLayout.clip(latestMessage, 30)}」，话里有话")
                put("criteria", JSONObject().apply { SUBTEXT.forEach { (k, v) -> put(k, v) } })
            },
        )

        return JSONObject().apply {
            put("state", state)
            put("model", DEFAULT_MODEL)
            put("questions", questions)
        }
    }

    private fun choice(instructions: String, criteria: Map<String, String>): JSONObject =
        JSONObject().apply {
            put("type", "choice")
            put("instructions", instructions)
            put("criteria", JSONObject().apply { criteria.forEach { (k, v) -> put(k, v) } })
        }

    // ------------------------------------------------------------------ 调用

    /** 一次请求的状态码与响应体；[body] 为 null 表示失败 */
    private class PostResult(val code: Int, val body: String?)

    /**
     * 分析一条消息，返回可直接插入系统消息的多行文本；失败返回 null。
     *
     * 503 视为「上游引擎暂时不可用」，等待 3 秒后重试一次（与原脚本一致）。
     * 本方法是阻塞调用，必须在工作线程执行。
     */
    fun analyze(
        apiKey: String,
        latestMessage: String,
        history: List<String>,
        padChars: Int,
    ): String? {
        if (apiKey.isBlank()) return null
        if (latestMessage.isBlank()) return null

        val payload = buildRequestBody(latestMessage, history).toString()

        val first = post(payload, apiKey)
        val result = if (first.code == RETRYABLE_HTTP_CODE) {
            WeLogger.w(TAG, "[Jev] 请求失败 503（上游引擎暂时不可用），3 秒后重试一次")
            runCatching { Thread.sleep(RETRY_DELAY_MS) }
            post(payload, apiKey)
        } else {
            first
        }

        val response = result.body
        if (response.isNullOrBlank()) return null

        return runCatching {
            val answers = JSONObject(response).optJSONObject("answers") ?: return@runCatching null
            formatAnswers(answers, padChars)
        }.getOrElse {
            WeLogger.e(TAG, "解析 Jev 响应失败", it)
            null
        }
    }

    private fun post(body: String, apiKey: String): PostResult = runCatching {
        val request = Request.Builder()
            .url(DEFAULT_API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body.toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                WeLogger.w(TAG, "[Jev] 请求失败 HTTP ${resp.code} ${JevLayout.clip(text, 160)}")
                PostResult(resp.code, null)
            } else {
                PostResult(resp.code, text)
            }
        }
    }.getOrElse {
        WeLogger.e(TAG, "[Jev] 请求异常", it)
        PostResult(HTTP_FAILED, null)
    }

    // ------------------------------------------------------------------ 响应排版

    /**
     * 把 answers 排成多行文本，每行一个结论；「判断」与「建议」之间用分隔线隔开。
     *
     * 一个维度都没解析出来时返回 null —— 否则会在聊天里插一条只有「Jev:」的系统消息。
     */
    fun formatAnswers(answers: JSONObject, padChars: Int): String? {
        val body = ArrayList<String>()
        val tail = ArrayList<String>()

        body.add("Jev:")

        answers.optJSONObject("潜台词")?.let { sub ->
            if (sub.has("noul")) {
                body.add("话里有话 ${JevLayout.toPercent(sub.optDouble("noul", 0.0))}")
            }
        }

        formatChoice(answers, "情绪", "当前情绪")?.let { body.add(it) }
        formatChoice(answers, "对方诉求", "真实意图")?.let { body.add(it) }
        formatChoice(answers, "最佳动作", "最佳动作")?.let { body.add(it) }

        answers.optJSONObject("最佳动作")?.let { action ->
            val act = action.optString("choice", "")
            actionTip(act).takeIf { it.isNotEmpty() }?.let { tail.add(it) }
            actionExample(act).takeIf { it.isNotEmpty() }?.let { tail.add("「$it」") }
        }

        if (body.size <= 1) return null

        return JevLayout.render(body, tail, padChars)
    }

    /**
     * 把某个 choice 维度排成一行，只显示概率最高的一项。
     * 优先信任服务端返回的 choice；但若 probabilities 里有更高的项，则以概率为准。
     */
    private fun formatChoice(answers: JSONObject, key: String, label: String): String? {
        val item = answers.optJSONObject(key) ?: return null
        val probs = item.optJSONObject("probabilities") ?: return null

        var best = item.optString("choice", "")
        var bestP = probs.optDouble(best, 0.0)

        val keys = probs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = probs.optDouble(k, 0.0)
            if (v > bestP) {
                bestP = v
                best = k
            }
        }
        if (best.isEmpty()) return null
        return "$label $best ${JevLayout.toPercent(bestP)}"
    }

    // ------------------------------------------------------------------ 话术映射

    /** 按动作给出话术要点（判断交给 Jev，话术交给代码） */
    fun actionTip(action: String): String = when (action) {
        "先安抚" -> "先接情绪，别讲道理"
        "给行动" -> "少解释，直接做"
        "讲清楚" -> "把原因说明白"
        "简短应承" -> "一句话到位"
        "先别回" -> "先停手，别急着回"
        else -> ""
    }

    /** 按动作给出一句可直接改用的话术例句 */
    fun actionExample(action: String): String = when (action) {
        "先安抚" -> "这事儿让你不舒服了，是我没上心。"
        "给行动" -> "行，我现在去办，弄好告诉你。"
        "讲清楚" -> "当时是因为……，我没提前说，是我的问题。"
        "简短应承" -> "嗯，记着了。"
        "先别回" -> "等情绪降下来再说。"
        else -> ""
    }
}
