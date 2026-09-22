package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

object AutoEnableSendOriginalMedia : SwitchFeature() {

    override val technicalId = "自动启用发送原图"
    override val nameRes = R.string.feature_auto_enable_send_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_original_media_description

    /**
     * 相册预览页；发送原图的勾选状态在它的 iniView / 数量回调里被反复重建。
     * GalleryEntryUI 是「气泡 / 相机」路径的中转页，它把整个 intent 透传给
     * ImagePreviewUI，所以这两个 flag 必须由它一起带上，否则那条路径拿不到。
     */
    private val PICKER_PAGES = listOf(
        ALBUM_PREVIEW_UI,
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
        "com.tencent.mm.plugin.gallery.ui.GalleryEntryUI",
    )

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val TAG = "AutoSendOriginal"

    /** 「原图」开关在三语文案里的写法（宿主随系统语言切换）。 */
    private val ORIGINAL_LABELS = listOf("原图", "原圖")

    override fun onEnable() {
        PICKER_PAGES.forEach { name ->
            // toClassOrNull：宿主改名/缺失时不能抛 ClassNotFoundException 把整个 onEnable 打断
            // （一旦抛出，后面的 Hook 全部不会安装，表现就是"功能整条消失"）。
            name.toClassOrNull()?.hookBeforeOnCreate {
                val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
                // Upstream 09-12 also sets WeChat's newer "key_send_raw_image" flag, which is
                // what makes the picker keep original moments images and original video files
                // (send_raw_img alone only covers the chat path).
                activity.intent.putExtra("send_raw_img", true)
                activity.intent.putExtra("key_send_raw_image", true)

                // 真机反馈：仅改 intent flag 时勾选框仍可能没勾上（宿主在 iniView 里按自己的
                // 状态重建勾选行）。所以再补一条事件兜底：页面起来后在视图树里找到「原图」
                // 勾选框，真的把它点上（走宿主自己的 performClick，等于用户手点，语义一致）。
                attachOriginalAutoCheck(activity)
            }
        }

        // Keep the "send as original" toggle logically checked so the picker does not drop the
        // raw flag when it refreshes its own checkbox row (选数量变化时宿主会把勾选状态重置)。
        if (!methodUpdateSendAsMediaGroupViews.isPlaceholder) {
            methodUpdateSendAsMediaGroupViews.hookBefore {
                // 该方法的最后一个 boolean 参数就是原图开关；先做类型/越界保护，
                // 参数位对不上的宿主版本直接跳过（宁可不动，也不要改错参数静默失效）。
                if (args.size > 3 && args[3] is Boolean) {
                    args[3] = true
                }
            }
        }
    }

    // ------------------------------------------------------------------ 勾选框兜底

    /**
     * 页面打开后短时间内重试几次再勾选：宿主先绑数据、后重建勾选行，第一次查询往往是空树。
     *
     * 只在打开后这几秒内自动勾选 —— 用户之后手动取消不会被反复夺回（尊重用户操作）。
     */
    private fun attachOriginalAutoCheck(activity: Activity) {
        // 用主线程 Handler 而不是 decorView.postDelayed：hook 发生在 onCreate 之前，
        // 此时 decorView 可能还没 attach，挂在它上面的 runnable 会一直不执行；
        // 每次执行时再取当前 decorView，避免拿到被宿主重建掉的旧树。
        val handler = Handler(Looper.getMainLooper())
        listOf(300L, 800L, 1600L, 2600L).forEach { delay ->
            runCatching {
                handler.postDelayed({
                    runCatching {
                        if (activity.isFinishing || activity.isDestroyed) return@runCatching
                        val root = activity.window?.decorView ?: return@runCatching
                        autoCheckOriginal(root)
                    }
                }, delay)
            }
        }
    }

    private fun autoCheckOriginal(root: View) {
        runCatching {
            val toggle = findOriginalToggle(root)
            if (toggle == null) {
                WeLogger.d(TAG, "original toggle not found yet")
                return
            }
            if (toggle.isChecked) {
                WeLogger.d(TAG, "original toggle already checked")
                return
            }
            val clicked = runCatching { toggle.performClick() }.getOrDefault(false)
            WeLogger.i(TAG, "auto check original: click=$clicked checked=${toggle.isChecked}")
        }.onFailure { WeLogger.w(TAG, "auto check original failed", it) }
    }

    /**
     * 在视图树里找「原图」勾选框。
     *
     * 两种宿主布局都覆盖：勾选框自己带文案（CompoundButton.text），或文案是它同父容器里的
     * 兄弟 TextView（微信相册的「原图」行常见写法）。只认这个标签，避免误点同页其他开关。
     */
    private fun findOriginalToggle(root: View): CompoundButton? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is CompoundButton && isOriginalToggle(view)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return null
    }

    private fun isOriginalToggle(button: CompoundButton): Boolean {
        if (button.text?.let { text -> ORIGINAL_LABELS.any { text.contains(it) } } == true) return true

        val parent = button.parent as? ViewGroup ?: return false
        for (index in 0 until parent.childCount) {
            val sibling = parent.getChildAt(index)
            if (sibling === button) continue
            val label = (sibling as? TextView)?.text?.toString() ?: continue
            if (ORIGINAL_LABELS.any { label.contains(it) }) return true
        }
        return false
    }

    /**
     * 相册预览页的数量变化回调（包含宿主自身埋点字符串 updateSendAsMediaGroupViews）。
     * 必须锁定 declaredClass：早前只按字符串匹配，可能解析到宿主别处同名埋点的方法，
     * args[3] 便不是原图开关 → 越界/改错参数被 hook 框架吞掉，功能静默失效。
     */
    private val methodUpdateSendAsMediaGroupViews by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            usingEqStrings("updateSendAsMediaGroupViews")
        }
    }
}
