package dev.ujhhgtg.wekit.features.items.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 聊天记录分析 —— OpenAI 兼容 AI 客户端
 *
 * 保持脚本原始请求方式：POST {baseUrl}{path}，Authorization: Bearer <key>，
 * body { model, temperature, stream, messages }。支持流式（SSE data: 行）与非流式降级。
 * 额外提供「测试连接」：拉取模型列表 + 最小对话 ping。
 */
object ChatAnalysisAi {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun buildBody(model: String, sys: String, userContent: String, stream: Boolean): String {
        val body = JSONObject()
        body.put("model", model)
        body.put("temperature", 0.7)
        body.put("stream", stream)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", sys))
        messages.put(JSONObject().put("role", "user").put("content", userContent))
        body.put("messages", messages)
        return body.toString()
    }

    private fun post(config: AiModelConfig, body: String): okhttp3.Response {
        val req = Request.Builder()
            .url(config.endpoint())
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody(jsonType))
            .build()
        return client.newCall(req).execute()
    }

    /** 流式请求：回调增量文本，返回完整文本。失败抛异常。 */
    @Throws(Exception::class)
    fun stream(config: AiModelConfig, sys: String, userContent: String, onDelta: (String) -> Unit = {}): String {
        val body = buildBody(config.model, sys, userContent, stream = true)
        post(config, body).use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val sb = StringBuilder()
            val source = resp.body?.source() ?: throw Exception("响应为空")
            while (true) {
                val line = source.readUtf8Line() ?: break
                val t = line.trim()
                if (!t.startsWith("data:")) continue
                val payload = t.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val choices = obj.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val c = delta.optString("content")
                val rz = delta.optString("reasoning_content")
                if (c.isNotEmpty()) {
                    sb.append(c)
                    onDelta(c)
                }
                if (rz.isNotEmpty()) onDelta(rz)
            }
            return sb.toString()
        }
    }

    /** 非流式请求（流式失败时的降级路径）。 */
    @Throws(Exception::class)
    fun plain(config: AiModelConfig, sys: String, userContent: String): String? {
        val body = buildBody(config.model, sys, userContent, stream = false)
        post(config, body).use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val text = resp.body?.string() ?: return null
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
    }

    /**
     * 测试连接：GET {baseUrl}/models 拉取模型列表（OpenAI 兼容），再发一次最小对话验证。
     */
    @Throws(Exception::class)
    fun testConnection(config: AiModelConfig): AiTestResult {
        val b = config.baseUrl.trim().trimEnd('/')
        val models = runCatching { fetchModels(b, config.apiKey) }.getOrDefault(emptyList())

        // 最小对话 ping（非流式，max_tokens 小）
        val pingBody = JSONObject()
        pingBody.put("model", config.model.ifEmpty { models.firstOrNull() ?: "gpt-4o-mini" })
        pingBody.put("stream", false)
        pingBody.put("max_tokens", 16)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "user").put("content", "ping"))
        pingBody.put("messages", messages)

        val req = Request.Builder()
            .url(config.endpoint())
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(pingBody.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val reply = obj?.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            val msg = if (reply.isNotBlank()) {
                "✅ 连接成功，模型已响应：${reply.take(60)}"
            } else {
                "✅ 连接成功（HTTP ${resp.code}），但未解析到回复内容"
            }
            return AiTestResult(
                success = true,
                message = msg,
                models = models,
            )
        }
    }

    /** 拉取 OpenAI 兼容 /models 列表。 */
    @Throws(Exception::class)
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val b = baseUrl.trim().trimEnd('/')
        val req = Request.Builder()
            .url("$b/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = obj.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            return out.distinct().sorted()
        }
    }
}
