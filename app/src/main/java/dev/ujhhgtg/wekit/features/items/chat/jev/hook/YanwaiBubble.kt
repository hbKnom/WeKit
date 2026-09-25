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
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.JevProtocol
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodBar
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import java.util.IdentityHashMap
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
 * 卡片形态（合并后重做，取代原来那块纯文字 TextView）：
 * ```
 * ┃ 潜语 · 事件解读            较前几句 ↑12
 * ┃ 开心 ▓▓▓▓▓▓░░░░ 58%     ← 纯 onDraw 的概率条，无子 View
 * ┃ 平静 ▓▓▓░░░░░░░ 27%
 * ┃ 生气 ▓░░░░░░░░░ 15%
 * ┃ 【解读】这句在等一个具体答复……
 * ┃ 建议：先回一句具体的安排，别只说"再说"
 * ```
 * 左侧竖条颜色 = 情绪倾向（正向绿 / 中性橙 / 负向红），失败态换成告警色。
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
        val body: TextView,
        val advice: TextView,
        var fingerprint: String = "",
    )

    private val cards = IdentityHashMap<View, Card>()
    private val unsupported = mutableSetOf<String>()

    fun show(row: View, message: AnalysisInput?): Boolean {
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
        render(row, state, message)
        return true
    }

    // ------------------------------------------------------------------ 内容

    private fun render(row: View, card: Card, input: AnalysisInput) {
        val key = input.key
        val mood = MoodStore.get(key)
        val failure = SignalAnalyzer.failure(key)
        val pal = palette(row)

        val phrase = when {
            failure != null -> "failure:$failure"
            mood != null -> "ok:${mood.label}:${(mood.score * 100).roundToInt()}:" +
                mood.bars.joinToString(",") { "${it.name}${it.percent}${if (it.highlight) "*" else ""}" } +
                ":" + mood.advice.orEmpty() + ":" + mood.detail
            !ModulePrefs.canAnalyze -> "unconfigured"
            else -> "pending"
        }
        if (phrase == card.fingerprint) return
        card.fingerprint = phrase

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
                card.header.text = "潜语 · 分析失败"
                card.header.setTextColor(pal.warning)
                card.bars.visibility = View.GONE
                card.body.text = "$failure\n（点击这张卡重新分析）"
                card.advice.visibility = View.GONE
            }

            mood != null -> {
                val trend = MoodStore.trendOf(input.talker)
                val arrow = trend?.let {
                    val points = (abs(it) * 100).roundToInt()
                    when {
                        points < 5 -> "· 与前几句持平"
                        it > 0 -> "· 较前几句 ↑$points"
                        else -> "· 较前几句 ↓$points"
                    }
                }.orEmpty()
                card.header.text = listOf("潜语 · ${mood.label}", arrow)
                    .filter { it.isNotEmpty() }.joinToString("  ")
                card.header.setTextColor(accent)
                card.bars.setBars(mood.bars, accent, pal)
                card.bars.visibility = if (mood.bars.isEmpty()) View.GONE else View.VISIBLE
                card.body.text = bodyText(mood)
                card.body.visibility = if (card.body.text.isNullOrBlank()) View.GONE else View.VISIBLE
                card.advice.text = mood.advice?.let { "建议：$it" }.orEmpty()
                card.advice.visibility = if (mood.advice.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            !ModulePrefs.canAnalyze -> {
                card.header.text = "潜语 · 未配置"
                card.header.setTextColor(pal.muted)
                card.bars.visibility = View.GONE
                card.body.text = "还没填写模型渠道与 API Key，填写后本卡会自动开始分析。"
                card.advice.visibility = View.GONE
            }

            else -> {
                card.header.text = "潜语 · 正在分析"
                card.header.setTextColor(pal.muted)
                card.bars.visibility = View.GONE
                card.body.text = "已送出发送给模型，结论会回填到这张卡上。"
                card.advice.visibility = View.GONE
            }
        }
    }

    /** 正文 = 去掉 Jev 头、「情绪：」行与「建议：」行后的其余解读内容。 */
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
        val width = minOf(dp(row, 300), root.width - left - dp(row, 16))
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
                parent.addView(card.container, lp)
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
            root.addView(card.container, lp)
            target = root
        }
        if (target == null) {
            val signature = "${root.javaClass.name}/${anchor.parent?.javaClass?.name}"
            if (unsupported.add(signature)) MoodLog.w("暂不绘制未知气泡布局：$signature")
            return null
        }

        views.container.setOnClickListener { onClick(row, key) }
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
            body = views.body,
            advice = views.advice,
        )
    }

    /** 点击：失败态重试，成功态复制解读到剪贴板。 */
    private fun onClick(row: View, key: String) {
        val mood = MoodStore.get(key)
        val failure = SignalAnalyzer.failure(key)
        when {
            failure != null -> YanwaiScanner.retryRow(row)
            mood != null -> {
                val text = MoodMessageChannel.format(mood)
                val copied = runCatching {
                    val cm = row.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("潜语解读", text))
                    true
                }.getOrDefault(false)
                runCatching {
                    Toast.makeText(row.context, if (copied) "已复制解读" else "复制失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 视图

    private class Views(
        val container: LinearLayout,
        val stripe: View,
        val header: TextView,
        val bars: BarsView,
        val body: TextView,
        val advice: TextView,
    )

    private fun createViews(row: View): Views {
        val pal = palette(row)
        val stripe = View(row.context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(row, 3), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val header = TextView(row.context).apply {
            textSize = 11f
            setTextColor(pal.title)
            gravity = Gravity.START
            includeFontPadding = false
        }
        val bars = BarsView(row.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 4) }
        }
        val body = TextView(row.context).apply {
            textSize = 12f
            setTextColor(pal.body)
            includeFontPadding = false
            setLineSpacing(dp(row, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 2) }
        }
        val advice = TextView(row.context).apply {
            textSize = 12f
            setTextColor(pal.title)
            includeFontPadding = false
            setLineSpacing(dp(row, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(row, 4) }
        }
        val column = LinearLayout(row.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(row, 9), dp(row, 7), dp(row, 9), dp(row, 7))
            addView(header)
            addView(bars)
            addView(body)
            addView(advice)
        }
        val container = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            id = View.generateViewId()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(stripe)
            // 宽度由调用方在布局参数里钉死（=气泡宽度）；这里让内容列占满剩余宽度
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return Views(container, stripe, header, bars, body, advice)
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
            if (bars == value && accent == accentColor) return
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

    private fun palette(row: View): Palette {
        val dark = row.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            Palette(0xFF23262B.toInt(), 0xFF343A42.toInt(), 0xFFD6DAE1.toInt(), 0xFFC3C8D0.toInt(),
                0xFF33373D.toInt(), 0xFF8B9099.toInt(), 0xFF5CC08A.toInt(), 0xFFE0A45A.toInt(),
                0xFFE07A70.toInt(), 0xFFE0A45A.toInt())
        } else {
            Palette(0xFFFFFFFF.toInt(), 0xFFE3E6EC.toInt(), 0xFF4A4F58.toInt(), 0xFF3C4149.toInt(),
                0xFFEDEFF3.toInt(), 0xFF8A8F98.toInt(), 0xFF2F9E63.toInt(), 0xFFCC8A2E.toInt(),
                0xFFC0453B.toInt(), 0xFFCC8A2E.toInt())
        }
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
            val method = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
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
