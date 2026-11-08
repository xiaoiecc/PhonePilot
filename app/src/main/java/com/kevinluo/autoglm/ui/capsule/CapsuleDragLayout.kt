package com.kevinluo.autoglm.ui.capsule

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/** Intercept only a real drag; taps, ripple feedback and long presses stay with header buttons. */
class CapsuleDragLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (88 * resources.displayMetrics.density).toInt()
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val supplied = MeasureSpec.getSize(heightMeasureSpec)
        val bounded = when (mode) {
            MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(minOf(supplied, cap), MeasureSpec.EXACTLY)
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(minOf(supplied, cap), MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, bounded)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                onDragStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) dragging = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) onDrag?.invoke(event.rawX - downX, event.rawY - downY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd?.invoke() else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
