package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ContextMessage
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MessageMetadata
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MessagePolicy

/**
 * 潜语扫描器（合并「言外潜台词」+「Jev 聊天决策」后的唯一入口）。
 *
 * 上游 wechatmood 是独立模块，只能自己 DexKit 搜 `MicroMsg.MvvmChattingItem` 的
 * `[onBindView]` 绑定点、再每 700ms 轮询 ListView/Adapter 才能知道「哪条消息现在在屏幕上」。
 * 本模块跑在 WeKit 里，[WeChatMessageViewApi] 已经把这件事做完了：它 hook 同一个绑定点，
 * 维护 `View -> MessageInfo` 绑定表，并在绑定/解绑/回收时回调。因此这里：
 *
 *  - 去掉 DexKit 与 Adapter 搜索，绑定关系直接由 API 给出；
 *  - 去掉 ListView 遍历，可见行通过 [WeChatMessageViewApi.findBoundViews] 获取。
 *
 * ## 本轮（第 15 轮）重构：把节拍从「结果通道」降级成「兜底」
 *
 * 用户实测「决策分析造成卡顿」的直接原因是这里：600ms 节拍会对**屏幕里每一行**重新
 * 扫描一遍，每扫一次都可能触发渲染。现在改成：
 *
 *  1. **结果靠推送，不靠轮询**：实现 [SignalAnalyzer.SettleListener]，成功/失败落地后
 *     直接把结论回填到对应那张卡上，卡片不再等下一拍（既更快又不闪）。
 *  2. **节拍只盯「还没结清的那几行」**（[awaiting]，通常只有个位数），不再遍历整屏；
 *     而且只在真的有未结清行时才继续排下一拍 —— 空闲时一个定时器都不留。
 *  3. **分析输入按行缓存一次**（[Row] + [inputs]）：反射取文本、收集前文（要排序整屏
 *     可见行）只在绑定/重绑时做，节拍里不再重复做。
 *  4. **看门狗区分「排队中」与「在跑」**：排队要占限速预算，用宽松上限；已经在跑用严格
 *     上限。以前一律 60s，一屏十几条排到队尾的消息会被误判成「分析超时」。
 *  5. **补扫**（[rescan]）：功能是开启时才装上钩子，此前已经绑好的行不会再有回调 ——
 *     以前这些行永远不分析（用户实测「选定的聊天有些行没有结果」）。现在开启/保存配置后
 *     主动补扫一次可见行并提交。
 *
 * 硬约束（不变）：每条已经提交的分析都必须在有限时间内变成「有结果」或「可见失败」，
 * 绝不允许「既没结果也没失败、节拍却一直空转」。
 */
object YanwaiScanner : WeChatMessageViewApi.IMessageViewLifecycleListener,
    WeChatMessageViewApi.ICreateViewListener, SignalAnalyzer.SettleListener {

    private const val TAG = "YanwaiScanner"

    /**
     * 兜底节拍间隔。结果主要靠 [SignalAnalyzer.SettleListener] 推送回填，
     * 这一拍只负责「排队提示刷新 + 看门狗 + 布局未就绪的卡片重试」，1s 足够。
     */
    private const val TICK_MS = 1000L

    /** 已经在跑的单个分析：超过这个时间没有结论就结清（分析侧自身上限是 70s）。 */
    private const val RUNNING_WATCHDOG_MS = 90_000L

    /** 还排在队里的分析：限速 + 排队有客观等待，给足余量，避免误杀排在队尾的消息。 */
    private const val QUEUED_WATCHDOG_MS = 180_000L

    /** 兜底节拍跑满这么多拍，就顺手补扫一次可见行（抓「没有绑定回调」的漏网消息）。 */
    private const val RESCAN_EVERY_TICKS = 10

    /** 行还没测量好时卡片挂不上，最多重试这么多拍再放弃（避免无限重试）。 */
    private const val MAX_DEFER_ATTEMPTS = 12

    private val main = Handler(Looper.getMainLooper())
    private var installed = false
    private var tickScheduled = false

    /** 正在等待结果的行（主线程私有），避免对同一行重复 show。 */
    private val awaiting = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** 已经画好内容、但因为行还没测量而没能挂上视图的行，等布局就绪后重试。 */
    private val deferred = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private val deferredAttempts = java.util.WeakHashMap<View, Int>()

    /**
     * 每行**算好一次**的分析输入 + 跳过原因，节拍与推送里直接复用。
     *
     * 这是「开启潜语后滑动明显变卡」的主要来源：原来每 400~600ms 会对屏幕里每一行重新
     * 算一遍输入 —— 反射取文本 + 收集前文（要遍历并排序本屏所有已绑定的行），
     * 20 行的屏幕就是每拍 20 次反射 + 几百次比较。现在只在绑定/重绑时算一次。
     */
    private val inputs = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Row>())

    /** 一行消息的分析输入与「不分析的原因」（过长/非文本时为非空）。 */
    private class Row(val input: AnalysisInput, val note: String?)

    /** key -> 提交时刻（elapsedRealtime）。用于看门狗判定，与系统时间跳变无关。 */
    private val submittedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 已经记过的异常签名：同一类绘制异常只落一行日志，不刷屏。 */
    private val loggedSignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private var tickCount = 0

    // ------------------------------------------------------------------ 兜底节拍

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (!installed) return
            if (!ModulePrefs.enabled) {
                awaiting.clear()
                deferred.clear()
                deferredAttempts.clear()
                return
            }
            val now = SystemClock.elapsedRealtime()
            var keep = false
            for (view in awaiting.toList()) {
                val row = rowFor(view)
                if (row == null) {
                    awaiting.remove(view)
                    continue
                }
                val key = row.input.key
                if (row.note != null || isSettled(key)) {
                    awaiting.remove(view)
                    submittedAt.remove(key)
                    if (!showCard(view, row)) deferred.add(view)
                    continue
                }
                var submitted = submittedAt[key]
                if (submitted == null && MoodStore.isPending(key)) {
                    submitted = now
                    submittedAt[key] = now
                }
                if (submitted == null) {
                    // 既没提交过、也不在队列里：现在不该有卡（未配置 Key / 命中失败冷却）
                    awaiting.remove(view)
                    continue
                }
                val started = SignalAnalyzer.startedAt(key)
                val waited = if (started != null) now - started else now - submitted
                val limit = if (started != null) RUNNING_WATCHDOG_MS else QUEUED_WATCHDOG_MS
                if (waited > limit) {
                    // 结清会回调 onSettled 立刻回填；这一拍继续跑一次做确认
                    SignalAnalyzer.clearStuck(key)
                    keep = true
                    continue
                }
                keep = true
                // 排队中的卡片要跟着队列缩短更新「前面还有几条」
                showCard(view, row)
            }
            keep = retryDeferred() || keep
            YanwaiBubble.prune()
            if (keep) {
                scheduleTick()
                tickCount++
                // 有未结清的行时，顺手周期性补扫一次（抓没有绑定回调的漏网消息）
                if (tickCount % RESCAN_EVERY_TICKS == 0) rescan()
            }
        }
    }

    /** 重试「内容已就绪但行还没测量」的卡片；返回是否还需要继续跑节拍。 */
    private fun retryDeferred(): Boolean {
        if (deferred.isEmpty()) return false
        var keep = false
        for (view in deferred.toList()) {
            val row = rowFor(view)
            if (row == null) {
                deferred.remove(view)
                deferredAttempts.remove(view)
                continue
            }
            if (showCard(view, row)) {
                deferred.remove(view)
                deferredAttempts.remove(view)
                continue
            }
            val attempts = (deferredAttempts[view] ?: 0) + 1
            deferredAttempts[view] = attempts
            if (attempts >= MAX_DEFER_ATTEMPTS) {
                deferred.remove(view)
                deferredAttempts.remove(view)
                logOnce("defer", "卡片挂载失败：行布局一直未就绪")
            } else {
                keep = true
            }
        }
        return keep
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        main.postDelayed(tick, TICK_MS)
    }

    /** 只在主线程改 View 相关状态；绑定回调理论上就在主线程，这里防御一次。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === main.looper) block() else main.post(block)
    }

    private fun logOnce(signature: String, message: String) {
        if (!loggedSignatures.add(signature)) return
        MoodLog.w(message)
    }

    // ------------------------------------------------------------------ 生命周期

    fun install() {
        if (installed) return
        installed = true
        WeChatMessageViewApi.addLifecycleListener(this)
        WeChatMessageViewApi.addListener(this)
        SignalAnalyzer.addListener(this)
        // 回插通道一次性关闭迁移（用户明确要求默认不要再往会话里插系统消息）
        runCatching { ModulePrefs.migrateInsertOffOnce() }
        MoodLog.i(
            "$TAG 已挂载（气泡=${ModulePrefs.displayBubble}，回插会话=${ModulePrefs.displayMessage}，" +
                "范围=${ModulePrefs.scopeSummary()}）"
        )
        // 装上钩子时屏幕上可能已经有绑好的消息，补扫一次
        onMain { rescan() }
    }

    fun uninstall() {
        installed = false
        main.removeCallbacks(tick)
        tickScheduled = false
        awaiting.clear()
        deferred.clear()
        deferredAttempts.clear()
        submittedAt.clear()
        inputs.clear()
        runCatching {
            WeChatMessageViewApi.removeLifecycleListener(this)
            WeChatMessageViewApi.removeListener(this)
            SignalAnalyzer.removeListener(this)
        }
        YanwaiBubble.clearAll()
        MoodLog.i("$TAG 已卸载")
    }

    /** 设置页改完配置后调用：补扫可见行 + 起一拍兜底。 */
    fun refresh() {
        main.removeCallbacks(tick)
        tickScheduled = false
        if (!installed) return
        onMain {
            rescan()
            if (awaiting.isNotEmpty() || deferred.isNotEmpty()) scheduleTick()
        }
    }

    /**
     * 补扫当前所有可见行：建立/刷新分析输入，并对「还没有任何进度」的文本消息提交分析。
     *
     * 这是「选定的聊天每条文本消息都要被分析」的兜底：功能开启、补填 API Key、
     * 换作用范围之后，已经绑定在屏幕上的行不会再收到绑定回调，只有这里会把它们捞回来。
     */
    fun rescan() {
        if (!installed) return
        if (!ModulePrefs.enabled) return
        var bound = 0
        for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
            bound++
            handle(view, message)
        }
        if (bound > 0 && (awaiting.isNotEmpty() || deferred.isNotEmpty())) scheduleTick()
    }

    override fun onCreateView(param: HookParam, view: View) {
        if (!installed) return
        val message = WeChatMessageViewApi.getBoundMessage(view) ?: return
        onMain { handle(view, message) }
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (!installed) return
        onMain { handle(view, message) }
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo, rebound: Boolean) {
        // 重绑只是换了消息：卡片由紧接着的 show() 按新 key 覆写，这里不要清（清了会闪一下）。
        if (rebound) return
        onMain { forget(view) }
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        onMain { forget(view) }
    }

    /** 分析结论落地（成功或失败）：立刻把对应那张卡刷新出来，不等下一拍。 */
    override fun onSettled(input: AnalysisInput, mood: Mood?, reason: String?) {
        if (!installed) return
        main.post {
            if (!installed) return@post
            submittedAt.remove(input.key)
            fill(input.key)
        }
    }

    // ------------------------------------------------------------------ 主逻辑

    private fun handle(view: View, message: MessageInfo) {
        if (!ModulePrefs.enabled) {
            forget(view)
            return
        }
        // 绑定已经换人（异步回调期间列表又滚了一格）：交给新绑定的那次回调处理。
        val actual = WeChatMessageViewApi.getBoundMessage(view)
        if (actual != null && actual.id != message.id) return
        val row = rowOf(view, message) ?: run {
            forget(view)
            return
        }
        val input = row.input
        // 不在作用域的聊天：一张卡都不要留（否则会永远显示「正在分析…」——
        // SignalAnalyzer.submit 会拒收，却没人告诉卡片「这次不会分析」）。
        if (!ModulePrefs.inScope(input.talker)) {
            forget(view)
            return
        }
        val key = input.key
        if (row.note == null) submitIfNeeded(key, input)
        val settled = isSettled(key)
        if (!settled && (submittedAt.containsKey(key) || MoodStore.isPending(key))) awaiting.add(view)
        if (!showCard(view, row)) deferred.add(view)
        if (awaiting.isNotEmpty() || deferred.isNotEmpty()) scheduleTick()
    }

    /**
     * 提交一次即可（claim 去重）：气泡通道与「回插会话」通道共用这一份请求与结果。
     *
     * 「没有任何进度」时才提交 —— 有结果、有失败（冷却中）、正在排队/在跑的都不重复提交。
     * 这条路径也是「功能开启前就已经绑好的行」被补扫时唯一会走到的提交入口。
     */
    private fun submitIfNeeded(key: String, input: AnalysisInput) {
        if (submittedAt.containsKey(key) || MoodStore.isPending(key)) return
        if (MoodStore.get(key) != null) return
        if (SignalAnalyzer.failure(key) != null) return
        if (SignalAnalyzer.submit(input) != null) {
            submittedAt.putIfAbsent(key, SystemClock.elapsedRealtime())
        }
    }

    private fun isSettled(key: String): Boolean =
        MoodStore.get(key) != null || SignalAnalyzer.failure(key) != null

    /** 画/刷新卡片；返回 true 表示这张卡已经就绪。 */
    private fun showCard(view: View, row: Row): Boolean {
        if (!ModulePrefs.displayBubble) return true
        return runCatching { YanwaiBubble.show(view, row.input, row.note) }
            .getOrElse {
                logOnce("bubble", "气泡绘制失败：${it.javaClass.simpleName} ${it.message}")
                true
            }
    }

    /**
     * 把某个 key 的结论填到它对应的卡片上。
     *
     * 先看 [awaiting]（通常个位数），找不到再从整屏缓存里捞一次 ——
     * 保证「分析完了但卡片没更新」这种情况不会因为没被登记而丢掉。
     */
    private fun fill(key: String) {
        val targets = LinkedHashMap<View, Row>()
        for (view in awaiting.toList()) {
            val row = rowFor(view) ?: continue
            if (row.input.key == key) targets[view] = row
        }
        if (targets.isEmpty()) {
            for ((view, row) in snapshotRows()) {
                if (row.input.key == key) targets[view] = row
            }
        }
        if (targets.isEmpty()) return
        awaiting.removeAll(targets.keys)
        deferred.removeAll(targets.keys)
        for ((view, row) in targets) {
            if (!showCard(view, row)) deferred.add(view)
        }
    }

    private fun forget(view: View) {
        awaiting.remove(view)
        deferred.remove(view)
        deferredAttempts.remove(view)
        val row = inputs.remove(view)
        row?.let { submittedAt.remove(it.input.key) }
        YanwaiBubble.clear(view)
    }

    /** 取当前绑定行（缓存优先）。 */
    private fun rowFor(view: View): Row? =
        WeChatMessageViewApi.getBoundMessage(view)?.let { rowOf(view, it) }

    /**
     * 取这一行的分析输入（带缓存）。
     *
     * 缓存命中条件是「同一个 View 仍然绑着同一条消息」—— 重绑（滚出滚入）后
     * [WeChatMessageViewApi] 会重新走一次 handle()，所以这里只需要防御性地校验一次。
     */
    private fun rowOf(view: View, message: MessageInfo): Row? {
        val cached = inputs[view]
        if (cached != null && cached.input.messageId == message.id && cached.input.talker == message.talker) {
            return cached
        }
        val fresh = buildRow(view, message) ?: run {
            inputs.remove(view)
            return null
        }
        inputs[view] = fresh
        return fresh
    }

    private fun snapshotRows(): List<Pair<View, Row>> =
        synchronized(inputs) { inputs.entries.map { it.key to it.value } }

    /**
     * View + MessageInfo -> 分析输入。
     *
     * 非文本消息返回 null（不画卡）；文本过长时返回带 [Row.note] 的行 ——
     * 以前这种消息既不提交也不画卡，用户看到的是「这一条什么都没有」，
     * 现在明确写清「本条内容过长，未分析」，不会让人以为是功能漏掉了一条。
     */
    private fun buildRow(view: View, message: MessageInfo): Row? {
        // 「也分析我发的消息」为开时才把我方消息纳入（默认只分析对方，与上游一致）。
        val text = MessageMetadata.analyzeText(message, ModulePrefs.analyzeSelf) ?: return null
        val talker = message.talker
        if (talker.isBlank()) return null
        val input = AnalysisInput(
            text = text,
            talker = talker,
            context = collectContext(view, message),
            messageId = message.id,
            speaker = MessageMetadata.speaker(message),
            createdAt = runCatching { message.createTime }.getOrDefault(0L),
        )
        val note = if (MessagePolicy.textOrNull(text) == null) {
            "本条内容超过 ${MessagePolicy.MAX_CHARACTERS} 字，为避免把长文整段发给模型，本条不分析。"
        } else {
            null
        }
        return Row(input, note)
    }

    /**
     * 只从**当前已绑定**的可见行里取前文，与上游一致（不读数据库、不扫历史）。
     * 命中不了 10 条时有多少给多少，不做补造。
     *
     * 注意：这里用行在列表里的纵向顺序（而不是 getLocationOnScreen），
     * 既避免在非主线程碰 View 的屏幕坐标，也不受状态栏/输入法偏移影响。
     */
    private fun collectContext(target: View, message: MessageInfo): List<ContextMessage> {
        val limit = ModulePrefs.contextLimit
        if (limit <= 0) return emptyList()
        val rows = WeChatMessageViewApi.findBoundViews { it.talker == message.talker }
        val ordered = rows.sortedBy { (view, _) -> view.top }
        val index = ordered.indexOfFirst { it.first === target }
        if (index <= 0) return emptyList()
        return ordered.subList(maxOf(0, index - limit), index)
            .mapNotNull { (_, previous) ->
                val previousText = MessageMetadata.plainText(previous) ?: return@mapNotNull null
                ContextMessage(MessageMetadata.speaker(previous), previousText)
            }
    }

    /**
     * 气泡点击「分析失败，点击重试」：清掉这条的失败与冷却，**真正重新提交一次**。
     *
     * 之前这里只调 refresh()（仅跑节拍），卡片会停在「正在分析…」而永远没有新请求 ——
     * 点击重试等于没反应，属于必须修掉的逻辑错误。
     */
    fun retryRow(view: View) {
        if (!installed) return
        main.post {
            val message = WeChatMessageViewApi.getBoundMessage(view) ?: return@post
            val row = rowOf(view, message) ?: return@post
            if (row.note != null) return@post
            SignalAnalyzer.retryFailure(row.input.key)
            MoodStore.release(row.input.key)
            submittedAt.remove(row.input.key)
            deferred.remove(view)
            deferredAttempts.remove(view)
            // 失败/超时的行重新真的提交一次：结果要么回填，要么再给一条可见失败。
            handle(view, message)
        }
    }

    /**
     * 「重新分析本屏」/「重试全部失败」：清空结果、失败与进度后，对当前所有可见行重新提交。
     * 是一次真实的全量重算（会消耗请求额度），因此只在设置页里由用户主动触发。
     */
    fun reanalyzeVisible() {
        if (!installed) return
        onMain {
            submittedAt.clear()
            awaiting.clear()
            deferred.clear()
            deferredAttempts.clear()
            inputs.clear()
            SignalAnalyzer.clearResults()
            YanwaiBubble.clearAll()
            var count = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                if (rowOf(view, message) == null) continue
                count++
                handle(view, message)
            }
            MoodLog.i("已对 $count 行可见消息重新提交分析")
        }
    }

    /** 「重试全部失败」：只重新提交**还没有结果**的可见行（成功的保持不动，省额度）。 */
    fun reanalyseFailed() {
        if (!installed) return
        onMain {
            SignalAnalyzer.retryAllFailures()
            var count = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                val row = rowOf(view, message) ?: continue
                if (row.note != null) continue
                if (MoodStore.get(row.input.key) != null) continue
                submittedAt.remove(row.input.key)
                MoodStore.release(row.input.key)
                count++
                handle(view, message)
            }
            MoodLog.i("已重新提交 $count 行失败的可见消息")
        }
    }

    /** 供设置页/看门狗使用：清空计时器（例如用户手动关闭功能后重新打开）。 */
    fun forgetProgress() {
        submittedAt.clear()
        inputs.clear()
        awaiting.clear()
        deferred.clear()
        deferredAttempts.clear()
    }
}
