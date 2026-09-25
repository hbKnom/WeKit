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

    /**
     * 话题 / 沉默的判定阈值：30 分钟。
     *
     * 一个阈值同时干两件事（口径自洽）：
     *  - 间隔 ≥ 30 分钟 → 记一次「沉默」（用于【沉默与主动性】）；
     *  - 同时切开一个「话题段」（用于【话题切换】），于是「话题段数 = 沉默次数 + 1」。
     *
     * 只做整数比较，不产生任何分配，放在主扫描里是 O(1)。
     */
    private const val TOPIC_BREAK_MS = 30L * 60L * 1000L

    /** 消息长度画像里保留的最长摘录条数（定长插入，零额外内存） */
    private const val EXCERPT_N = 3

    /** 摘录正文的最大展示字数（超长只留开头，避免报告里塞进一篇长文） */
    private const val EXCERPT_MAX = 46

    /** 口头禅只扫这个长度以内的正文：长文（转发长帖）会把语气词分布整个拉偏 */
    private const val CLICHE_BODY_MAX = 300

    /**
     * 第 15 轮：秒回阈值（10 秒）。
     *
     * 回复延迟分布的第一档，也是「秒回率」的分子，并用来归因「谁最爱秒回」。
     * 只有整数比较，零分配。
     */
    private const val FAST_REPLY_MS = 10_000L

    /**
     * 第 15 轮：连击被判为「被打断」的最小长度。
     *
     * 2 条以内的换人属于正常你来我往；只有同一个人的连击 ≥3 条时被别人接上，
     * 才算真正的「打断」。不卡这个下限的话「打断次数」就等于「发言轮次」，指标没有信息量。
     */
    private const val INTERRUPT_MIN_STREAK = 3

    /**
     * 第 15 轮：话题词只扫这个长度以内的正文。
     *
     * 与口头禅同一口径：话题词表是固定词表逐词 `contains`，长文（转发长帖）会让单条消息的
     * 扫描代价随正文长度线性上涨，而长文里的话题词分布也不代表聊天习惯。
     */
    private const val TOPIC_BODY_MAX = 300

    /**
     * 第 15 轮：话题词表（固定 28 个，**不随消息内容增长**）。
     *
     * 为什么不再跑一遍分词：分词结果会随语料无限膨胀（大群几十万条能产出十几万 token），
     * 而这里要的是「这份聊天在聊什么」的粗粒度雷达 —— 固定词表的统计口径稳定、跨会话可比、
     * 内存恒定。命中判定与【口头禅】一致，是**消息级**（一条消息对同一个词只记一次）。
     */
    private val TOPIC_WORDS = listOf(
        "工作", "加班", "会议", "项目", "代码", "需求", "学习", "考试", "游戏", "吃饭",
        "外卖", "奶茶", "咖啡", "减肥", "运动", "电影", "音乐", "旅行", "天气", "睡觉",
        "熬夜", "摸鱼", "工资", "股票", "房子", "宠物", "生日", "红包",
    )

    /** 星期名（ISO：周一=0 … 周日=6）：热力图左侧标签与峰值时段文案共用同一份 */
    private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 活跃热力的格数：7 天 × 24 小时（定长，与消息条数无关） */
    private const val HEAT_CELLS = 7 * 24

    /**
     * 口头禅词表（**消息级**判定：一条消息命中一次，和【高频词】的 n-gram 词频是两套口径）。
     *
     * 为什么用固定词表而不是再跑一遍分词：单字语气词（嗯/啊/哦）根本进不了 n-gram
     * （[countWords] 最少切 2 字），而这恰恰是口头禅最典型的形态；固定词表还能保证
     * 词表大小恒定，不会为大群多占一个字节的内存。
     */
    private val CLICHES = listOf(
        "哈哈", "嘿嘿", "嘻嘻", "呵呵", "笑死", "救命", "离谱", "绝了", "无语", "好家伙",
        "真的", "就是", "然后", "其实", "感觉", "可能", "不是", "好吧", "emmm", "emm",
        "嗯", "啊", "哦", "唉", "哎", "呀",
    )

    /**
     * 颜文字提示串（同样是消息级判定）。
     * 只做 contains：真正的面孔表达式五花八门，正则匹配既慢又容易漏，
     * 这里取的是"常见的几种打字习惯"，报告里也只声称口径为"常见颜文字"。
     */
    private val KAOMOJI = listOf("^_^", "T_T", "t_t", "Orz", "orz", "OTL", "-_-", ">_<", "QAQ", "￣▽￣")

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

        // 第 14 轮扩展的六个维度：全部在下面那次分页扫描里就地累计（不再回扫、不再多查一次库）
        val ex = ExtraStats()
        var waitingInitiator = false
        // 第 15 轮：本条消息与上一条的间隔（0 = 首条 或 超过 30 分钟），用于「谁最爱秒回」归因
        var lastReplyGap = 0L

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
                // ---- 第 15 轮：引用回复（type 49 且带 <refermsg> 节点）----
                // 只对卡片类消息做一次 contains：不解析 XML、不为它多查一次库、也不留中间结果。
                if (type == 49 && m["content"]?.toString()?.contains("<refermsg>") == true) ex.quoteMsgs++

                hc.timeInMillis = ct
                val hour = hc.get(Calendar.HOUR_OF_DAY)
                hourDist[hour]++
                // Calendar.DAY_OF_WEEK 周日=1，这里折成 ISO 的「周一=0 … 周日=6」
                val dow = (hc.get(Calendar.DAY_OF_WEEK) + 5) % 7
                weekday[dow]++
                // ---- 第 15 轮：活跃热力（周几 × 小时）在同一次扫描里就地累计，零分配 ----
                val heatIdx = dow * 24 + hour
                val heatV = ex.heat[heatIdx] + 1
                ex.heat[heatIdx] = heatV
                if (heatV > ex.heatPeak) {
                    ex.heatPeak = heatV
                    ex.heatPeakIdx = heatIdx
                }
                lastReplyGap = 0L
                if (prevCt > 0L) {
                    val gap = ct - prevCt
                    if (gap > 0L) {
                        gapSum += gap
                        gapCount++
                        if (gap > maxGapMs) maxGapMs = gap
                        // ---- 第 14 轮：回复间隔 / 沉默 / 话题分段（全是整数比较，无分配）----
                        if (gap <= TOPIC_BREAK_MS) {
                            // 真正意义上的「回复」：30 分钟内的你来我往
                            ex.replyGapSum += gap
                            ex.replyGapCount++
                            // ---- 第 15 轮：回复延迟分档 + 最快回复（同一处累计，不多遍历一次）----
                            lastReplyGap = gap
                            when {
                                gap <= FAST_REPLY_MS -> ex.latency[0]++
                                gap <= 60_000L -> ex.latency[1]++
                                gap <= 300_000L -> ex.latency[2]++
                                else -> ex.latency[3]++
                            }
                            if (ex.fastestGapMs == 0L || gap < ex.fastestGapMs) ex.fastestGapMs = gap
                        } else {
                            // 沉默 ≥30 分钟：记一次沉默、切开一个话题段，并把下一条消息的作者
                            // 记为这一段的「发起人」（pending 标记在下面 rank 统计处消费）
                            ex.silentBreaks++
                            ex.silentSum += gap
                            if (gap > ex.maxGapMs) {
                                ex.maxGapMs = gap
                                ex.maxGapStart = prevCt
                                ex.maxGapEnd = ct
                            }
                            closeTopic(ex, prevCt)
                            ex.topicStart = ct
                            waitingInitiator = true
                        }
                    }
                } else {
                    // 时段内第一条消息 = 第一个话题段的起点（它本身不算「发起」：
                    // 时间范围是我们截出来的，它前面的沉默长度未知，计入会失真）
                    ex.topicStart = ct
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
                    // ---- 第 15 轮：秒回归因（≤10 秒就接上话的那个人是谁）----
                    if (lastReplyGap in 1..FAST_REPLY_MS) {
                        ex.fastReply[rankKey] = (ex.fastReply[rankKey] ?: 0) + 1
                    }
                    // 沉默 ≥30 分钟后的第一条 = 这一段话题的「发起人」（系统消息不参与）
                    if (waitingInitiator) {
                        ex.initiator[rankKey] = (ex.initiator[rankKey] ?: 0) + 1
                        waitingInitiator = false
                    }
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
                    if (body.startsWith("@")) {
                        // ---- 第 15 轮：@ 拆成两种口径（@ 了谁 / 其中 @ 的是不是我）----
                        // 判定条件与原来逐字等价，只是把「有没有 @」这一层单独记下来。
                        ex.atAny++
                        if ((myWxid.isNotEmpty() && body.contains(myWxid)) ||
                            (myNick.isNotEmpty() && body.contains(myNick)) ||
                            body.contains("所有人")
                        ) {
                            atMe++
                            ex.atMeEx++
                        }
                    }
                    if (senderKey == streakKey) {
                        streak++
                    } else {
                        // ---- 第 15 轮：换人 = 一个「回合」结束；长连击被换人才算「打断」----
                        if (streak >= INTERRUPT_MIN_STREAK) ex.interrupts++
                        ex.turns++
                        streakKey = senderKey
                        streak = 1
                    }
                    if (streak > maxStreak) {
                        maxStreak = streak
                        ex.streakMax = streak
                        ex.streakMaxKey = senderKey
                    }
                    if (body.length > longestLen) {
                        longestLen = body.length
                        longestFromKey = senderKey
                    }
                    // ---- 第 14 轮：长度 / 标点 / 口头禅 / 摘录（同一次扫描内增量）----
                    ex.lenSum += body.length
                    ex.rankChars[senderKey] = (ex.rankChars[senderKey] ?: 0) + body.length
                    scanPunctuation(body, ex)
                    if (body.length <= CLICHE_BODY_MAX) scanCliches(body, ex)
                    if (body.length <= TOPIC_BODY_MAX) scanTopics(body, ex)
                    rememberExcerpt(ex, senderKey, body)
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
        // 收尾最后一个话题段（段时长 = 段内最后一条 - 段内第一条）
        closeTopic(ex, prevCt)

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
                extra = ex,
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

    // ---------------- 第 14 轮扩展维度的累计器（同一次扫描内增量） ----------------

    /**
     * 六个新维度所需的原始量。
     *
     * 为什么收成一个类而不是再散十几个局部变量：`analyze` 的主循环已经有十几个计数器，
     * 这里再加十几行 `var` 会让人分不清「哪些是旧口径、哪些是新口径」；收进一个持有器后，
     * 新维度的所有状态集中在 **同一处**，也便于逐条核对「有没有多做一次遍历」。
     *
     * 内存：全部是定长标量 + 三个「以参与者为键」的小 map（键最多是参会人数，
     * 私聊只有 我/对方 两个键），**不随消息条数增长**；[topBodies] 恒定 ≤ [EXCERPT_N] 条，
     * 且只存已有字符串的引用（[analyze] 里的 textBodies 本来就持有它们）。
     */
    private class ExtraStats {
        /** 纯文本字数总和（平均字数的分子） */
        var lenSum = 0L

        /** 纯文本字符总数（各类「/百字」密度的分母） */
        var charTotal = 0

        // ---- 标点与语气（按字符计数）----
        var qMark = 0
        var eMark = 0
        var ellipsis = 0
        var tilde = 0
        var letterChars = 0

        /** 命中表情符号 / 常见颜文字的消息条数 */
        var emojiMsgs = 0
        var kaoMsgs = 0

        // ---- 口头禅 ----
        var clicheMsgs = 0
        val cliche = mutableMapOf<String, Int>()

        // ---- 沉默与主动性 ----
        /** ≤[TOPIC_BREAK_MS] 的回复间隔之和 / 条数（真正的「回复」间隔，不含长中断） */
        var replyGapSum = 0L
        var replyGapCount = 0

        /** ≥[TOPIC_BREAK_MS] 的沉默次数与累计时长 */
        var silentBreaks = 0
        var silentSum = 0L

        /** 最长沉默的起止时间点（上一条 / 下一条消息的时间） */
        var maxGapMs = 0L
        var maxGapStart = 0L
        var maxGapEnd = 0L

        /** 沉默后第一条消息的发送者计数（谁更常先开口） */
        val initiator = mutableMapOf<String, Int>()

        /** 每个参与者的纯文本字数（互动平衡的「字数比」） */
        val rankChars = mutableMapOf<String, Int>()

        // ---- 话题切换 ----
        var topicStart = 0L
        var maxTopicMs = 0L
        var maxTopicStart = 0L
        var maxTopicEnd = 0L

        /** 最长 [EXCERPT_N] 条摘录（senderKey to body），定长插入 */
        val topBodies = mutableListOf<Pair<String, String>>()

        // ---- 第 15 轮：六个新维度的累计量（同样是定长容器 / 人数规模的小 map）----

        /** 7(ISO 周几) × 24(小时) 的活跃热力矩阵：定长 [HEAT_CELLS] 个 int，不随消息数增长 */
        val heat = IntArray(HEAT_CELLS)

        /** 热力峰值格的计数与下标（下标 = 周几 × 24 + 小时） */
        var heatPeak = 0
        var heatPeakIdx = -1

        /** 回复延迟分档（≤10 秒 / ≤60 秒 / ≤5 分 / ≤30 分），定长 4 档 */
        val latency = IntArray(4)

        /** 最快一次回复的间隔（毫秒），0 = 还没有可用样本 */
        var fastestGapMs = 0L

        /** 秒回（≤10 秒接上话）按发送者的归因计数 */
        val fastReply = mutableMapOf<String, Int>()

        /** 最长连击的条数与归属人 */
        var streakMax = 0
        var streakMaxKey = ""

        /** 「回合」数（换人发言的次数）与「打断」次数（长连击被别人接上） */
        var turns = 0
        var interrupts = 0

        /** @ 的全部次数 / 其中 @ 到我 的次数 */
        var atAny = 0
        var atMeEx = 0

        /** 引用回复（type 49 且含 refermsg 节点）条数 */
        var quoteMsgs = 0

        /** 话题词命中（消息级）与命中任意话题词的消息条数 */
        val topic = mutableMapOf<String, Int>()
        var topicMsgs = 0
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
        extra: ExtraStats,
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

        // 第 14 轮扩展的六个新维度。全部**追加在既有段落之后**：
        // 老段的顺序、每一行文本都不动，因此老报告的解析结果逐字不变（只多出新卡片）。
        appendExtraSections(
            r = r,
            ex = extra,
            talker = talker,
            isGroup = isGroup,
            textN = textN,
            totalAll = totalAll,
            rank = rank,
            lenShort = lenShort,
            lenMid = lenMid,
            lenLong = lenLong,
            lenHuge = lenHuge,
            nickCache = nickCache,
            typeCount = typeCount,
        )

        return r.toString()
    }

    // ---------------- 第 14 轮新增：六个维度的报告段 ----------------

    /**
     * 追加六个新维度（消息长度画像 / 标点与语气 / 口头禅 / 互动平衡 / 沉默与主动性 / 话题切换）。
     *
     * 排版铁律（弹窗 UI 与 PNG 导出各有一个**通用**的「【段】」解析器，两边的判据必须同时满足）：
     *  - 「键：值」一行一个指标，键 ≤ 20 字、值 ≤ 18 字 → 进 KPI 网格（大数字卡片）；
     *  - 要画成环形图的分布：`标签 数值 ████`，数值全为正、标签里不含数字；
     *  - 词频行**整行只允许** `词×次数` 这种 token → 标签云；
     *  - 其余整句一律不带全角冒号，避免被误判成指标行（所以比值句用半角 `:`）。
     */
    private fun appendExtraSections(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        totalAll: Int,
        rank: Map<String, Int>,
        lenShort: Int,
        lenMid: Int,
        lenLong: Int,
        lenHuge: Int,
        nickCache: MutableMap<String, String>,
        typeCount: Map<String, Int>,
    ) {
        // ── 1) 消息长度画像 ──────────────────────────────────────────
        r.append("\n【消息长度画像】\n")
        r.append("平均字数：").append(if (textN > 0) (ex.lenSum.toDouble() / textN).roundToInt() else 0).append(" 字\n")
        r.append("短句占比：").append(pct(lenShort, textN)).append("%\n")
        r.append("中句占比：").append(pct(lenMid, textN)).append("%\n")
        r.append("长句占比：").append(pct(lenLong + lenHuge, textN)).append("%\n")
        if (ex.topBodies.isEmpty()) {
            r.append("最长摘录：无\n")
        } else {
            for ((i, tp) in ex.topBodies.withIndex()) {
                r.append("最长摘录 ").append(i + 1).append("：").append(tp.second.length).append(" 字")
                val who = textSafe(speakerDisplayName(tp.first, talker, isGroup, nickCache))
                if (who.isNotBlank()) r.append(" · ").append(who)
                r.append("\n")
                r.append(excerpt(tp.second)).append("\n")
            }
        }

        // ── 2) 标点与语气（按字符密度口径，与【情绪指纹】的消息级口径互补）──
        r.append("\n【标点与语气】\n")
        if (ex.charTotal > 0) {
            val qD = density(ex.qMark, ex.charTotal)
            val eD = density(ex.eMark, ex.charTotal)
            val lD = density(ex.ellipsis, ex.charTotal)
            val wD = density(ex.tilde, ex.charTotal)
            r.append("问号密度：").append(oneDecimal(qD)).append(" /百字\n")
            r.append("感叹密度：").append(oneDecimal(eD)).append(" /百字\n")
            r.append("省略号密度：").append(oneDecimal(lD)).append(" /百字\n")
            r.append("波浪号密度：").append(oneDecimal(wD)).append(" /百字\n")
            r.append("字母占比：").append(pct(ex.letterChars, ex.charTotal)).append("%\n")
            r.append("表情符号率：").append(pct(ex.emojiMsgs, textN)).append("%\n")
            r.append("颜文字率：").append(pct(ex.kaoMsgs, textN)).append("%\n")
            r.append("语气倾向：").append(toneTrend(qD, eD, lD, wD, ex, textN)).append("\n")
        } else {
            r.append("标点统计：无可用正文\n")
        }

        // ── 3) 口头禅（消息级命中，与【高频词】的 n-gram 词频是两套口径）──────
        r.append("\n【口头禅】\n")
        r.append("口头禅浓度：").append(pct(ex.clicheMsgs, textN)).append("%\n")
        r.append("统计口径：含该词的消息条数\n")
        val ck = topKeys(ex.cliche, 12)
        if (ck.isEmpty()) {
            r.append("最常挂嘴边：无\n")
        } else {
            val top = ck[0]
            r.append("最常挂嘴边：").append(top).append("（").append(ex.cliche[top] ?: 0).append(" 次）\n")
            for ((i, k) in ck.withIndex()) {
                r.append(k).append("×").append(ex.cliche[k] ?: 0)
                if (i < ck.size - 1) r.append("  ")
            }
            r.append("\n")
        }

        // ── 4) 互动平衡（只给占比与比值，不复述【发言排行】的条数）──────────
        r.append("\n【互动平衡】\n")
        val rankTotal = rank.values.sum()
        val mine = rank["我"] ?: 0
        val mineChars = ex.rankChars["我"] ?: 0
        val allChars = ex.rankChars.values.sum()
        val others = (rankTotal - mine).coerceAtLeast(0)
        val otherChars = (allChars - mineChars).coerceAtLeast(0)
        r.append("我的条数占比：").append(pct(mine, rankTotal)).append("%\n")
        r.append("我的字数占比：").append(pct(mineChars, allChars)).append("%\n")
        if (isGroup) {
            val othersMap = rank.filterKeys { it != "我" }
            val ok = topKeys(othersMap, 3)
            for ((i, k) in ok.withIndex()) {
                val dn = textSafe(speakerDisplayName(k, talker, isGroup, nickCache))
                r.append("TOP").append(i + 1).append(" ").append(dn).append(" 占比：")
                    .append(pct(rank[k] ?: 0, rankTotal)).append("%\n")
            }
            r.append("条数比 ").append(ratioText(mine, others)).append("（我 vs 其余人）\n")
            val top1Pct = if (ok.isEmpty()) 0 else pct(rank[ok[0]] ?: 0, rankTotal)
            r.append("平衡度：").append(groupBalanceText(top1Pct)).append("\n")
        } else {
            r.append("条数比 ").append(ratioText(mine, others)).append("（我 vs 对方）\n")
            r.append("字数比 ").append(ratioText(mineChars, otherChars)).append("（我 vs 对方）\n")
            r.append("平衡度：").append(balanceText(mine, others)).append("\n")
        }

        // ── 5) 沉默与主动性 ─────────────────────────────────────────
        r.append("\n【沉默与主动性】\n")
        r.append("最长沉默：").append(humanDuration(ex.maxGapMs)).append("\n")
        r.append("沉默次数：").append(ex.silentBreaks).append(" 次\n")
        if (ex.silentBreaks > 0) {
            r.append("平均每次沉默：").append(humanDuration(ex.silentSum / ex.silentBreaks)).append("\n")
        }
        if (ex.maxGapEnd > ex.maxGapStart && ex.maxGapStart > 0L) {
            r.append("最长沉默区间 ").append(clockText(ex.maxGapStart)).append(" → ")
                .append(clockText(ex.maxGapEnd)).append("\n")
        }
        if (ex.replyGapCount > 0) {
            r.append("平均回复间隔：").append(humanDuration(ex.replyGapSum / ex.replyGapCount)).append("\n")
        } else {
            r.append("平均回复间隔：无（30 分钟内无连续对话）\n")
        }
        if (ex.initiator.isEmpty()) {
            r.append("谁更常先开口：没有跨越 30 分钟的中断\n")
        } else {
            r.append("谁更常先开口\n")
            val ik = topKeys(ex.initiator, 4)
            val iMax = (ex.initiator[ik[0]] ?: 1).coerceAtLeast(1)
            var firstKey = ik[0]
            for (k in ik) {
                val v = ex.initiator[k] ?: 0
                if (v <= 0) continue
                if (v > (ex.initiator[firstKey] ?: 0)) firstKey = k
                val dn = textSafe(speakerDisplayName(k, talker, isGroup, nickCache))
                r.append(dn).append(" ").append(v).append(" ").append(bar(v, iMax, 16)).append("\n")
            }
            r.append("先开口最多：")
                .append(textSafe(speakerDisplayName(firstKey, talker, isGroup, nickCache)))
                .append("（").append(ex.initiator[firstKey] ?: 0).append(" 次）\n")
        }

        // ── 6) 话题切换 ─────────────────────────────────────────────
        r.append("\n【话题切换】\n")
        val topicCount = ex.silentBreaks + 1
        r.append("话题段数：").append(topicCount).append(" 段\n")
        r.append("平均每段：").append((totalAll.toDouble() / topicCount).roundToInt()).append(" 条\n")
        if (ex.maxTopicMs > 0L) {
            r.append("最长话题：").append(humanDuration(ex.maxTopicMs)).append("\n")
        }
        if (ex.maxTopicEnd > ex.maxTopicStart && ex.maxTopicStart > 0L) {
            r.append("最长话题段 ").append(clockText(ex.maxTopicStart)).append(" → ")
                .append(clockText(ex.maxTopicEnd)).append("\n")
        }
        if (ex.silentBreaks > 0) {
            r.append("切换间隔：").append(humanDuration(ex.silentSum / ex.silentBreaks)).append("/次\n")
        } else {
            r.append("切换节奏：全程连贯，没有跨越 30 分钟的中断\n")
        }

        // ── 第 15 轮：六个新维度（同样追加在老段之后，老报告的每一行文本都没动）──
        appendRound15Sections(
            r = r,
            ex = ex,
            talker = talker,
            isGroup = isGroup,
            textN = textN,
            totalAll = totalAll,
            typeCount = typeCount,
            nickCache = nickCache,
        )
    }


    // ---------------- 第 15 轮新增：六个扩展维度的报告段 ----------------

    /**
     * 追加第 15 轮的六个维度：活跃热力 / 回复延迟分布 / 媒体与表情构成 / 连击与打断 /
     * 话题关键词 / @与互动消息。
     *
     * 排版规则与第 14 轮**完全同一套**（两侧的通用解析器不认识新语法，所以这里不发明新写法，
     * 只把已经在内存里的数字排成它认得的形状）：
     *  - `键：值` 一行一个指标，键 ≤ 20 字、值 ≤ 18 字 → 进 KPI 网格（连续 ≥2 行才会并成网格）；
     *  - `标签 数值 ████` → 分布图（标签里含数字 → 柱状图；全正且无数字 → 环形图）；
     *  - 整行只由 `词×次数` 组成 → 标签云；
     *  - 活跃热力固定 7 行 `周X → 24 个数字`（弹窗与 PNG 各有一段专用解析，判据见两侧的 HeatLine）；
     *  - 结论句一律不带全角冒号，免得被误判成指标行。
     *
     * 所有数据都来自 [ExtraStats] 在同一次扫描里累出来的定长容器（热力 7×24 int、延迟 4 档 int、
     * 话题固定 28 词），这里只做字符串拼接：**不查库、不遍历消息、不物化全量**。
     */
    private fun appendRound15Sections(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        totalAll: Int,
        typeCount: Map<String, Int>,
        nickCache: MutableMap<String, String>,
    ) {
        // ── 7) 活跃热力（周几 × 小时）────────────────────────────────
        r.append("\n【活跃热力】\n")
        // KPI 的值必须"以数字开头"：排版器会把值拆成「数字 + 单位」两段来画（
        // splitValueUnit），值以中文开头时数字会被截出来、前缀会被丢掉，所以星期名一律写在
        // 结尾的读法行里，指标格里只放纯数值。
        if (ex.heatPeakIdx >= 0) {
            r.append("峰值条数：").append(ex.heatPeak).append(" 条\n")
            r.append("峰值小时：").append(ex.heatPeakIdx % 24).append(" 点\n")
        }
        var heatCells = 0
        for (v in ex.heat) if (v > 0) heatCells++
        r.append("活跃时段数：").append(heatCells).append(" 个\n")
        // 必须连续 7 行、每行 24 个数字：少一行就不成块，会被两侧解析器退回普通正文行（宁缺勿错）
        for (d in 0 until 7) {
            r.append(DAY_NAMES[d]).append(" →")
            for (h in 0 until 24) r.append(' ').append(ex.heat[d * 24 + h])
            r.append("\n")
        }
        r.append("热力读法 每行一天、每列一小时，颜色越深越活跃")
        if (ex.heatPeakIdx >= 0) r.append("，峰值落在").append(heatLabel(ex.heatPeakIdx))
        r.append("\n")

        // ── 8) 回复延迟分布（同一批 30 分钟内的间隔分四档）────────────
        r.append("\n【回复延迟分布】\n")
        if (ex.replyGapCount > 0) {
            r.append("平均回复速度：").append(humanDuration(ex.replyGapSum / ex.replyGapCount)).append("\n")
            r.append("秒回率：").append(pct(ex.latency[0], ex.replyGapCount)).append("%\n")
            r.append("最快回复：").append(humanDuration(ex.fastestGapMs)).append("\n")
            val lMax = maxOf(ex.latency[0], ex.latency[1], ex.latency[2], ex.latency[3])
            // 标签不带空格：0 条的那一档整行没有 █，解析器会退回 PLAIN_COUNT_LINE
            // （^(\\S+)\\s+(\\d+)$），标签里只要有空格就认不出来 —— 会变成一行原始正文。
            val names = listOf("≤10秒", "10~60秒", "1~5分", "5~30分")
            for (i in 0 until 4) {
                r.append(names[i]).append(" ").append(ex.latency[i]).append(" ")
                    .append(bar(ex.latency[i], lMax, 16)).append("\n")
            }
            val fast = topKeys(ex.fastReply, 1)
            if (fast.isNotEmpty()) {
                r.append("谁最爱秒回：")
                    .append(textSafe(speakerDisplayName(fast[0], talker, isGroup, nickCache)))
                    .append("（").append(ex.fastReply[fast[0]] ?: 0).append(" 次）\n")
            }
        } else {
            // 不带全角冒号：带冒号又是短行的话会被解析器读成一条指标，这里要的是一句人话
            r.append("样本不足 该时段没有 30 分钟以内的连续对话\n")
        }

        // ── 9) 媒体与表情构成（纯复用既有 typeCount，扫描期零成本）──
        r.append("\n【媒体与表情构成】\n")
        if (totalAll > 0) {
            val textCount = typeCount["文字"] ?: 0
            val emojiCount = typeCount["表情"] ?: 0
            val picCount = typeCount["图片"] ?: 0
            val mediaCount = picCount + (typeCount["语音"] ?: 0) + (typeCount["视频"] ?: 0) + emojiCount
            r.append("文字消息占比：").append(pct(textCount, totalAll)).append("%\n")
            r.append("媒体消息占比：").append(pct(mediaCount, totalAll)).append("%\n")
            r.append("表情占比：").append(pct(emojiCount, totalAll)).append("%\n")
            r.append("图片占比：").append(pct(picCount, totalAll)).append("%\n")
            r.append("媒体与文字比 ").append(ratioText(mediaCount, textCount)).append("\n")
            r.append("鉴定：").append(
                when {
                    pct(emojiCount, totalAll) >= 20 -> "表情包是第二语言"
                    pct(mediaCount, totalAll) >= 50 -> "能发图绝不打字"
                    pct(textCount, totalAll) >= 80 -> "纯文字选手"
                    else -> "文字为主，媒体点缀"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有可统计的消息\n")
        }

        // ── 10) 连击与打断（"回合" = 换人发言；长连击被换人才算打断）──
        r.append("\n【连击与打断】\n")
        if (textN > 0) {
            val turns = if (ex.turns > 0) ex.turns else 1
            r.append("最长连击：").append(ex.streakMax).append(" 条\n")
            r.append("平均连击：").append(oneDecimal(textN.toDouble() / turns.toDouble())).append(" 条\n")
            r.append("打断次数：").append(ex.interrupts).append(" 次\n")
            if (ex.streakMaxKey.isNotEmpty()) {
                r.append("连击王：")
                    .append(textSafe(speakerDisplayName(ex.streakMaxKey, talker, isGroup, nickCache)))
                    .append("\n")
            }
            r.append("连击点评 ").append(
                when {
                    ex.streakMax >= 10 -> "有人能连发十条不喘气"
                    ex.turns * 2 >= textN -> "轮流发言，几乎没有连发"
                    ex.interrupts * 4 >= turns -> "话题老被打断，跳得很快"
                    else -> "有来有回，节奏正常"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 11) 话题关键词（固定 28 词表，消息级口径，等价于一张小雷达）──
        r.append("\n【话题关键词】\n")
        if (textN > 0) {
            r.append("话题浓度：").append(pct(ex.topicMsgs, textN)).append("%\n")
            val top = topKeys(ex.topic, 12)
            if (top.isNotEmpty()) {
                // 话题词写进「键」、次数写进「值」：值以数字开头，指标格才能把数字画成大号
                r.append("最热话题 ").append(top[0]).append("：")
                    .append(ex.topic[top[0]] ?: 0).append(" 次\n")
                val chips = StringBuilder()
                for ((i, w) in top.withIndex()) {
                    if (i > 0) chips.append("  ")
                    chips.append(w).append("×").append(ex.topic[w] ?: 0)
                }
                r.append(chips).append("\n")
            } else {
                // 一个话题词都没命中时也保持"值以数字开头"，指标格的行数才不会突然变少
                r.append("话题命中：0 个\n")
            }
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 12) @ 与互动消息 ────────────────────────────────────────
        r.append("\n【@与互动消息】\n")
        if (textN > 0) {
            r.append("@提及次数：").append(ex.atAny).append(" 次\n")
            r.append("其中@我：").append(ex.atMeEx).append(" 次\n")
            r.append("引用回复：").append(ex.quoteMsgs).append(" 条\n")
            r.append("互动消息占比：").append(pct(ex.atAny + ex.quoteMsgs, textN)).append("%\n")
            r.append("互动点评 ").append(
                when {
                    ex.atAny + ex.quoteMsgs == 0 -> "既不 @ 人也不引用，全靠正文接话"
                    pct(ex.atAny + ex.quoteMsgs, textN) >= 10 -> "@ 与引用用得很勤，点名型选手"
                    else -> "@ 与引用不多，自然接话型"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }
    }

    /** 热力格下标（周几 × 24 + 小时）→ 「周三 21 点」 */
    private fun heatLabel(idx: Int): String {
        if (idx < 0 || idx >= HEAT_CELLS) return ""
        return DAY_NAMES[idx / 24] + " " + (idx % 24) + " 点"
    }

    // ---------------- 第 14 轮新增：扫描期的增量统计 ----------------

    /**
     * 收尾一个话题段：段时长 = 段内最后一条消息 - 段内第一条消息。
     *
     * 只在「沉默 ≥30 分钟」和扫描结束时各调一次，纯整数比较，无分配。
     */
    private fun closeTopic(ex: ExtraStats, endCt: Long) {
        if (ex.topicStart <= 0L || endCt <= ex.topicStart) return
        val dur = endCt - ex.topicStart
        if (dur > ex.maxTopicMs) {
            ex.maxTopicMs = dur
            ex.maxTopicStart = ex.topicStart
            ex.maxTopicEnd = endCt
        }
    }

    /**
     * 标点 / 字母 / 表情的**一次**字符扫描。
     *
     * 为什么要单独走一遍字符：这些量要的是「每百字几个」的密度口径，
     * 上面那批 `contains` 只能回答「有没有」，给不出密度；而字符循环是纯算术、
     * 零对象分配，同一条正文多扫一遍的代价远小于再查一次数据库。
     */
    private fun scanPunctuation(body: String, ex: ExtraStats) {
        if (body.isEmpty()) return
        ex.charTotal += body.length
        var emoji = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '?' || c == '？' -> ex.qMark++
                c == '!' || c == '！' -> ex.eMark++
                c == '…' -> ex.ellipsis++
                c == '~' || c == '～' -> ex.tilde++
                c in 'a'..'z' || c in 'A'..'Z' -> ex.letterChars++
            }
            // 表情符号在 UTF-16 里是代理对，必须按码点判断（只看一个 char 永远判不出来）
            if (Character.isHighSurrogate(c) && i + 1 < body.length && Character.isLowSurrogate(body[i + 1])) {
                val cp = Character.toCodePoint(c, body[i + 1])
                if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF) emoji = true
                i++
            }
            i++
        }
        if (emoji) ex.emojiMsgs++
        if (KAOMOJI.any { body.contains(it) }) ex.kaoMsgs++
    }

    /** 口头禅：固定词表逐个 `contains`，一条消息对同一个词只记一次（消息级口径） */
    private fun scanCliches(body: String, ex: ExtraStats) {
        var hit = false
        for (w in CLICHES) {
            if (body.contains(w)) {
                ex.cliche[w] = (ex.cliche[w] ?: 0) + 1
                hit = true
            }
        }
        if (hit) ex.clicheMsgs++
    }

    /** 话题词：固定词表逐个 `contains`（消息级口径，与【口头禅】同一套判定） */
    private fun scanTopics(body: String, ex: ExtraStats) {
        var hit = false
        for (w in TOPIC_WORDS) {
            if (body.contains(w)) {
                ex.topic[w] = (ex.topic[w] ?: 0) + 1
                hit = true
            }
        }
        if (hit) ex.topicMsgs++
    }

    /**
     * 最长摘录：[EXCERPT_N] 条定长插入。
     *
     * 不排序、不收集全部消息（内存恒定），只保留已有字符串的引用；
     * 长度为 0 的正文直接跳过，避免"空摘录"占位。
     */
    private fun rememberExcerpt(ex: ExtraStats, senderKey: String, body: String) {
        if (body.isEmpty()) return
        var at = -1
        for (i in ex.topBodies.indices) {
            if (body.length > ex.topBodies[i].second.length) {
                at = i
                break
            }
        }
        if (at < 0 && ex.topBodies.size < EXCERPT_N) at = ex.topBodies.size
        if (at < 0) return
        ex.topBodies.add(at, senderKey to body)
        while (ex.topBodies.size > EXCERPT_N) ex.topBodies.removeAt(ex.topBodies.size - 1)
    }

    /**
     * 摘录正文的安全化：去掉换行、全/半角冒号与条形块。
     *
     * 为什么必须做：摘录是**任意用户文本**，里面一旦出现 `：`，弹窗和 PNG 的通用解析器
     * 就会把这一行当成「指标：值」，排版会错位；换成逗号后它永远是普通正文行。
     */
    private fun excerpt(body: String): String {
        val cleaned = textSafe(body).trim()
        val cut = if (cleaned.length > EXCERPT_MAX) cleaned.substring(0, EXCERPT_MAX) + "…" else cleaned
        return "「" + cut + "」"
    }

    /** 展示名 / 正文里会被段解析器误判的字符一律替换掉（不删除信息，只换字形） */
    private fun textSafe(s: String): String = s
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('：', '，')
        .replace(':', ',')
        .replace('█', ' ')

    /** 每百字出现次数（密度口径）；分母为 0 时返回 0，绝不产生 NaN */
    private fun density(count: Int, total: Int): Double =
        if (total <= 0) 0.0 else count.toDouble() * 100.0 / total.toDouble()

    /** 保留一位小数。用整数运算而不是 String.format，省掉一处 Locale import */
    private fun oneDecimal(v: Double): String {
        if (v <= 0.0) return "0.0"
        val t = (v * 10.0).roundToInt()
        return (t / 10).toString() + "." + (t % 10)
    }

    /** 比值文本：以较小的一方为 1；任一方为 0 时直接给整数比 */
    private fun ratioText(a: Int, b: Int): String {
        if (a <= 0 && b <= 0) return "0 : 0"
        if (a <= 0 || b <= 0) return if (a > b) "$a : 0" else "0 : $b"
        return if (a >= b) oneDecimal(a.toDouble() / b.toDouble()) + " : 1"
        else "1 : " + oneDecimal(b.toDouble() / a.toDouble())
    }

    /** 一句「语气倾向」结论（只判档位不含数字，避免被 KPI 卡片当数值渲染） */
    private fun toneTrend(qD: Double, eD: Double, lD: Double, wD: Double, ex: ExtraStats, textN: Int): String = when {
        qD >= 1.0 && qD >= eD -> "探询型（总想再确认一句）"
        eD >= 0.8 -> "外放型（感叹号比句号还多）"
        lD >= 0.3 -> "留白型（省略号里都是没说出口的）"
        wD >= 0.3 -> "拖音型（波浪号把语气拉长）"
        textN > 0 && ex.emojiMsgs * 2 >= textN -> "活泼型（表情符号撑起半句话）"
        else -> "平铺直叙型（标点很克制）"
    }

    /** 私聊平衡度结论 */
    private fun balanceText(mine: Int, others: Int): String {
        if (others <= 0) return "一边倒（对方一句没说）"
        if (mine <= 0) return "全程潜水（我一句没说）"
        val hi = maxOf(mine, others)
        val lo = minOf(mine, others)
        val ratio = hi.toDouble() / lo.toDouble()
        return when {
            ratio < 1.2 -> "势均力敌，你来我往"
            ratio < 1.8 && mine > others -> "我稍主动，对方接得住"
            ratio < 1.8 -> "对方稍主动，我接得住"
            mine > others -> "我在输出，对方以听为主"
            else -> "对方在输出，我以听为主"
        }
    }

    /** 群聊平衡度结论：看头名的条数占比 */
    private fun groupBalanceText(top1Pct: Int): String = when {
        top1Pct >= 50 -> "一个人带全场，其余人负责围观"
        top1Pct >= 30 -> "少数人撑起大部分发言"
        top1Pct > 0 -> "发言比较分散，没有绝对主角"
        else -> "暂无可比数据"
    }

    /** 毫秒 → "MM-dd HH:mm"（本地时区）：沉默区间 / 话题段起止点用 */
    private fun clockText(ms: Long): String {
        if (ms <= 0L) return ""
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val mo = ((c.get(Calendar.MONTH) + 1).toString()).padStart(2, '0')
        val day = (c.get(Calendar.DAY_OF_MONTH).toString()).padStart(2, '0')
        val hh = (c.get(Calendar.HOUR_OF_DAY).toString()).padStart(2, '0')
        val mi = (c.get(Calendar.MINUTE).toString()).padStart(2, '0')
        return "$mo-$day $hh:$mi"
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
