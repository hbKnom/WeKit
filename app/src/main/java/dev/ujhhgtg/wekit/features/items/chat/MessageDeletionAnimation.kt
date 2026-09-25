// SPDX-License-Identifier: GPL-3.0-only
package dev.ujhhgtg.wekit.features.items.chat

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Telegram 风格的消息删除动画。
 *
 * 消息被删除时，该行先被截成一张位图快照，随即碎裂为粒子向四周飞散并淡出；
 * 其余仍然存在的消息行从「删除前的落点」平滑滑回最终位置。
 *
 * 实现要点
 *  - 复用 [WeChatMessageViewApi] 的生命周期回调来识别「行被移除」而不是「行被回收」
 *    （滚动回收会紧接着触发 [onMessageViewRecycled]，删除不会）；
 *  - 在 detached 的瞬间抓取 baseline —— 此刻消息列表尚未重新布局，其余行仍是旧坐标，
 *    这正是计算归位位移所需的基准；
 *  - 归位只用 translationY 驱动，不触碰布局，因此不会触发消息列表重新测量；
 *  - 整批消失行共用一个覆盖层 View 绘制全部粒子，避免为每一块建 Animator。
 */
object MessageDeletionAnimation : SwitchFeature(), WeChatMessageViewApi.IMessageViewLifecycleListener {

    override val technicalId = "消息删除动画"
    override val nameRes = R.string.feature_message_deletion_animation_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_deletion_animation_description

    /** 微信在消息行上存放消息 id 的 tag key。 */
    private const val ROW_TAG_MSG_ID = 2113929222

    private const val SETTLE_MS = 250L
    private const val SETTLE_HFR_MS = 475L
    private const val DISSOLVE_MS = 520L
    private const val RECYCLE_WINDOW_MS = 200L
    private const val COLUMNS = 12
    private const val MIN_CHUNK_PX = 6
    private const val HFR_THRESHOLD = 90f

    // 还原上游使用的两条缓动曲线
    private val settleEasing = PathInterpolator(0.19919473f, 0.010644531f, 0.27920938f, 0.9102539f)
    private val dissolveEasing = PathInterpolator(0f, 0f, 0.58f, 1f)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** View -> 被 RecyclerView 回收的时间戳。 */
    private val recycledAt = WeakHashMap<View, Long>()

    /** View -> 最近一次已知的父容器。 */
    private val knownParent = WeakHashMap<View, WeakReference<ViewGroup>>()

    @Volatile
    private var installed = false

    private val isHighRefreshRate: Boolean
        get() = runCatching {
            Resources.getSystem().displayMetrics.refreshRate >= HFR_THRESHOLD
        }.getOrDefault(false)

    override fun onEnable() {
        if (installed) return
        installed = true
        WeChatMessageViewApi.addLifecycleListener(this)
    }

    override fun onDisable() {
        installed = false
        runCatching { WeChatMessageViewApi.removeLifecycleListener(this) }
        recycledAt.clear()
        knownParent.clear()
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        (view.parent as? ViewGroup)?.let { knownParent[view] = WeakReference(it) }
        recycledAt.remove(view)
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        // RecyclerView 回收 = 滚动复用，绝不能当成删除
        recycledAt[view] = SystemClock.uptimeMillis()
        knownParent.remove(view)
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo) {
        if (!installed) return

        val container = (view.parent as? ViewGroup)
            ?: knownParent[view]?.get()
            ?: return

        if (isContainerScrolling(container)) return
        if (view.width <= 0 || view.height <= 0) return

        val left = view.left + view.translationX.toInt()
        val top = view.top + view.translationY.toInt()
        val snapshot = runCatching { capture(view) }.getOrNull() ?: return
        val baseline = captureBaseline(container)

        // 延后一帧：真正被删除的行不会收到 onMessageViewRecycled
        mainHandler.post {
            val recycled = recycledAt[view]
            if (recycled != null && SystemClock.uptimeMillis() - recycled < RECYCLE_WINDOW_MS) return@post
            if (!installed) return@post

            runCatching {
                DissolveOverlay(container, snapshot, left, top, baseline).play(
                    dissolveMs = DISSOLVE_MS,
                    settleMs = if (isHighRefreshRate) SETTLE_HFR_MS else SETTLE_MS,
                    settleEasing = if (isHighRefreshRate) dissolveEasing else settleEasing,
                )
            }
        }
    }

    private fun capture(view: View): Bitmap {
        val picture = Picture()
        view.draw(picture.beginRecording(view.width, view.height))
        picture.endRecording()
        return Bitmap.createBitmap(picture)
    }

    private fun captureBaseline(container: ViewGroup): HashMap<Any, Float> {
        val out = HashMap<Any, Float>(max(8, container.childCount))
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val key: Any = child.getTag(ROW_TAG_MSG_ID) ?: child
            out.putIfAbsent(key, child.translationY + child.top)
        }
        return out
    }

    private fun isContainerScrolling(container: ViewGroup): Boolean = runCatching {
        val m = container.javaClass.getMethod("getScrollState")
        (m.invoke(container) as Int) != 0
    }.getOrDefault(false)

    private data class Particle(
        val bitmap: Bitmap,
        val startX: Float,
        val startY: Float,
        val vx: Float,
        val vy: Float,
        val rotation: Float,
    )

    private class DissolveOverlay(
        private val container: ViewGroup,
        snapshot: Bitmap,
        private val left: Int,
        private val top: Int,
        private val baseline: HashMap<Any, Float>,
    ) {

        private val particles: List<Particle> = buildParticles(snapshot)

        fun play(dissolveMs: Long, settleMs: Long, settleEasing: PathInterpolator) {
            if (particles.isNotEmpty()) {
                val view = ParticleView(container, particles)
                container.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                view.start(dissolveMs) {
                    runCatching { container.removeView(view) }
                }
            }
            settle(settleMs, settleEasing)
        }

        private fun settle(durationMs: Long, easing: PathInterpolator) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i) ?: continue
                if (child is ParticleView) continue
                val key: Any = child.getTag(ROW_TAG_MSG_ID) ?: child
                val oldTop = baseline[key] ?: continue
                val offset = oldTop - child.top
                if (abs(offset) < 0.5f) continue
                child.animate().cancel()
                child.translationY = offset
                child.animate()
                    .translationY(0f)
                    .setDuration(durationMs)
                    .setInterpolator(easing)
                    .start()
            }
        }

        private fun buildParticles(source: Bitmap): List<Particle> {
            if (source.width <= 0 || source.height <= 0) return emptyList()
            val chunk = max(MIN_CHUNK_PX, source.width / COLUMNS)
            val out = ArrayList<Particle>(64)
            val rnd = Random(SystemClock.uptimeMillis())
            val cx = source.width / 2f
            val cy = source.height / 2f

            var y = 0
            while (y < source.height) {
                var x = 0
                while (x < source.width) {
                    val w = minOf(chunk, source.width - x)
                    val h = minOf(chunk, source.height - y)
                    if (w > 0 && h > 0) {
                        val piece = Bitmap.createBitmap(source, x, y, w, h)
                        val dx = (x + w / 2f) - cx
                        val dy = (y + h / 2f) - cy
                        val len = max(1f, sqrt(dx * dx + dy * dy))
                        val speed = 70f + rnd.nextFloat() * 130f
                        out.add(
                            Particle(
                                bitmap = piece,
                                startX = (left + x).toFloat(),
                                startY = (top + y).toFloat(),
                                vx = dx / len * speed + (rnd.nextFloat() - 0.5f) * 60f,
                                vy = dy / len * speed + (rnd.nextFloat() - 0.5f) * 60f - 40f,
                                rotation = (rnd.nextFloat() - 0.5f) * 90f,
                            )
                        )
                    }
                    x += chunk
                }
                y += chunk
            }
            return out
        }
    }

    private class ParticleView(
        parent: ViewGroup,
        private val particles: List<Particle>,
    ) : View(parent.context) {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val dst = Rect()

        private var elapsed = 0L
        private var duration = 500L
        private var lastFrame = 0L

        init {
            isClickable = false
            isFocusable = false
            setWillNotDraw(false)
        }

        fun start(totalMs: Long, onFinished: () -> Unit) {
            duration = max(1L, totalMs)
            lastFrame = SystemClock.uptimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    val now = SystemClock.uptimeMillis()
                    elapsed += (now - lastFrame).coerceAtMost(48L)
                    lastFrame = now
                    if (elapsed >= duration) {
                        onFinished()
                        return
                    }
                    invalidate()
                    postOnAnimation(this)
                }
            }
            postOnAnimation(tick)
        }

        override fun onDraw(canvas: Canvas) {
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            if (progress >= 1f) return

            paint.alpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
            val t = dissolveEasing.getInterpolation(progress) * duration / 1000f

            for (p in particles) {
                val x = p.startX + p.vx * t
                val y = p.startY + p.vy * t + 260f * t * t
                if (y > height) continue
                src.set(0, 0, p.bitmap.width, p.bitmap.height)
                dst.set(
                    x.toInt(), y.toInt(),
                    x.toInt() + p.bitmap.width, y.toInt() + p.bitmap.height,
                )
                if (p.rotation == 0f) {
                    canvas.drawBitmap(p.bitmap, src, dst, paint)
                } else {
                    val save = canvas.save()
                    canvas.rotate(
                        p.rotation * progress,
                        dst.exactCenterX(),
                        dst.exactCenterY(),
                    )
                    canvas.drawBitmap(p.bitmap, src, dst, paint)
                    canvas.restoreToCount(save)
                }
            }
        }
    }
}
