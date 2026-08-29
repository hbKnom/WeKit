package dev.ujhhgtg.wekit.features.items.chat

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.History
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.agent.model.local.LOCAL_LLAMA_MIN_CONTEXT_WINDOW
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaModels
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 聊天记录分析
 *
 * 迁移自 Hchat 脚本「聊天记录分析 v0.3.4」：
 * 长按消息 → 选择时间范围（今天/昨天/本周/上周/本月/上月）→
 * 本地统计报告（类型分布/活跃时段/发言排行/高频词/情绪指纹/废话程度鉴定）+
 * AI 总结（复用 WeAgent 配置的默认模型，非流式调用）。
 */
object ChatRecordAnalysis : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "聊天记录分析"
    override val nameRes = R.string.feature_chat_record_analysis_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_record_analysis_description

    /** 单次最多取的消息条数，0 = 不限制（脚本默认 0，配置项 ana_max_count） */
    private var maxCount by prefOption("chat_analysis_max_count", 0)

    /** 喂给 AI 的最大文本条数，超出部分等距抽样（脚本默认 500，配置项 ana_sample_limit） */
    private var sampleLimit by prefOption("chat_analysis_sample_limit", 500)

    private val rangeLabels = listOf("今天", "昨天", "本周", "上周", "本月", "上月")

    @Volatile
    private var busy = false

    private var gTalker = ""
    private var gLabel = ""
    private var gTranscript = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777266,
                localizedChatString(R.string.chat_record_analysis_menu),
                ChatInfoIcon, MaterialSymbols.Outlined.History, { _ -> true },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                if (busy) {
                    view.context.showToast(localizedChatString(R.string.chat_record_analysis_busy))
                    return@MenuItem
                }
                gTalker = msgInfo.talker
                showRangePicker(view)
            }
        )
    }

    /* ---------------- 时间范围选择 ---------------- */

    private fun showRangePicker(view: View) {
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_record_analysis_range_title)) },
                text = {
                    LazyColumn {
                        itemsIndexed(rangeLabels) { index, label ->
                            BaseWidget(
                                title = label,
                                description = stringResource(R.string.chat_record_analysis_range_hint, talkerDisplayName(gTalker)),
                                onClick = {
                                    onDismiss()
                                    startAnalysis(index)
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    /* ---------------- 后台统计 ---------------- */

    private fun startAnalysis(mode: Int) {
        if (busy) return
        busy = true
        val talker = gTalker
        val context = HostInfo.application
        Thread {
            try {
                val result = doAnalyze(talker, mode)
                mainHandler.post {
                    busy = false
                    if (result == null) {
                        showToast(localizedChatString(R.string.chat_record_analysis_no_text, gLabel))
                        return@post
                    }
                    showReport(context, result)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    busy = false
                    showToast("分析失败：" + (e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    /** 返回统计报告文本；该时段无纯文本消息时返回 null */
    private fun doAnalyze(talker: String, mode: Int): String? {
        val now = System.currentTimeMillis()
        val range = timeRange(mode, now)
        gLabel = rangeLabels[mode]
        val isGroup = talker.endsWith("@chatroom")

        val rows = queryRows(talker, range.first, range.second, maxCount)

        val texts = mutableListOf<Pair<String, String>>()
        val typeCount = mutableMapOf<String, Int>()
        val hourDist = IntArray(24)
        val rank = mutableMapOf<String, Int>()
        val wordMap = mutableMapOf<String, Int>()
        var totalAll = 0
        var laugh = 0
        var question = 0
        var exclaim = 0
        var wave = 0
        var speechless = 0
        var lenShort = 0
        var lenMid = 0
        var lenLong = 0
        var lenHuge = 0
        var atMe = 0

        val myWxid = WeApi.selfWxId
        val myNick = runCatching { WeDatabaseApi.getDisplayName(myWxid) }.getOrDefault("")

        val hc = Calendar.getInstance()
        for (m in rows) {
            val ct = (m["createTime"] as? Number)?.toLong() ?: 0L
            if (ct <= 0L) continue
            if (ct < range.first || ct >= range.second) continue
            totalAll++

            val type = (m["type"] as? Number)?.toInt() ?: 0
            val tn = typeName(type)
            typeCount[tn] = (typeCount[tn] ?: 0) + 1

            hc.timeInMillis = ct
            hourDist[hc.get(Calendar.HOUR_OF_DAY)]++

            val sent = (m["isSend"] as? Number)?.toLong() == 1L
            var content = m["content"]?.toString() ?: ""

            // 发言排行按原版口径统计全部非系统消息
            if (type != 10000) {
                val rankKey = when {
                    sent -> "我"
                    isGroup -> groupSenderFromContent(content).ifEmpty { "群友" }
                    else -> "对方"
                }
                rank[rankKey] = (rank[rankKey] ?: 0) + 1
            }

            if (content.contains("哈") || content.contains("笑")) laugh++
            if (content.contains("?") || content.contains("？") || content.endsWith("吗")) question++
            if (content.contains("!") || content.contains("！")) exclaim++
            if (content.contains("~") || content.contains("～")) wave++
            if (content.contains("...") || content.contains("。。。") || content.contains("无语")) speechless++

            val len = content.length
            when {
                len <= 5 -> lenShort++
                len <= 20 -> lenMid++
                len <= 50 -> lenLong++
                else -> lenHuge++
            }

            if (type == 1) {
                var senderKey: String
                var body = content
                if (sent) {
                    senderKey = "我"
                } else if (isGroup) {
                    val wx = groupSenderFromContent(body)
                    if (wx.isNotEmpty()) {
                        senderKey = wx
                        body = stripGroupSenderPrefix(body, wx)
                    } else {
                        senderKey = "群友"
                    }
                } else {
                    senderKey = "对方"
                }
                if (body.startsWith("@") &&
                    (body.contains(myWxid) || (myNick.isNotEmpty() && body.contains(myNick)) || body.contains("所有人"))
                ) {
                    atMe++
                }
                texts.add(senderKey to body)
            }
        }

        if (texts.isEmpty()) return null

        /* 等距抽样，保留时间分布 */
        val limit = if (sampleLimit < 1) 1 else sampleLimit
        val sampled = if (texts.size > limit) {
            val stride = texts.size.toDouble() / limit
            List(limit) { k -> texts[(k * stride).toInt()] }
        } else {
            texts
        }

        val nickCache = mutableMapOf<String, String>()
        val sb = StringBuilder()
        for ((key, rawBody) in sampled) {
            val dn = speakerDisplayName(key, talker, isGroup, nickCache)
            var body = rawBody
            if (body.length > 200) body = body.substring(0, 200) + "…"
            sb.append("[").append(dn).append("]: ").append(body).append("\n")
            countWords(body, wordMap)
        }
        gTranscript = sb.toString()

        return buildLocalReport(
            talker = talker,
            isGroup = isGroup,
            totalAll = totalAll,
            textN = texts.size,
            typeCount = typeCount,
            hourDist = hourDist,
            rank = rank,
            nickCache = nickCache,
            wordMap = wordMap,
            laugh = laugh,
            question = question,
            exclaim = exclaim,
            wave = wave,
            speechless = speechless,
            lenShort = lenShort,
            lenMid = lenMid,
            lenLong = lenLong,
            lenHuge = lenHuge,
            atMe = atMe,
        )
    }

    private fun queryRows(talker: String, start: Long, end: Long, max: Int): List<Map<String, Any?>> {
        val sql = StringBuilder()
        sql.append("SELECT msgId,msgSvrId,talker,content,createTime,type,isSend FROM message ")
        sql.append("WHERE talker=? AND createTime>=? AND createTime<? ORDER BY createTime ASC")
        val args = mutableListOf<Any>(talker, start, end)
        if (max > 0) {
            sql.append(" LIMIT ?")
            args.add(max)
        }
        return runCatching { WeDatabaseApi.executeQuery(sql.toString(), args.toTypedArray()) }
            .getOrDefault(emptyList())
    }

    /* ---------------- 本地统计报告 ---------------- */

    private fun buildLocalReport(
        talker: String,
        isGroup: Boolean,
        totalAll: Int,
        textN: Int,
        typeCount: Map<String, Int>,
        hourDist: IntArray,
        rank: Map<String, Int>,
        nickCache: MutableMap<String, String>,
        wordMap: Map<String, Int>,
        laugh: Int,
        question: Int,
        exclaim: Int,
        wave: Int,
        speechless: Int,
        lenShort: Int,
        lenMid: Int,
        lenLong: Int,
        lenHuge: Int,
        atMe: Int,
    ): String {
        val r = StringBuilder()
        r.append("【核心指标】\n")
        r.append("时段：").append(gLabel).append("\n")
        r.append("消息总数：").append(totalAll).append(" 条（纯文本 ").append(textN).append(" 条）\n")
        if (isGroup) r.append("文本发言人数：").append(rank.size).append("\n")
        else r.append("会话类型：私聊（我 / 对方）\n")
        if (atMe > 0) r.append("被 @ 次数：").append(atMe).append("\n")

        r.append("\n【内容载体偏好】\n")
        val tk = topKeys(typeCount, 6)
        if (tk.isNotEmpty()) {
            val tMax = typeCount[tk[0]] ?: 1
            for (k in tk) {
                val v = typeCount[k] ?: 0
                r.append(k).append(" ").append(v).append(" ").append(bar(v, tMax, 16)).append("\n")
            }
        }

        var hMax = 0
        var hPeak = 0
        for (h in 0 until 24) {
            if (hourDist[h] > hMax) {
                hMax = hourDist[h]
                hPeak = h
            }
        }
        r.append("\n【全天活跃频次】\n")
        r.append("最活跃时段：").append(hPeak).append(" 点（").append(hMax).append(" 条）\n")
        val bandNames = listOf("凌晨0-5", "上午6-11", "中午12-13", "下午14-17", "傍晚18-19", "夜晚20-23")
        val bands = listOf(0 to 6, 6 to 12, 12 to 14, 14 to 18, 18 to 20, 20 to 24)
        val bandSum = IntArray(6)
        var bMax = 0
        for (b in 0 until 6) {
            var sum = 0
            for (h in bands[b].first until bands[b].second) sum += hourDist[h]
            bandSum[b] = sum
            if (sum > bMax) bMax = sum
        }
        for (b in 0 until 6) {
            r.append(bandNames[b]).append("点 ").append(bandSum[b]).append(" ")
                .append(bar(bandSum[b], bMax, 16)).append("\n")
        }

        r.append("\n").append(if (isGroup) "【发言排行 Top10】" else "【发言对比】").append("\n")
        val rk = topKeys(rank, 10)
        if (rk.isNotEmpty()) {
            val rMax = rank[rk[0]] ?: 1
            for ((i, key) in rk.withIndex()) {
                val v = rank[key] ?: 0
                val dn = speakerDisplayName(key, talker, isGroup, nickCache)
                r.append(i + 1).append(". ").append(dn).append("：").append(v).append(" 条 ")
                    .append(bar(v, rMax, 16)).append("\n")
            }
        }

        if (wordMap.isNotEmpty()) {
            r.append("\n【高频词】\n")
            val wk = topKeys(wordMap, 12)
            for ((i, k) in wk.withIndex()) {
                r.append(k).append("×").append(wordMap[k])
                if (i < wk.size - 1) r.append("  ")
            }
            r.append("\n")
        }

        r.append("\n【情绪指纹】\n")
        r.append("哈哈哈浓度：").append(pct(laugh, textN)).append("%\n")
        r.append("疑问句比例：").append(pct(question, textN)).append("%\n")
        r.append("感叹号比例：").append(pct(exclaim, textN)).append("%\n")
        r.append("波浪号比例：").append(pct(wave, textN)).append("%\n")
        r.append("无语指数　：").append(pct(speechless, textN)).append("%\n")

        r.append("\n【废话程度鉴定】\n")
        r.append("≤5字：").append(pct(lenShort, textN)).append("%　≤20字：").append(pct(lenMid, textN)).append("%\n")
        r.append("≤50字：").append(pct(lenLong, textN)).append("%　>50字：").append(pct(lenHuge, textN)).append("%\n")
        when {
            pct(lenShort, textN) >= 60 -> r.append("鉴定：全员惜字如金\n")
            pct(lenHuge, textN) >= 15 -> r.append("鉴定：小作文大户实锤\n")
            else -> r.append("鉴定：正常人类浓度\n")
        }

        return r.toString()
    }

    /* ---------------- 报告展示 ---------------- */

    private fun showReport(context: android.content.Context, report: String) {
        showComposeDialog(context) {
            var showAi by remember { mutableStateOf(false) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_record_analysis_report_title, talkerDisplayName(gTalker), gLabel)) },
                text = {
                    if (showAi) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            BaseWidget(
                                title = stringResource(R.string.chat_record_analysis_ai_title),
                                description = stringResource(R.string.chat_record_analysis_ai_running),
                            )
                        }
                        LazyColumn {
                            itemsIndexed(report.lines()) { _, line -> Text(line) }
                        }
                    } else {
                        LazyColumn {
                            itemsIndexed(report.lines()) { _, line -> Text(line) }
                        }
                    }
                },
                confirmButton = {
                    if (!showAi) {
                        Button({
                            showAi = true
                            startAiSummary()
                        }) { Text(stringResource(R.string.chat_record_analysis_ai_button)) }
                    }
                    Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    /* ---------------- AI 总结 ---------------- */

    private fun startAiSummary() {
        if (busy) return
        busy = true
        val context = HostInfo.application
        Thread {
            try {
                val result = runBlocking {
                    val modelId = WeAgentSettings.defaultModelId() ?: return@runBlocking null
                    val model = WeAgentRepository.getModel(modelId) ?: return@runBlocking null
                    val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@runBlocking null

                    val sys = "你是一个微信聊天分析助手。请根据以下聊天记录，总结出这段时间内大家聊了哪些主要内容，" +
                        "重点话题，整体氛围如何，并提取一些有趣的点。语言请幽默生动，排版清晰。如果记录较少请简短回复。"
                    val userContent = "聊天记录：\n" + gTranscript
                    val messages = listOf(
                        LlmMessage(role = LlmRole.SYSTEM, content = sys),
                        LlmMessage(role = LlmRole.USER, content = userContent),
                    )
                    val request = ModelProviderManager.buildRequest(model, messages, emptyList(), stream = false)
                    var reply: String? = null
                    when (provider.type) {
                        ModelProviderType.LOCAL_LLAMA -> {
                            val gguf = LocalLlamaModels.resolveModelFile(model.modelIdRemote)
                                ?: return@runBlocking null
                            ModelProviderManager.localClientFor(
                                provider = provider,
                                modelIdRemote = model.modelIdRemote,
                                nCtx = LocalLlamaModels.defaultContextWindow(model.modelIdRemote)
                                    ?: LOCAL_LLAMA_MIN_CONTEXT_WINDOW,
                                backend = "auto",
                            ).stream(request).collect { event ->
                                when (event) {
                                    is LlmStreamEvent.Completed -> reply = event.message.content
                                    is LlmStreamEvent.Failed -> throw event.error
                                    else -> Unit
                                }
                            }
                        }

                        else -> {
                            ModelProviderManager.clientFor(provider).stream(request).collect { event ->
                                when (event) {
                                    is LlmStreamEvent.Completed -> reply = event.message.content
                                    is LlmStreamEvent.Failed -> throw event.error
                                    else -> Unit
                                }
                            }
                        }
                    }
                    reply
                }
                mainHandler.post {
                    busy = false
                    if (result.isNullOrBlank()) {
                        showToast(localizedChatString(R.string.chat_record_analysis_ai_failed))
                    } else {
                        showAiReport(context, result)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    busy = false
                    showToast("AI 分析失败：" + (e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun showAiReport(context: android.content.Context, aiText: String) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_record_analysis_ai_title)) },
                text = {
                    LazyColumn {
                        itemsIndexed(aiText.lines()) { _, line -> Text(line) }
                    }
                },
                confirmButton = {
                    Button({
                        copyToClipboard(aiText)
                        showToast(localizedChatString(R.string.chat_record_analysis_copied))
                    }) { Text(stringResource(R.string.chat_record_analysis_copy)) }
                    Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    /* ---------------- 辅助函数 ---------------- */

    private fun timeRange(mode: Int, now: Long): Pair<Long, Long> {
        val d = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = d.timeInMillis
        val yesterdayStart = todayStart - 86_400_000L

        val w = d.clone() as Calendar
        w.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        var mondayStart = w.timeInMillis
        if (mondayStart > todayStart) mondayStart -= 7L * 86_400_000L
        val lastMondayStart = mondayStart - 7L * 86_400_000L

        val m = d.clone() as Calendar
        m.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = m.timeInMillis
        val lm = m.clone() as Calendar
        lm.add(Calendar.MONTH, -1)
        val lastMonthStart = lm.timeInMillis

        return when (mode) {
            0 -> todayStart to now
            1 -> yesterdayStart to todayStart
            2 -> mondayStart to now
            3 -> lastMondayStart to mondayStart
            4 -> monthStart to now
            else -> lastMonthStart to monthStart
        }
    }

    private fun typeName(t: Int): String = when (t) {
        1 -> "文字"
        3 -> "图片"
        34 -> "语音"
        43 -> "视频"
        47 -> "表情"
        48 -> "位置"
        49 -> "卡片/链接"
        10000 -> "系统"
        10002 -> "撤回"
        419430449 -> "转账"
        436207665 -> "红包"
        else -> "其他"
    }

    /** Top-K 选择（原版：逐轮找最大，避免全量排序） */
    private fun topKeys(m: Map<String, Int>, k: Int): List<String> {
        if (m.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val used = mutableSetOf<String>()
        val n = minOf(m.size, k)
        repeat(n) {
            var bestKey: String? = null
            var bestVal = -1
            for ((key, v) in m) {
                if (key in used) continue
                if (v > bestVal) {
                    bestVal = v
                    bestKey = key
                }
            }
            if (bestKey == null) return@repeat
            used.add(bestKey)
            out.add(bestKey)
        }
        return out
    }

    private fun bar(v: Int, max: Int, width: Int): String {
        if (max <= 0) return ""
        val n = (v.toDouble() / max.toDouble() * width).roundToInt()
        return "█".repeat(n.coerceIn(0, width))
    }

    private fun pct(part: Int, total: Int): Int = if (total <= 0) 0 else (part.toDouble() / total.toDouble() * 100.0).roundToInt()

    /** 词频：中文按 2-4 字窗口切分，跳过纯数字 */
    private fun countWords(text: String, out: MutableMap<String, Int>) {
        if (text.isEmpty()) return
        val buf = StringBuilder()
        for (i in 0..text.length) {
            var keep = false
            if (i < text.length) {
                val ch = text[i]
                keep = ch in '\u4e00'..'\u9fff' || ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
            }
            if (keep) {
                buf.append(text[i])
            } else {
                emitWords(buf.toString(), out)
                buf.setLength(0)
            }
        }
    }

    private fun emitWords(run: String, out: MutableMap<String, Int>) {
        val n = run.length
        if (n < 2) return
        var digitOnly = true
        for (ch in run) {
            if (ch !in '0'..'9') {
                digitOnly = false
                break
            }
        }
        if (digitOnly) return
        val maxWin = minOf(n, 4)
        for (win in 2..maxWin) {
            for (s in 0..n - win) {
                val w = run.substring(s, s + win)
                out[w] = (out[w] ?: 0) + 1
            }
        }
    }

    /** 群消息 content 前缀提取发送者 wxid：`wxid:\n内容` 或 `wxid:内容` */
    private fun groupSenderFromContent(content: String): String {
        var p = content.indexOf(":\n")
        if (p >= 1 && p <= 41) return content.substring(0, p).trim()
        p = content.indexOf(":")
        if (p >= 1 && p <= 41) return content.substring(0, p).trim()
        return ""
    }

    private fun stripGroupSenderPrefix(body: String, wx: String): String {
        var cut = body.indexOf(":\n")
        if (cut >= 1 && cut <= 41 && body.substring(0, cut).trim() == wx) return body.substring(cut + 2)
        cut = body.indexOf(":")
        if (cut >= 1 && cut <= 41 && body.substring(0, cut).trim() == wx) return body.substring(cut + 1)
        return body
    }

    /** 说话人显示名：我 / 对方 / 群成员昵称 */
    private fun speakerDisplayName(key: String, talker: String, isGroup: Boolean, nickCache: MutableMap<String, String>): String {
        if (key == "我") return "我"
        if (key == "对方") return talkerDisplayName(talker)
        if (key == "群友") return "群友"
        if (!isGroup) return key
        return nickCache.getOrPut(key) {
            runCatching { WeDatabaseApi.getGroupMemberDisplayNameMap(talker)[key] }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: key
        }
    }

    private fun talkerDisplayName(talker: String): String =
        runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrNull()?.takeIf { it.isNotBlank() } ?: talker
}
