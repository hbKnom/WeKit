package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.HashMap
import java.util.WeakHashMap

/**
 * 头衔标签 View 注入器（忠实还原原脚本「会话自定义头衔」的注入机制）
 *
 * 原脚本在昵称 TextView（userTV）的父容器里动态 addView 一个独立 TextView，
 * 按父容器类型布局在昵称旁边/上方：
 *  - LinearLayout：插入到昵称的 index 位置（昵称左侧同一行）
 *  - RelativeLayout：ABOVE + ALIGN_START（昵称上方）
 *  - ConstraintLayout：bottomToTop + startToStart（昵称上方）
 *
 * 稳定性改造：用 WeakHashMap 按昵称 View 缓存已创建的标签 View，
 * bind 复用、不重复 addView，避免原脚本每次 bind 都触发重布局的卡顿。
 * 「头衔下」与「头衔上」通过不同 key 持有各自独立的标签 View，互不干扰。
 */
object TitleTagOverlay {

    private val tagViews = WeakHashMap<TextView, HashMap<String, TextView>>()

    /** 取（或创建）昵称 TextView 对应的头衔标签 View；父容器不支持时返回 null。 */
    fun getOrCreate(nick: TextView, key: String, onStyle: (TextView) -> Unit): TextView? {
        synchronized(tagViews) {
            tagViews[nick]?.get(key)?.let { return it }
        }
        val parent = nick.parent as? ViewGroup ?: return null
        val title = TextView(nick.context).apply {
            visibility = View.GONE
            onStyle(this)
        }
        val added = when (parent) {
            is LinearLayout -> {
                val index = parent.indexOfChild(nick)
                if (index < 0) return null
                parent.addView(
                    title,
                    index,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                true
            }
            is RelativeLayout -> {
                if (nick.id == View.NO_ID) nick.id = View.generateViewId()
                parent.addView(
                    title,
                    RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        addRule(RelativeLayout.ABOVE, nick.id)
                        addRule(RelativeLayout.ALIGN_START, nick.id)
                        bottomMargin = dp(nick, 2f)
                    },
                )
                true
            }
            is ConstraintLayout -> {
                if (nick.id == View.NO_ID) nick.id = View.generateViewId()
                parent.addView(
                    title,
                    ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomToTop = nick.id
                        startToStart = nick.id
                        bottomMargin = dp(nick, 2f)
                    },
                )
                true
            }
            else -> false
        }
        if (!added) return null
        synchronized(tagViews) {
            tagViews.getOrPut(nick) { HashMap() }[key] = title
        }
        return title
    }

    /** 隐藏已创建的标签（复用前先隐藏，避免闪出旧头衔）。 */
    fun hide(nick: TextView, key: String) {
        val view = synchronized(tagViews) { tagViews[nick]?.get(key) } ?: return
        view.visibility = View.GONE
    }

    private fun dp(view: View, value: Float): Int {
        val density = view.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }
}
