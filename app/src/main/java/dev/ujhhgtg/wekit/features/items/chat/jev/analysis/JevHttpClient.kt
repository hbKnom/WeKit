package dev.ujhhgtg.wekit.features.items.chat.jev.analysis

import android.os.SystemClock
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiSettings
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jev 协议 HTTP 客户端。Never reports raw service bodies.
 *
 * 合并后新增两件事，直接对着用户实测的「有些消息能出结果、有些出不来」：
 *
 *  1. **全局限速**（[MIN_INTERVAL_MS]）：进入会话时本屏多条消息会同时提交分析，
 *     原来这些请求会一起打出去，撞上免费额度/频率限制（例如 OpenRouter 免费模型
 *     每天 50 次、每分钟若干次）就会出现「一部分成功、一部分 429」。
 *     现在所有请求串行且间隔不小于 [MIN_INTERVAL_MS]，把突发摊平。
 *  2. **可重试失败自动退避重试**（429 / 408 / 5xx / 网络中断）：失败一次不再直接报错，
 *     最多重试 [MAX_RETRIES] 次；仍然失败时，报错文案里会写明「已自动重试 N 次」，
 *     用户能区分「额度/限流」和「配置错误」。
 */
class JevHttpClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private val calls = AtomicInteger()
    private var lastCallAt = 0L

    /** 本次进程已经发出的请求数（含重试）。设置页用它解释额度与限流。 */
    val requestCount: Int get() = calls.get()

    /** 「等一下就好了」的失败：交给 [exchange] 退避重试。 */
    private class Retryable(reason: String) : IllegalStateException(reason)

    fun exchange(payload: JSONObject, settings: ApiSettings): String {
        check(settings.isConfigured) { "请先填写并保存 API Key" }
        var attempt = 0
        while (true) {
            throttle()
            try {
                return post(payload, settings)
            } catch (e: Retryable) {
                if (attempt >= MAX_RETRIES) {
                    throw IllegalStateException("${e.message}（已自动重试 $MAX_RETRIES 次仍未成功）")
                }
                attempt++
                val rest = RETRY_BACKOFF_MS * attempt
                MoodLog.w("请求失败，${rest}ms 后第 $attempt 次重试：${e.message}")
                runCatching { Thread.sleep(rest) }
            }
        }
    }

    /** 全局限速：串行 + 最小间隔，避免一屏消息把额度一次打光。 */
    @Synchronized
    private fun throttle() {
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_INTERVAL_MS - (now - lastCallAt)
        if (wait > 0) runCatching { Thread.sleep(wait) }
        lastCallAt = SystemClock.elapsedRealtime()
    }

    private fun post(payload: JSONObject, settings: ApiSettings): String {
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        calls.incrementAndGet()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val httpOk = response.isSuccessful && json?.has("error") != true
                if (httpOk) return body

                val error = json?.optJSONObject("error")
                val code = if (response.isSuccessful) {
                    error?.optInt("code", response.code) ?: response.code
                } else {
                    response.code
                }
                val reason = when {
                    error?.optString("type") == "customer_verification_required" ->
                        "Vercel 账户尚未验证，请到 AI Gateway 控制台绑定信用卡后重试"
                    code == 401 -> "API Key 无效，请检查所选渠道与密钥是否对应"
                    code == 403 -> "账户或模型尚未授权，请到所选渠道控制台检查"
                    code == 402 -> "模型账户额度不足，请到所选渠道充值或检查额度"
                    code == 429 -> "请求过于频繁或当日免费额度已用尽，请稍后重试或到渠道控制台查看额度"
                    code == 400 || code == 404 || code == 422 -> "接口地址或模型不受支持，请检查渠道和配置"
                    code in 300..399 -> "接口发生重定向，请填写最终的 Jev 接口地址"
                    else -> "模型服务暂不可用，请稍后重试"
                }
                // 429 / 408 / 5xx 是「等一下再来」；其余是配置问题，重试没有意义。
                if (code == 429 || code == 408 || response.code >= 500) throw Retryable(reason)
                throw IllegalStateException("$reason（HTTP ${response.code}）")
            }
        } catch (e: java.io.IOException) {
            throw Retryable("连接超时或网络不可用")
        }
    }

    private companion object {
        /**
         * 请求之间的最小间隔：把「一屏多条同时分析」的突发摊平到额度允许的速率。
         *
         * 第 14 轮从 1200ms 收到 700ms：每条消息要两轮请求（[JevProtocol.payload] +
         * [JevProtocol.detailPayload]），1.2s 的间隔让一屏 12 条消息要等两分钟，
         * 用户看到的就是「有的行半天不出结果」。700ms 仍能把突发摊平，
         * 真被限流时 [exchange] 还有退避重试兜着。
         */
        const val MIN_INTERVAL_MS = 700L

        /** 可重试失败的最大重试次数。 */
        const val MAX_RETRIES = 2

        /** 退避基数：第 n 次重试等 n × 该值。 */
        const val RETRY_BACKOFF_MS = 900L
    }
}
