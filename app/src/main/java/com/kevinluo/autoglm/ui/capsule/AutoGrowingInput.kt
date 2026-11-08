package com.kevinluo.autoglm.ui.capsule

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/** Native layout grows for wrapped/composing text; only the bounded remainder scrolls. */
class AutoGrowingInput @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatEditText(context, attrs) {
    var onDismissKeyboard: (() -> Unit)? = null

    init {
        minLines = 1
        maxLines = 6
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val available = if (frame.height() > 0) frame.height() else resources.displayMetrics.heightPixels
        val minimum = lineHeight + compoundPaddingTop + compoundPaddingBottom
        val cap = minOf(lineHeight * 6 + compoundPaddingTop + compoundPaddingBottom,
            (available * .36f).toInt().coerceAtLeast(minimum))
        if (maxHeight != cap) maxHeight = cap
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onDismissKeyboard?.invoke()
            // Do not let the same BACK close the editor and navigate the target application.
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
