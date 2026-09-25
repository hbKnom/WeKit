package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ContextMessage
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MessagePolicy
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MessageMetadata

/**
 * 言外扫描器（WeKit 版）。
 *
 * 上游 wechatmood 是独立模块，只能自己 DexKit 搜 `MicroMsg.MvvmChattingItem` 的
 * `[onBindView]` 绑定点、再每 700ms 轮询 ListView/Adapter 才能知道「哪条消息现在在屏幕上」。
 * 本模块跑在 WeKit 里，[WeChatMessageViewApi] 已经把这件事做完了：它 hook 同一个绑定点，
 * 维护 `View -> MessageInfo` 绑定表，并在绑定/解绑/回收时回调。因此这里：
 *
 *  - 去掉 DexKit 与 Adapter 搜索，绑定关系直接由 API 给出；
 *  - 去掉 ListView 遍历，可见行通过 [WeChatMessageViewApi.findBoundViews] 获取；
 *  - 保留一个轻量刷新节拍，仅用于把异步分析结果回填到已经挂好的卡片上。
 */
object YanwaiScanner : WeChatMessageViewApi.IMessageViewLifecycleListener, WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "YanwaiScanner"

    /** 结果回填节拍：只在仍有「已挂卡片但尚无结论」时继续跑。 */
    private const val TICK_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private var installed = false

    /** 正在等待结果的行，避免对同一行重复 show。 */
    private val awaiting = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private val tick = object : Runnable {
        override fun run() {
            if (!installed) return
            var pending = false
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                val input = bindingOf(view, message) ?: continue
                val done = MoodStore.get(input.key) != null || SignalAnalyzer.failure(input.key) != null
                if (!done) { pending = true; continue }
                runCatching { YanwaiBubble.show(view, input) }
                    .onFailure { MoodLog.w("气泡回填失败：${it.javaClass.simpleName}") }
            }
            YanwaiBubble.prune()
            if (pending) main.postDelayed(this, TICK_MS)
        }
    }

    fun install() {
        if (installed) return
        installed = true
        WeChatMessageViewApi.addLifecycleListener(this)
        WeChatMessageViewApi.addListener(this)
    }

    fun uninstall() {
        installed = false
        main.removeCallbacks(tick)
        awaiting.clear()
        runCatching {
            WeChatMessageViewApi.removeLifecycleListener(this)
            WeChatMessageViewApi.removeListener(this)
        }
        YanwaiBubble.clearAll()
    }

    fun refresh() {
        main.removeCallbacks(tick)
        if (installed) main.post(tick)
    }

    override fun onCreateView(param: HookParam, view: View) {
        if (!installed) return
        val message = WeChatMessageViewApi.getBoundMessage(view) ?: return
        handle(view, message, immediate = false)
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (!installed) return
        handle(view, message, immediate = true)
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo) {
        awaiting.remove(view)
        YanwaiBubble.clear(view)
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        awaiting.remove(view)
        YanwaiBubble.clear(view)
    }

    private fun handle(view: View, message: MessageInfo, immediate: Boolean) {
        if (!ModulePrefs.enabled) { YanwaiBubble.clear(view); return }
        val input = bindingOf(view, message)
        if (input == null) { YanwaiBubble.clear(view); return }
        if (ModulePrefs.canAnalyze) {
            val key = input.key
            SignalAnalyzer.submit(input) { key in visibleKeys() }
        }
        if (!ModulePrefs.showBadge) return
        runCatching { YanwaiBubble.show(view, input) }
            .onFailure { MoodLog.w("气泡绘制失败：${it.javaClass.simpleName}") }
        if (MoodStore.get(input.key) == null && SignalAnalyzer.failure(input.key) == null) {
            awaiting.add(view)
            if (immediate) refresh()
        }
    }

    /** View -> 分析输入。文本消息才产出，其余返回 null。 */
    private fun bindingOf(view: View, message: MessageInfo): AnalysisInput? {
        val text = MessageMetadata.incomingText(message) ?: return null
        val talker = message.talker
        if (talker.isBlank()) return null
        return AnalysisInput(
            text = text,
            talker = talker,
            context = collectContext(view, message),
            messageId = message.id,
            speaker = MessageMetadata.speaker(message),
        )
    }

    /**
     * 只从**当前已绑定**的可见行里取前文，与上游一致（不读数据库、不扫历史）。
     * 命中不了 10 条时有多少给多少，不做补造。
     */
    private fun collectContext(target: View, message: MessageInfo): List<ContextMessage> {
        val rows = WeChatMessageViewApi.findBoundViews { it.talker == message.talker }
        val ordered = rows.sortedBy { (view, _) -> IntArray(2).also { view.getLocationOnScreen(it) }[1] }
        val index = ordered.indexOfFirst { it.first === target }
        if (index <= 0) return emptyList()
        return ordered.subList(maxOf(0, index - MessagePolicy.MAX_CONTEXT_MESSAGES), index)
            .mapNotNull { (_, previous) ->
                val previousText = MessageMetadata.plainText(previous) ?: return@mapNotNull null
                ContextMessage(MessageMetadata.speaker(previous), previousText)
            }
    }

    private fun visibleKeys(): Set<String> =
        WeChatMessageViewApi.findBoundViews { true }
            .mapNotNull { (view, message) -> bindingOf(view, message)?.key }
            .toSet()
}
