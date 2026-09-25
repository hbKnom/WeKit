package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 聊天记录分析 —— 分析引擎
 *
 * 完整复刻 WeKit Java 脚本 v0.3.4 的统计口径：
 * 核心指标 / 内容载体偏好 / 全天活跃频次 / 发言排行 / 高频词 / 情绪指纹 / 废话程度鉴定。
 * 查询使用 WeDatabaseApi（WCDB 主数据库）范围 SQL，分页读取避免一次性 OOM。
 */
object ChatAnalysisEngine {

    const val FEATURE_AI = "AI 总结"
    const val FEATURE_STATS = "本地统计"
    const val FEATURE_RANK = "发言排行"
    val ALL_FEATURES = listOf(FEATURE_AI, FEATURE_STATS, FEATURE_RANK)

    /** 每页查询条数（防止大群全量 OOM） */
    private const val PAGE_SIZE = 1000

    /**
     * 单条消息喂给 AI 的字符上限（超出截断，避免一条长文吃掉整个上下文）。
     *
     * 用户 2026-09-22 反馈「内容文本的上限真的极少」：原值 500 字，一条长消息（比如群里
     * 转发的长文、长公告）几乎只剩开头。默认放到 2000 字，并且改成可以由
     * [ChatAnalysisEngine.analyze] 的 `lineMax` 参数覆盖（设置页可调）。
     */
    const val TRANSCRIPT_LINE_MAX_DEFAULT = 4000

    /**
     * 喂给 AI 的整段对话文本总量的**默认**硬上限。
     *
     * 用户反馈原值 60000 字太少（2026-09-22 二轮反馈「内容文本的上限极少」，再放到 48 万字）、内容不够 AI 容易答错，这里默认放到 240000 字
     * （中文约 1 字 ≈ 0.6~1 token，24 万字约 15~24 万 token，适配 32 万上下文的模型；
     * 小上下文模型请把设置里的「喂给 AI 的文本上限」调小，否则服务端会返回上下文超限）。
     * 真正的硬上限由调用方按设置传入，这里只是兜底默认值。
     */
    const val TRANSCRIPT_MAX_CHARS_DEFAULT = 480_000

    /** 数据库未就绪时的提示，由调用方展示 */
    val dbReady: Boolean get() = runCatching { WeDatabaseApi.isReady }.getOrDefault(false)

    // ---------------- 时间范围（与脚本 timeRange 完全一致） ----------------

    fun timeRange(mode: Int, now: Long): Pair<Long, Long> {
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

    // ---------------- 查询：分批读取并实时统计 ----------------

    /**
     * 执行分析。返回统计报告；该时段无纯文本消息时 statsReport 为 null（AI 仍可提示）。
     * 抛出的异常由调用方展示。
     */
    fun analyze(
        talker: String,
        mode: Int,
        maxCount: Int,
        sampleLimit: Int,
        features: Set<String>,
        onProgress: ((Int, Int) -> Unit)? = null,
        lineMax: Int = TRANSCRIPT_LINE_MAX_DEFAULT,
        transcriptMaxChars: Int = TRANSCRIPT_MAX_CHARS_DEFAULT,
    ): AnalyzeResult {
        val now = System.currentTimeMillis()
        val range = timeRange(mode, now)
        val start = range.first
        val end = range.second
        val isGroup = talker.isGroupChatWxId

        val typeCount = mutableMapOf<String, Int>()
        val hourDist = IntArray(24)
        val rank = mutableMapOf<String, Int>()
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
        // 第 13 轮扩展的四个维度：按星期分布、消息间隔（互动节奏）、连发长度、最长单条。
        val weekday = IntArray(7)
        var gapSum = 0L
        var gapCount = 0
        var maxGapMs = 0L
        var prevCt = 0L
        var streak = 0
        var maxStreak = 0
        var streakKey = ""
        var longestLen = 0
        var longestFromKey = ""

        val textSenders = mutableListOf<String>()
        val textBodies = mutableListOf<String>()

        val myWxid = runCatching { WeApi.selfWxId }.getOrDefault("")
        val myNick = runCatching { WeDatabaseApi.getDisplayName(myWxid) }.getOrDefault("")

        val hc = Calendar.getInstance()
        var offset = 0
        var fetchedTotal = 0
        while (true) {
            val page = queryPage(talker, start, end, PAGE_SIZE, offset, maxCount)
            if (page.isEmpty()) break
            for (m in page) {
                val ct = (m["createTime"] as? Number)?.toLong()
                    ?: m["createTime"]?.toString()?.toLongOrNull()
                    ?: 0L
                if (ct <= 0L) continue
                if (ct < start || ct >= end) continue
                totalAll++

                val type = (m["type"] as? Number)?.toInt()
                    ?: m["type"]?.toString()?.toIntOrNull()
                    ?: 0
                val tn = typeName(type)
                typeCount[tn] = (typeCount[tn] ?: 0) + 1

                hc.timeInMillis = ct
                hourDist[hc.get(Calendar.HOUR_OF_DAY)]++
                // Calendar.DAY_OF_WEEK 周日=1，这里折成 ISO 的「周一=0 … 周日=6」
                weekday[(hc.get(Calendar.DAY_OF_WEEK) + 5) % 7]++
                if (prevCt > 0L) {
                    val gap = ct - prevCt
                    if (gap > 0L) {
                        gapSum += gap
                        gapCount++
                        if (gap > maxGapMs) maxGapMs = gap
                    }
                }
                prevCt = ct

                val sent = (m["isSend"] as? Number)?.toLong() == 1L
                    || m["isSend"]?.toString() == "1"
                val content = m["content"]?.toString() ?: ""

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
                        (
                            (myWxid.isNotEmpty() && body.contains(myWxid)) ||
                                (myNick.isNotEmpty() && body.contains(myNick)) ||
                                body.contains("所有人")
                        )
                    ) {
                        atMe++
                    }
                    if (senderKey == streakKey) {
                        streak++
                    } else {
                        streakKey = senderKey
                        streak = 1
                    }
                    if (streak > maxStreak) maxStreak = streak
                    if (body.length > longestLen) {
                        longestLen = body.length
                        longestFromKey = senderKey
                    }
                    textSenders.add(senderKey)
                    textBodies.add(body)
                }
            }
            fetchedTotal += page.size
            onProgress?.invoke(fetchedTotal, totalAll)
            offset += page.size
            // 读满上限 或 最后一页不满一页（已读完）
            if ((maxCount > 0 && offset >= maxCount) || page.size < PAGE_SIZE) break
        }

        val textN = textSenders.size
        if (textN == 0) {
            return AnalyzeResult(statsReport = "", totalAll = totalAll, textN = 0)
        }

        // 等距抽样，保留时间分布（脚本 step 语义）
        // sampleLimit <= 0 = 不抽样：该时段的纯文本消息**全部**喂给 AI。
        // 用户 2026-09-22 第二次反馈「条数只有 20000 的上限真的极少」——真正的兜底是下面
        // 的整段字数上限（transcriptMaxChars），条数不该再额外卡一道。
        val limit = if (sampleLimit <= 0) Int.MAX_VALUE else sampleLimit
        val sampledIdx: List<Int> = if (textN > limit) {
            val step = ceil(textN.toDouble() / limit).toInt().coerceAtLeast(1)
            (0 until textN step step).toList()
        } else {
            (0 until textN).toList()
        }

        val nickCache = mutableMapOf<String, String>()
        val sb = StringBuilder()
        val wordMap = mutableMapOf<String, Int>()
        val effectiveLineMax = if (lineMax < 100) 100 else lineMax
        val effectiveMaxChars = if (transcriptMaxChars < 2000) 2000 else transcriptMaxChars
        var included = 0
        for (k in sampledIdx) {
            val key = textSenders[k]
            val rawBody = textBodies[k]
            val dn = speakerDisplayName(key, talker, isGroup, nickCache)
            var body = rawBody
            if (body.length > effectiveLineMax) {
                body = body.substring(0, effectiveLineMax) + "…"
            }
            // 喂给 AI 的对话文本必须有硬上限：抽样后大群仍可能十几万字，整段发出去会被服务端
            // 判上下文超限（就是用户看到的"AI 返回错误"），所以到量就停并注明截断。
            if (sb.length + dn.length + body.length + 8 > effectiveMaxChars) break
            sb.append("[").append(dn).append("]: ").append(body).append("\n")
            countWords(body, wordMap)
            included++
        }
        // 不论有没有截断都注明一次收录情况：用户反馈"看不出上限到底是多少"，
        // 这行会一起进 AI 正文和报告，条数/字数上限一目了然。
        // 正文字数必须在**追加这行之前**取，否则量到的是"正文 + 本行已写的部分"。
        val bodyChars = sb.length
        sb.append("…（本次共读取该时段纯文本 ").append(textN).append(" 条，收录 ")
            .append(included).append(" 条，正文 ").append(bodyChars).append(" 字 / 上限 ")
            .append(effectiveMaxChars).append(" 字）\n")
        val transcript = sb.toString()

        val report = if (features.contains(FEATURE_STATS)) {
            buildLocalReport(
                talker = talker,
                isGroup = isGroup,
                totalAll = totalAll,
                textN = textN,
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
                weekday = weekday,
                gapSum = gapSum,
                gapCount = gapCount,
                maxGapMs = maxGapMs,
                maxStreak = maxStreak,
                longestLen = longestLen,
                longestFromKey = longestFromKey,
                showRank = features.contains(FEATURE_RANK),
            )
        } else {
            ""
        }

        return AnalyzeResult(
            statsReport = report,
            transcript = transcript,
            totalAll = totalAll,
            textN = textN,
        )
    }

    /** 分页查询：LIMIT ? OFFSET ?；maxCount>0 时最后一页按剩余量截断。 */
    private fun queryPage(
        talker: String,
        start: Long,
        end: Long,
        limit: Int,
        offset: Int,
        maxCount: Int,
    ): List<Map<String, Any?>> {
        var effLimit = limit
        if (maxCount > 0) {
            val remain = maxCount - offset
            if (remain <= 0) return emptyList()
            if (remain < effLimit) effLimit = remain
        }
        val sql = "SELECT msgId,msgSvrId,talker,content,createTime,type,isSend FROM message " +
            "WHERE talker=? AND createTime>=? AND createTime<? ORDER BY createTime ASC LIMIT ? OFFSET ?"
        val args = mutableListOf<Any>(talker, start, end, effLimit, offset)
        return runCatching {
            WeDatabaseApi.executeQuery(sql, args.toTypedArray())
        }.getOrDefault(emptyList())
    }

    // ---------------- 本地统计报告（口径与脚本一致） ----------------

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
        weekday: IntArray,
        gapSum: Long,
        gapCount: Int,
        maxGapMs: Long,
        maxStreak: Int,
        longestLen: Int,
        longestFromKey: String,
        showRank: Boolean,
    ): String {
        val r = StringBuilder()
        r.append("【核心指标】\n")
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

        // 第 13 轮扩展：光看「几点活跃」看不出「哪天活跃」，周末/工作日结构对群聊尤其有信息量。
        if (weekday.sum() > 0) {
            r.append("\n【活跃日历】\n")
            val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            val wMax = weekday.max()
            for (i in 0 until 7) {
                r.append(dayNames[i]).append(" ").append(weekday[i]).append(" ")
                    .append(bar(weekday[i], wMax, 16)).append("\n")
            }
            val weekend = weekday[5] + weekday[6]
            val workday = weekday.sum() - weekend
            r.append("工作日 / 周末：").append(workday).append(" / ").append(weekend).append(" 条\n")
        }

        if (showRank) {
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

        // 第 13 轮扩展：节奏类指标（不依赖任何文本内容，只看时间轴与长度）
        r.append("\n【互动节奏】\n")
        if (gapCount > 0) {
            r.append("平均间隔：").append(humanDuration(gapSum / gapCount)).append("\n")
            r.append("最长冷场：").append(humanDuration(maxGapMs)).append("\n")
        }
        r.append("最长连发：").append(maxStreak).append(" 条\n")
        if (longestLen > 0) {
            r.append("最长一条：").append(longestLen).append(" 字")
            if (longestFromKey.isNotBlank()) {
                r.append("（").append(speakerDisplayName(longestFromKey, talker, isGroup, nickCache)).append("）")
            }
            r.append("\n")
        }

        // 第 13 轮扩展：昼夜结构（把「全天活跃频次」压成一个可比较的结论）
        var deepNight = 0
        var daytime = 0
        var evening = 0
        for (h in 0 until 24) {
            when (h) {
                in 0..5 -> deepNight += hourDist[h]
                in 6..17 -> daytime += hourDist[h]
                else -> evening += hourDist[h]
            }
        }
        r.append("\n【昼夜结构】\n")
        r.append("深夜 0-5 点：").append(pct(deepNight, totalAll)).append("%\n")
        r.append("白天 6-17 点：").append(pct(daytime, totalAll)).append("%\n")
        r.append("夜晚 18-23 点：").append(pct(evening, totalAll)).append("%\n")
        when {
            pct(deepNight, totalAll) >= 25 -> r.append("鉴定：夜猫子局，深夜最容易聊出真话\n")
            pct(daytime, totalAll) >= 60 -> r.append("鉴定：白天型作息，聊的都是正事\n")
            else -> r.append("鉴定：分布在正常人类时段\n")
        }

        return r.toString()
    }

    // ---------------- 工具函数 ----------------

    fun typeName(t: Int): String = when (t) {
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

    /** Top-K 选择（与脚本一致：逐轮找最大） */
    fun topKeys(m: Map<String, Int>, k: Int): List<String> {
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

    fun bar(v: Int, max: Int, width: Int): String {
        if (max <= 0 || v <= 0) return ""
        val n = (v.toDouble() / max.toDouble() * width).roundToInt().coerceAtLeast(1)
        return "█".repeat(n.coerceAtMost(width))
    }

    fun pct(part: Int, total: Int): Int = if (total <= 0) 0 else (part.toDouble() / total.toDouble() * 100.0).roundToInt()

    /** 毫秒 → 人话时长（用于「平均间隔 / 最长冷场」这类节奏指标）。 */
    fun humanDuration(millis: Long): String = when {
        millis <= 0L -> "0 秒"
        millis < 60_000L -> "${millis / 1000} 秒"
        millis < 3_600_000L -> "${millis / 60_000} 分 ${millis % 60_000 / 1000} 秒"
        millis < 86_400_000L -> "${millis / 3_600_000} 小时 ${millis % 3_600_000 / 60_000} 分"
        else -> "${millis / 86_400_000} 天 ${millis % 86_400_000 / 3_600_000} 小时"
    }

    /** 词频：中文按 2-4 字窗口切分，跳过纯数字（脚本语义） */
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
        if (p >= 1 && p <= 41) {
            val s = content.substring(0, p).trim()
            if (validSender(s)) return s
        }
        p = content.indexOf(":")
        if (p >= 1 && p <= 41) {
            val s = content.substring(0, p).trim()
            if (validSender(s)) return s
        }
        return ""
    }

    private fun validSender(s: String): Boolean {
        if (s.length < 3 || s.length > 40) return false
        return s.matches(Regex("^[a-zA-Z0-9_\\-@]+$"))
    }

    private fun stripGroupSenderPrefix(body: String, wx: String): String {
        var cut = body.indexOf(":\n")
        if (cut >= 1 && cut <= 41 && body.substring(0, cut).trim() == wx) return body.substring(cut + 2)
        cut = body.indexOf(":")
        if (cut >= 1 && cut <= 41 && body.substring(0, cut).trim() == wx) return body.substring(cut + 1)
        return body
    }

    /** 说话人显示名：我 / 对方 / 群成员昵称（群昵称 → 微信名/备注 → wxid） */
    private fun speakerDisplayName(key: String, talker: String, isGroup: Boolean, nickCache: MutableMap<String, String>): String {
        if (key == "我") return "我"
        if (key == "对方") return talkerDisplayName(talker)
        if (key == "群友") return "群友"
        if (!isGroup) return key
        return nickCache.getOrPut(key) {
            // 1. 群备注/群昵称（roomdata protobuf）
            val groupNick = runCatching { WeDatabaseApi.getGroupMemberDisplayNameMap(talker)[key] }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (groupNick != null) return@getOrPut groupNick
            // 2. 微信名/备注（rcontact 表）
            val display = runCatching { WeDatabaseApi.getDisplayName(key) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != key }
            display ?: key
        }
    }

    fun talkerDisplayName(talker: String): String =
        runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrNull()?.takeIf { it.isNotBlank() } ?: talker
}
