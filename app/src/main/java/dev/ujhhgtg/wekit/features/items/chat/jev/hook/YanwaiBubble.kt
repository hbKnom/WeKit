package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.ChatInsights
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.JevProtocol
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.JevText
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodBar
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.dominantName
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Append a sibling below the real text bubble, without replacing a host row or ViewHolder.
 *
 * 铁律（踩过崩溃，别再犯）：卡片只允许作为**宿主行内部容器的子 View**存在，
 * 绝不往宿主 RecyclerView（或其直接子 View 之外的位置）addView ——
 * 那会让 RecyclerView 看到没有 ViewHolder 的外来子 View，直接 NPE 崩溃。
 * 因此这里一路只找「行内部那个纵向 LinearLayout / 行根 RelativeLayout」作为落点，
 * 并且只做**追加**（不改变宿主原有子 View 的下标）。
 *
 * 卡片形态（第 15 轮重排信息层级 + 可展开）：
 * ```
 * ┃ 潜语 · 平静   · 较前几句 ↑12          ← 主情绪 + 与前几句对比
 * ┃ 平静 ▓▓▓▓▓▓░░░░ 59%                  ← 主情绪一条（展开时给全概率）
 * ┃ 邀约安排 · 等具体安排 · 置信度 62%      ← 场景 / 阶段 / 置信度
 * ┃ 这句可能在给见面留位置                  ← 候选解读标题
 * ┃ 对方在试探能否一起去？                  ← 展开后：解读要回答的问题
 * ┃ 信号 45% · 普通 30%                    ← 展开后：备选解读概率
 * ┃ 建议：顺着刚提到的事，问一个还没说的细节  ← 下一步动作
 * ┃ 点击展开 · 长按复制                     ← 交互提示 / 降级说明
 * ```
 * 默认收起（一行主情绪 + 一条建议），点一下展开全部细节，长按复制成文本 ——
 * 聊天里最重要的信息一眼可见，其余按需展开，不占屏、不刷屏。
 *
 * 左侧竖条颜色 = 情绪倾向（正向绿 / 中性橙 / 负向红），失败态换成告警色。
 *
 * 性能（用户实测「卡片一多就卡」的几处）：
 *  - 渲染指纹改成**结构化比较**（不再拼字符串），调色板与文本拼接只在真的变化时才算；
 *  - 调色板（含莫奈取色）按「夜间模式 + 引擎色板实例」缓存，不再每次 show 都重建；
 *  - 卡片落点反射结果按 holder 类缓存，不再每次绑定时重扫方法表。
 */
object YanwaiBubble {
    private data class Card(
        val key: String,
        val container: LinearLayout,
        val parent: ViewGroup,
        val anchor: View,
        val assignedId: Int?,
        val detach: View.OnAttachStateChangeListener,
        val stripe: View,
        val header: TextView,
        val bars: BarsView,
        val meta: TextView,
        val reading: TextView,
        val advice: TextView,
        /** 卡片扩展块：建议分级 / 话题 / 互动均衡 / 情绪趋势 / 风险依据。 */
        val insight: TextView,
        val footer: TextView,
        /** 是否展开了完整解读（默认取设置里的「卡片默认展开详情」）。 */
        var expanded: Boolean = ModulePrefs.cardExpanded,
        /** 上一次渲染的指纹；相等就整个跳过布局与文本重算。 */
        var fingerprint: Fingerprint? = null,
        /** 最近一次渲染用的输入与跳过原因：展开/收起时要按它原样重绘（不再走一遍扫描器）。 */
        var input: AnalysisInput? = null,
        var note: String? = null,
        /** 本屏素材（谁在说话 + 前文/本条原文），绑定那一刻由扫描器算好。 */
        var screen: ChatInsights.Screen = ChatInsights.Screen(),
        /** 队列满、这一条还没排上：卡片要如实说明「在等空位」，而不是看起来卡住了。 */
        var capacityPending: Boolean = false,
    )

    /**
     * 渲染指纹：只包含**会改变画面**的东西，用字段比较代替字符串拼接
     * （拼接本身在每拍每行都会产生临时对象，是之前热路径上没必要的一笔开销）。
     */
    private data class Fingerprint(
        val key: String,
        val state: Char,
        val moodId: Int,
        val failure: String?,
        val note: String?,
        val pending: Int,
        val expanded: Boolean,
        val night: Boolean,
        val trendVersion: Int,
        val capacity: Boolean,
        val screenId: Int,
    )

    private val cards = IdentityHashMap<View, Card>()
    private val unsupported = mutableSetOf<String>()

    /** holder 类 → 找主容器的方法（反射结果缓存，避免每次绑定重扫方法表）。 */
    private val mainContainerLookup = HashMap<Class<*>, java.lang.reflect.Method?>()

    /** 调色板缓存：夜间模式 + 莫奈引擎色板实例都没变时就复用同一份。 */
    private val paletteLock = Any()
    private var paletteNight = false
    private var paletteEngine: Any? = null
    private var paletteCache: Palette? = null

    /**
     * 画/刷新一张卡片。返回 true 表示这张卡已经就绪（含「本来就不该有卡」的情况）。
     */
    fun show(
        row: View,
        message: AnalysisInput?,
        note: String? = null,
        screen: ChatInsights.Screen = ChatInsights.Screen(),
        capacityPending: Boolean = false,
    ): Boolean {
        if (message == null || !ModulePrefs.enabled || !ModulePrefs.displayBubble ||
            !ModulePrefs.inScope(message.talker)
        ) {
            clear(row)
            return false
        }
        val key = message.key
        var state = cards[row]
        if (state != null && (state.key != key || state.container.parent !== state.parent)) {
            clear(row)
            state = null
        }
        if (state == null) {
            state = attach(row, key) ?: return false
            cards[row] = state
        }
        state.input = message
        state.note = note
        state.screen = screen
        state.capacityPending = capacityPending
        render(row, state, message, note)
        return true
    }

    // ------------------------------------------------------------------ 内容

    private fun render(row: View, card: Card, input: AnalysisInput, note: String? = null) {
        val key = input.key
        val mood = MoodStore.get(key)
        val failure = SignalAnalyzer.failure(key)
        val night = isNight(row)
        val state = when {
            failure != null -> 'f'
            mood != null -> 'm'
            !ModulePrefs.canAnalyze -> 'u'
            note != null -> 's'
            else -> 'p'
        }
        val fingerprint = Fingerprint(
            key = key,
            state = state,
            moodId = if (mood != null) System.identityHashCode(mood) else 0,
            failure = failure,
            note = note,
            pending = if (state == 'p') MoodStore.pendingCount() else 0,
            expanded = card.expanded,
            night = night,
            trendVersion = MoodStore.trendVersion,
            capacity = card.capacityPending,
            // 本屏素材只在重绑时换对象：用实例身份当指纹，避免每拍哈希整屏文本
            screenId = System.identityHashCode(card.screen),
        )
        if (fingerprint == card.fingerprint) return
        card.fingerprint = fingerprint

        // 指纹比对通过之后才取调色板（莫奈取色有成本，没变化就不该付）
        val pal = palette(row, night)
        val accent = when {
            failure != null -> pal.warning
            mood == null -> pal.muted
            mood.score > 0.25 -> pal.positive
            mood.score < -0.25 -> pal.negative
            else -> pal.neutral
        }
        card.stripe.background = stripeDrawable(accent, row)
        card.container.background = cardDrawable(pal, accent, row)

        when {
            failure != null -> {
                card.header.text = JevText.get(R.string.jev_card_failed_title)
                card.header.setTextColor(pal.warning)
                card.bars.visibility = View.GONE
                card.meta.visibility = View.GONE
                card.reading.text = failure + "\n" + JevText.get(R.string.jev_card_retry_hint)
                card.reading.visibility = View.VISIBLE
                card.advice.visibility = View.GONE
                card.insight.visibility = View.GONE
                card.footer.text = JevText.get(R.string.jev_card_retry_footer)
                card.footer.visibility = View.VISIBLE
            }

            mood != null -> renderMood(card, input, mood, pal, accent)

            !ModulePrefs.canAnalyze -> {
                card.header.text = JevText.get(R.string.jev_card_unconfigured_title)
                card.header.setTextColor(pal.muted)
                card.bars.visibility = View.GONE
                card.meta.visibility = View.GONE
                card.reading.text = JevText.get(R.string.jev_card_unconfigured_body)
                card.reading.visibility = View.VISIBLE
                card.advice.visibility = View.GONE
                card.insight.visibility = View.GONE
                card.footer.visibility = View.GONE
            }

            else -> {
                val queued = if (note == null) MoodStore.pendingCount() else 0
                card.header.text = if (note != null) {
                    JevText.get(R.string.jev_card_skipped_title)
                } else {
                    JevText.get(R.string.jev_card_pending_title)
                }
                card.header.setTextColor(pal.muted)
                card.bars.visibility = View.GONE
                card.meta.visibility = View.GONE
                card.reading.text = note ?: when {
                    // 队列满、这一条还没排上：说清「在等空位、不会丢」，别让它看起来卡住了
                    card.capacityPending -> JevText.get(R.string.jev_card_pending_capacity, queued)
                    // 一屏多条同时提交时给个排队交代：卡片看起来「卡住了」多数只是还没轮到
                    queued > 1 -> JevText.get(R.string.jev_card_pending_queued, queued)
                    else -> JevText.get(R.string.jev_card_pending_solo)
                }
                card.reading.visibility = View.VISIBLE
                card.advice.visibility = View.GONE
                card.insight.visibility = View.GONE
                card.footer.visibility = View.GONE
            }
        }
    }

    private fun renderMood(
        card: Card,
        input: AnalysisInput,
        mood: Mood,
        pal: Palette,
        accent: Int,
    ) {
        // 标题：主情绪 + （可选）与前几句的对比
        val arrow = if (ModulePrefs.showTrend) {
            MoodStore.trendOf(input.talker)?.let {
                val points = (abs(it) * 100).roundToInt()
                "· " + when {
                    points < 5 -> JevText.get(R.string.jev_trend_flat)
                    it > 0 -> JevText.get(R.string.jev_trend_up, points)
                    else -> JevText.get(R.string.jev_trend_down, points)
                }
            }
        } else {
            null
        }
        card.header.text = listOf(JevText.get(R.string.jev_card_title, mood.dominantName()), arrow.orEmpty())
            .filter { it.isNotEmpty() }.joinToString("  ")
        card.header.setTextColor(accent)

        // 情绪概率：收起时只给主情绪一条，展开时给全部
        val visibleBars = if (card.expanded) {
            mood.bars
        } else {
            mood.bars.filter { it.highlight }.ifEmpty { mood.bars.take(1) }
        }
        card.bars.setBars(visibleBars, accent, pal)
        card.bars.visibility = if (visibleBars.isEmpty()) View.GONE else View.VISIBLE

        // 元信息：场景 · 阶段 · 置信度
        val metaParts = mutableListOf<String>()
        mood.sceneLabel?.let { metaParts += it }
        mood.progressLabel?.let { metaParts += it }
        if (mood.confidence > 0.0) {
            metaParts += JevText.get(R.string.jev_meta_confidence, (mood.confidence * 100).roundToInt())
        }
        card.meta.text = metaParts.joinToString(" · ")
        card.meta.visibility = if (card.meta.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // 解读：标题一行（收起）/ 标题 + 问题 + 备选概率（展开）
        val reading = buildString {
            val title = mood.readingTitle
            if (title != null) {
                append(title)
                if (card.expanded) {
                    mood.readingQuestion?.let { append('\n').append(it) }
                    if (mood.readingOptions.isNotEmpty()) {
                        append('\n').append(mood.readingOptions.joinToString(" · ") { "${it.label} ${it.percent}%" })
                    }
                }
            } else {
                // 降级结果（只有第一轮）没有结构化解读：退回原来的正文裁剪
                append(bodyText(mood))
            }
        }.trim()
        card.reading.text = reading
        card.reading.visibility = if (reading.isBlank()) View.GONE else View.VISIBLE

        // 建议：最该看的一行，永远显示
        card.advice.text = mood.advice?.let { JevText.get(R.string.jev_advice, it) }.orEmpty()
        card.advice.visibility = if (mood.advice.isNullOrBlank()) View.GONE else View.VISIBLE

        // 页脚：降级说明优先，其次交互提示
        val expandable = canExpand(mood)
        val hint = when {
            mood.note != null -> JevText.get(R.string.jev_hint_note, mood.note)
            expandable && card.expanded -> JevText.get(R.string.jev_hint_collapse)
            expandable -> JevText.get(R.string.jev_hint_expand)
            else -> JevText.get(R.string.jev_hint_copy)
        }
        card.footer.text = hint
        card.footer.visibility = View.VISIBLE

        // 扩展块（建议强度 / 话题 / 互动均衡 / 趋势 / 风险）
        renderInsights(card, input, mood, pal, accent)
    }

    /**
     * 卡片扩展块：把 [ChatInsights] 的纯计算结果渲染成几行三语文案。
     *
     * 收起时只留「建议强度 + 话题」两行（三秒内看得完），展开后再补互动均衡、
     * 情绪趋势与风险依据；四个开关（[ModulePrefs.showLevel] / [ModulePrefs.showBalance] /
     * [ModulePrefs.showTopics] / [ModulePrefs.showTrendPanel]）全关就直接不画这一块。
     *
     * 计算失败一律降级为「不画」：卡片少一块不影响原来的解读与建议。
     */
    private fun renderInsights(
        card: Card,
        input: AnalysisInput,
        mood: Mood,
        pal: Palette,
        accent: Int,
    ) {
        if (!ModulePrefs.showLevel && !ModulePrefs.showBalance &&
            !ModulePrefs.showTopics && !ModulePrefs.showTrendPanel
        ) {
            card.insight.visibility = View.GONE
            return
        }
        val insight = runCatching {
            ChatInsights.build(mood, card.screen, MoodStore.recentScores(input.talker))
        }.getOrNull()
        if (insight == null) {
            card.insight.visibility = View.GONE
            return
        }
        val lines = ArrayList<String>(6)
        // 1) 建议强度：第一行，三秒内最该看到的东西
        if (ModulePrefs.showLevel) {
            insight.level?.let {
                lines += JevText.get(
                    R.string.jev_ext_level_title,
                    JevText.get(ChatInsights.levelLabel(it)),
                )
            }
        }
        // 2) 话题标签：一行，命中不到就说「没提取到」，不留空行
        if (ModulePrefs.showTopics) {
            val topics = insight.topics.map { JevText.get(it) }.filter { it.isNotEmpty() }
            lines += JevText.get(R.string.jev_ext_topics_title) + "：" +
                topics.joinToString(" / ").ifEmpty { JevText.get(R.string.jev_ext_topics_none) }
        }
        val riskLevel = insight.risk?.level ?: 0
        if (card.expanded) {
            insight.balance?.let { balance ->
                if (ModulePrefs.showBalance) {
                    lines += JevText.get(R.string.jev_ext_balance_title, balance.total)
                    lines += JevText.get(
                        R.string.jev_ext_balance_value,
                        balance.selfPercent,
                        balance.otherPercent,
                    )
                    if (balance.otherRun >= 2) {
                        lines += JevText.get(R.string.jev_ext_balance_other_run, balance.otherRun)
                    }
                    if (balance.selfRun >= 3) {
                        lines += JevText.get(R.string.jev_ext_balance_self_run, balance.selfRun)
                    }
                }
            }
            insight.trend?.let { trend ->
                if (ModulePrefs.showTrendPanel) {
                    val direction = when {
                        trend.direction > 0 -> R.string.jev_ext_trend_up
                        trend.direction < 0 -> R.string.jev_ext_trend_down
                        else -> R.string.jev_ext_trend_flat
                    }
                    lines += JevText.get(R.string.jev_ext_trend_title) + "：" +
                        JevText.get(direction, trend.samples)
                    lines += JevText.get(
                        R.string.jev_ext_trend_stat,
                        formatScore(trend.mean),
                        formatScore(trend.swing),
                    )
                }
            }
            insight.risk?.let { risk ->
                if (ModulePrefs.showLevel) {
                    lines += JevText.get(R.string.jev_risk_title) + " · " + JevText.get(riskLevelText(risk.level))
                    risk.notes.forEach { (id, args) -> lines += JevText.get(id, *args) }
                }
            }
        } else if (ModulePrefs.showLevel && riskLevel >= 1) {
            // 收起时只给一行风险等级：有依据才说，细节留给展开
            lines += JevText.get(R.string.jev_risk_title) + " · " + JevText.get(riskLevelText(riskLevel))
        }
        val text = lines.filter { it.isNotBlank() }.joinToString("\n")
        card.insight.text = text
        card.insight.setTextColor(if (riskLevel >= 1) pal.warning else if (card.expanded) pal.body else accent)
        card.insight.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun riskLevelText(level: Int): Int = when {
        level >= 2 -> R.string.jev_risk_level_high
        level == 1 -> R.string.jev_risk_level_medium
        else -> R.string.jev_risk_level_low
    }

    private fun formatScore(value: Double): String = String.format(Locale.US, "%.2f", value)

    /** 还有没有「收起来时看不到」的内容可展开。 */
    private fun canExpand(mood: Mood): Boolean =
        mood.bars.size > 1 || mood.readingQuestion != null || mood.readingOptions.isNotEmpty()

    /** 正文 = 去掉 Jev 头、「情绪：」行与「建议：」行后的其余解读内容（降级结果用）。 */
    private fun bodyText(mood: Mood): String = mood.detail.lines()
        .filterNot {
            it.startsWith(JevProtocol.header) || it.startsWith("情绪：") || it.startsWith("建议：")
        }
        .joinToString("\n")
        .trim()

    // ------------------------------------------------------------------ 挂载

    private fun attach(row: View, key: String): Card? {
        val root = row as? ViewGroup ?: return null
        val anchor = findBubble(root) ?: return null
        val rowPos = IntArray(2).also { root.getLocationOnScreen(it) }
        val anchorPos = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val left = (anchorPos[0] - rowPos[0]).coerceAtLeast(0)
        // 行还没测量时 root.width == 0，以前这里会直接放弃（卡片就此不再出现，
        // 直到下一次 show —— 表现为「有些行一直没有卡」）。现在退化用屏幕宽度兜底，
        // 卡片先按固定宽度挂上去，宿主测量完会正常布局。
        val availableWidth = if (root.width > 0) {
            root.width
        } else {
            row.resources.displayMetrics.widthPixels
        }
        val width = minOf(dp(row, 300), availableWidth - left - dp(row, 16))
        if (width < dp(row, 100)) return null

        val views = createViews(row)

        var branch: View = anchor
        var parent = branch.parent as? ViewGroup
        var target: ViewGroup? = null
        var assignedId: Int? = null
        while (parent != null && isInside(parent, root)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                val parentPos = IntArray(2).also { parent.getLocationOnScreen(it) }
                val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (anchorPos[0] - parentPos[0] - parent.paddingLeft).coerceAtLeast(0)
                    topMargin = dp(row, 3)
                    bottomMargin = dp(row, 6)
                }
                // Append only: do not shift the indexes of the host's original children.
                parent.addView(views.container, lp)
                target = parent
                break
            }
            if (parent === root) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        if (target == null && root is RelativeLayout && branch.parent === root &&
            root.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            val branchParams = branch.layoutParams as? RelativeLayout.LayoutParams ?: return null
            if (branchParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return null
            if (branch.id == View.NO_ID) {
                assignedId = View.generateViewId()
                branch.id = assignedId
            }
            val lp = RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, branch.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                leftMargin = left
                topMargin = dp(row, 3)
                bottomMargin = dp(row, 6)
            }
            root.addView(views.container, lp)
            target = root
        }
        if (target == null) {
            val signature = "${root.javaClass.name}/${anchor.parent?.javaClass?.name}"
            if (unsupported.add(signature)) MoodLog.w("暂不绘制未知气泡布局：$signature")
            return null
        }

        views.container.setOnClickListener { onClick(row, key) }
        views.container.setOnLongClickListener { onLongClick(row, key) }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { clear(v) }
        }
        row.addOnAttachStateChangeListener(detach)
        return Card(
            key = key,
            container = views.container,
            parent = target,
            anchor = branch,
            assignedId = assignedId,
            detach = detach,
            stripe = views.stripe,
            header = views.header,
            bars = views.bars,
            meta = views.meta,
            reading = views.reading,
            advice = views.advice,
            insight = views.insight,
            footer = views.footer,
        )
    }

    /**
     * 点击：失败态重试；有结论时展开/收起完整解读。
     *
     * 「展开」比「复制」更适合作为单击默认行为 —— 复制改成**长按**，
     * 卡片上常驻一行提示说明这两件事。
     */
    private fun onClick(row: View, key: String) {
        val card = cards[row] ?: return
        when {
            SignalAnalyzer.failure(key) != null -> YanwaiScanner.retryRow(row)
            MoodStore.get(key) != null -> {
                val input = card.input ?: return
                card.expanded = !card.expanded
                // 指纹里的 expanded 变了，render 会自然重画；这里不用手动清缓存
                render(row, card, input, card.note)
            }
        }
    }

    /** 长按：把整份解读（含情绪概率与建议）复制成纯文本。 */
    private fun onLongClick(row: View, key: String): Boolean {
        val mood = MoodStore.get(key) ?: return false
        val text = MoodMessageChannel.format(mood)
        val copied = runCatching {
            val cm = row.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("潜语解读", text))
            true
        }.getOrDefault(false)
        runCatching {
            Toast.makeText(row.context, if (copied) "已复制解读" else "复制失败", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // ------------------------------------------------------------------ 视图

    private class Views(
        val container: LinearLayout,
        val stripe: View,
        val header: TextView,
        val bars: BarsView,
        val meta: TextView,
        val reading: TextView,
        val advice: TextView,
        val insight: TextView,
        val footer: TextView,
    )

    private fun createViews(row: View): Views {
        val pal = palette(row, isNight(row))
        val stripe = View(row.context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(row, 3), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val header = TextView(row.context).apply {
            textSize = 11.5f
            setTextColor(pal.title)
            gravity = Gravity.START
            includeFontPadding = false
        }
        val bars = BarsView(row.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 5) }
        }
        val meta = TextView(row.context).apply {
            textSize = 10f
            setTextColor(pal.muted)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 4) }
        }
        val reading = TextView(row.context).apply {
            textSize = 12f
            setTextColor(pal.body)
            includeFontPadding = false
            setLineSpacing(dp(row, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 5) }
        }
        val advice = TextView(row.context).apply {
            textSize = 12f
            setTextColor(pal.title)
            includeFontPadding = false
            setLineSpacing(dp(row, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 5) }
        }
        val footer = TextView(row.context).apply {
            textSize = 9.5f
            setTextColor(pal.muted)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 5) }
        }
        val insight = TextView(row.context).apply {
            textSize = 11f
            setTextColor(pal.body)
            includeFontPadding = false
            setLineSpacing(dp(row, 2).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 5) }
        }
        val column = LinearLayout(row.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(row, 10), dp(row, 8), dp(row, 10), dp(row, 8))
            addView(header)
            addView(bars)
            addView(meta)
            addView(reading)
            addView(advice)
            addView(insight)
            addView(footer)
        }
        val container = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            id = View.generateViewId()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(stripe)
            // 宽度由调用方在布局参数里钉死（=气泡宽度）；这里让内容列占满剩余宽度
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return Views(container, stripe, header, bars, meta, reading, advice, insight, footer)
    }

    /**
     * 情绪概率横条。
     *
     * 纯 [View.onDraw] 实现：**零子 View**，因此不会给宿主行增加任何测量/布局节点
     * （这正是之前踩过崩溃的那类操作，这里刻意避开）。
     */
    private class BarsView(context: Context) : View(context) {
        private var bars: List<MoodBar> = emptyList()
        private var accent: Int = Color.GRAY
        private var labelColor: Int = Color.GRAY
        private var trackColor: Int = Color.LTGRAY
        private var mutedColor: Int = Color.GRAY

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setBars(value: List<MoodBar>, accentColor: Int, pal: Palette) {
            if (bars == value && accent == accentColor && labelColor == pal.body) return
            bars = value
            accent = accentColor
            labelColor = pal.body
            trackColor = pal.track
            mutedColor = pal.muted
            textPaint.textSize = sp(10f)
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val rowHeight = sp(15f).toInt()
            setMeasuredDimension(width, bars.size * rowHeight)
        }

        override fun onDraw(canvas: Canvas) {
            if (bars.isEmpty()) return
            val rowHeight = sp(15f).toInt()
            val labelWidth = sp(38f)
            val percentWidth = sp(34f)
            val barLeft = labelWidth
            val barRight = (width - percentWidth).coerceAtLeast(barLeft + sp(20f))
            val barHeight = sp(7f)
            textPaint.textAlign = Paint.Align.LEFT

            bars.forEachIndexed { index, bar ->
                val centerY = index * rowHeight + rowHeight / 2f
                trackPaint.color = trackColor
                fillPaint.color = if (bar.highlight) accent else mutedColor
                val top = centerY - barHeight / 2f
                val radius = barHeight / 2f
                canvas.drawRoundRect(barLeft, top, barRight, top + barHeight, radius, radius, trackPaint)
                val filled = (barRight - barLeft) * (bar.percent.coerceIn(0, 100) / 100f)
                if (filled > 0f) {
                    canvas.drawRoundRect(barLeft, top, barLeft + filled, top + barHeight, radius, radius, fillPaint)
                }
                // 文字基线：居中（ascent 是负数，所以是 centerY - (ascent+descent)/2）
                textPaint.color = if (bar.highlight) accent else labelColor
                val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(bar.name, 0f, baseline, textPaint)
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("${bar.percent}%", width.toFloat(), baseline, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }
        }

        private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
    }

    // ------------------------------------------------------------------ 配色 / 形状

    private class Palette(
        val card: Int,
        val stroke: Int,
        val title: Int,
        val body: Int,
        val track: Int,
        val muted: Int,
        val positive: Int,
        val neutral: Int,
        val negative: Int,
        val warning: Int,
    )

    private fun isNight(row: View): Boolean = row.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * 取色板（带缓存）。
     *
     * 莫奈取色 + Palette 构造在每次 show 里都做一遍是没有必要的：只有夜间模式切换或
     * 引擎色板换了一版才需要重算。缓存键就是这两样。
     */
    private fun palette(row: View, night: Boolean): Palette {
        // 注意缓存键用的是**引擎色板实例**（applied.value），不是 tokens(night) 的返回值 ——
        // 后者每次都新建一个 Tokens，拿它当键等于永远不命中。
        val engine = MonetColors.applied.value
        synchronized(paletteLock) {
            val cached = paletteCache
            if (cached != null && paletteNight == night && paletteEngine === engine) return cached
            val fresh = buildPalette(night)
            paletteNight = night
            paletteEngine = engine
            paletteCache = fresh
            return fresh
        }
    }

    private fun buildPalette(dark: Boolean): Palette {
        val base = if (dark) {
            Palette(0xFF23262B.toInt(), 0xFF343A42.toInt(), 0xFFD6DAE1.toInt(), 0xFFC3C8D0.toInt(),
                0xFF33373D.toInt(), 0xFF8B9099.toInt(), 0xFF5CC08A.toInt(), 0xFFE0A45A.toInt(),
                0xFFE07A70.toInt(), 0xFFE0A45A.toInt())
        } else {
            Palette(0xFFFFFFFF.toInt(), 0xFFE3E6EC.toInt(), 0xFF4A4F58.toInt(), 0xFF3C4149.toInt(),
                0xFFEDEFF3.toInt(), 0xFF8A8F98.toInt(), 0xFF2F9E63.toInt(), 0xFFCC8A2E.toInt(),
                0xFFC0453B.toInt(), 0xFFCC8A2E.toInt())
        }
        // 莫奈引擎生效时改用引擎色板。这张卡片是 WeKit 自己插进聊天行的，宿主的资源替换覆盖不到它，
        // 不接过来就会在已经莫奈化的会话里显得突兀（用户反馈「WeKit 添加/修改的组件没美化到位」）。
        // 语义色（正向/中性/负向/告警）仍用卡片自己的，情绪含义不跟着主题漂移。
        val tokens = MonetColors.tokens(dark) ?: return base
        return Palette(
            card = tokens.surfaceContainer,
            stroke = tokens.outline,
            title = tokens.onSurface,
            body = tokens.onSurfaceVariant,
            track = tokens.surfaceContainerHigh,
            muted = tokens.onSurfaceVariant,
            positive = base.positive,
            neutral = base.neutral,
            negative = base.negative,
            warning = base.warning,
        )
    }

    private fun cardDrawable(pal: Palette, accent: Int, row: View): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(row, 10).toFloat()
        setColor(pal.card)
        setStroke(dp(row, 1), pal.stroke)
    }

    private fun stripeDrawable(accent: Int, row: View): GradientDrawable {
        val radius = dp(row, 10).toFloat()
        return GradientDrawable().apply {
            // 只圆左侧两角，和卡片外框的圆角对齐
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            setColor(accent)
        }
    }

    private fun findBubble(root: ViewGroup): View? {
        val holder = root.tag
        if (holder != null) {
            val cls = holder.javaClass
            val method = synchronized(mainContainerLookup) {
                if (mainContainerLookup.containsKey(cls)) {
                    mainContainerLookup[cls]
                } else {
                    val found = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
                    mainContainerLookup[cls] = found
                    found
                }
            }
            val main = runCatching { method?.isAccessible = true; method?.invoke(holder) as? View }.getOrNull()
            if (main != null && main !== root && main.isShown && isInside(main, root)) return main
        }
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    fun clear(row: View) {
        val state = cards.remove(row) ?: return
        row.removeOnAttachStateChangeListener(state.detach)
        (state.container.parent as? ViewGroup)?.removeView(state.container)
        if (state.assignedId != null && state.anchor.id == state.assignedId) state.anchor.id = View.NO_ID
    }

    fun clearAll() { cards.keys.toList().forEach(::clear) }
    fun prune() { cards.keys.filter { !it.isAttachedToWindow }.forEach(::clear) }
    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}
