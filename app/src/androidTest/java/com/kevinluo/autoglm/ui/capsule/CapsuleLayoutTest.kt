package com.kevinluo.autoglm.ui.capsule

import android.text.Spanned
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import com.kevinluo.autoglm.ui.FloatingWindowService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native layout checks only: no model request, device action, permission change or application navigation. */
@RunWith(AndroidJUnit4::class)
class CapsuleLayoutTest {
    @Test fun inputGrowsForRealWrappingCapsAndShrinksWithoutReplacingText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_AutoGLM)
            val input = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)
                .findViewById<AutoGrowingInput>(R.id.task_input)
            val width = (220 * context.resources.displayMetrics.density).toInt()
            fun measure(): Int {
                input.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                input.layout(0, 0, width, input.measuredHeight)
                return input.measuredHeight
            }
            input.setText("短句")
            val oneLine = measure()
            input.setText("第一行\n第二行\n第三行")
            val threeLines = measure()
            assertTrue("actual lines must grow the field", threeLines > oneLine)
            input.setText("不含显式换行但应自动折行的中文内容。".repeat(30))
            val wrapped = measure()
            assertTrue("soft wrapping must grow the field", wrapped > threeLines)
            assertTrue("field must cap rather than grow for all rows", input.layout.lineCount > 6)
            input.setText("一行文字\n".repeat(60))
            assertEquals("overflow scrolls within the same cap", wrapped, measure())
            val editable = input.text!!
            editable.replace(0, editable.length, "正在输入")
            val composition = Any()
            editable.setSpan(composition, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
            input.setSelection(2)
            assertEquals("deletion must shrink back", oneLine, measure())
            assertSame("resize must preserve the editable", editable, input.text)
            assertEquals(2, input.selectionStart)
            assertEquals(0, editable.getSpanStart(composition))
            assertTrue(editable.getSpanFlags(composition) and Spanned.SPAN_COMPOSING != 0)
        }
    }

    @Test fun capsuleInflatesAndHeaderControlsKeepFullTouchTargets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_AutoGLM)
            val view = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)
            val density = context.resources.displayMetrics.density
            val width = (360 * density).toInt()
            val height = (560 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            listOf(R.id.btn_minimize, R.id.btn_progress_details, R.id.btn_pause, R.id.btn_stop).forEach { id ->
                val control = view.findViewById<View>(id)
                assertTrue("header control width", control.measuredWidth >= (48 * density).toInt())
                assertTrue("header control height", control.measuredHeight >= (48 * density).toInt())
            }
            assertEquals(View.GONE, view.findViewById<View>(R.id.capsule_body).visibility)
        }
    }

    @Test fun upperRightControlTogglesActualMinimizedState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val application = InstrumentationRegistry.getInstrumentation().targetContext
            FloatingWindowStateManager.initialize(application)
            FloatingWindowStateManager.reset()
            assertFalse(FloatingWindowStateManager.snapshot.value.minimized)
            val service = FloatingWindowService()
            val view = LayoutInflater.from(application).inflate(R.layout.layout_floating_window, null)
            FloatingWindowService::class.java.getDeclaredField("floatingView").apply {
                isAccessible = true
                set(service, view)
            }
            val params = android.view.WindowManager.LayoutParams()
            FloatingWindowService::class.java.getDeclaredField("layoutParams").apply {
                isAccessible = true
                set(service, params)
            }
            FloatingWindowService::class.java.getDeclaredField("isAttached").apply {
                isAccessible = true
                set(service, java.util.concurrent.atomic.AtomicBoolean(true))
            }
            val method = FloatingWindowService::class.java.getDeclaredMethod("setupButtons", View::class.java)
            method.isAccessible = true
            method.invoke(service, view)
            view.findViewById<View>(R.id.btn_progress_details).performClick()
            assertTrue(FloatingWindowStateManager.snapshot.value.minimized)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.tv_status).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.btn_minimize).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.status_indicator).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.btn_progress_details).visibility)
            FloatingWindowService::class.java.getDeclaredMethod("resizeToContent", View::class.java).apply {
                isAccessible = true
                invoke(service, view)
            }
            val expectedWidth = minOf(application.resources.displayMetrics.widthPixels -
                (24 * application.resources.displayMetrics.density).toInt(),
                (400 * application.resources.displayMetrics.density).toInt())
            assertEquals(expectedWidth, params.width)
            view.findViewById<View>(R.id.btn_progress_details).performClick()
            assertFalse(FloatingWindowStateManager.snapshot.value.minimized)
            FloatingWindowStateManager.reset()
        }
    }

    @Test fun serviceInflaterAppliesIconTintsInBothColorModes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val application = InstrumentationRegistry.getInstrumentation().targetContext
            listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { night ->
                val configuration = Configuration(application.resources.configuration).apply {
                    uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
                }
                // Match the service: no AppCompatActivity inflater factory is installed.
                val context = ContextThemeWrapper(application.createConfigurationContext(configuration), R.style.Theme_AutoGLM)
                val view = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)
                mapOf(R.id.btn_minimize to R.color.capsule_accent,
                    R.id.btn_progress_details to R.color.capsule_muted,
                    R.id.btn_open_app to R.color.capsule_muted,
                    R.id.btn_select_template to R.color.capsule_muted,
                    R.id.btn_close to R.color.capsule_muted).forEach { (id, colorId) ->
                    val actual = view.findViewById<View>(id)
                    assertTrue("service must inflate a tint-aware icon", actual is AppCompatImageButton)
                    val icon = actual as AppCompatImageButton
                    val tint = requireNotNull(androidx.core.widget.ImageViewCompat.getImageTintList(icon))
                    val expected = context.getColor(colorId)
                    assertEquals(expected, tint.defaultColor)
                    assertEquals(expected, tint.getColorForState(intArrayOf(android.R.attr.state_enabled), tint.defaultColor))
                    // VectorDrawable keeps its tint filter separately from getColorFilter().
                    // Check rendered pixels instead of requiring that implementation detail.
                    val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        icon.drawable.setBounds(0, 0, 96, 96)
                        icon.drawable.draw(android.graphics.Canvas(bitmap))
                        val pixels = IntArray(96 * 96)
                        bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                        assertTrue("rendered glyph must use its intended tint", pixels.any { pixel ->
                            android.graphics.Color.alpha(pixel) > 220 &&
                                kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(expected)) <= 3 &&
                                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(expected)) <= 3 &&
                                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(expected)) <= 3
                        })
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test fun longProgressUsesTwoRealLinesAndHeaderFitsItsTextAtLargeFont() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val application = InstrumentationRegistry.getInstrumentation().targetContext
            listOf(1f, 1.3f).forEach { scale ->
                val config = Configuration(application.resources.configuration).apply { fontScale = scale }
                val context = ContextThemeWrapper(application.createConfigurationContext(config), R.style.Theme_AutoGLM)
                val view = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)
                val header = view.findViewById<CapsuleDragLayout>(R.id.header)
                val label = view.findViewById<TextView>(R.id.tv_status)
                val density = context.resources.displayMetrics.density
                val width = (360 * density).toInt()
                fun measure(): Int {
                    header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((88 * density).toInt(), View.MeasureSpec.AT_MOST))
                    header.layout(0, 0, header.measuredWidth, header.measuredHeight)
                    return header.measuredHeight
                }
                label.text = "查找群聊"
                val shortHeight = measure()
                val original = "我正在按完整群名重新定位会话，打开后核对最新消息。".repeat(5)
                label.text = original
                val longHeight = measure()
                assertEquals("full underlying description is preserved", original, label.text.toString())
                assertEquals(2, label.maxLines)
                assertEquals("readable description wraps instead of scrolling", 2, label.layout.lineCount)
                assertFalse(label.isSelected)
                assertTrue("header grows for the measured two-line text", longHeight > shortHeight)
                assertTrue(longHeight <= (88 * density).toInt())
                assertTrue("two lines fit without clipping", label.height >= label.layout.height + label.compoundPaddingTop + label.compoundPaddingBottom)
                listOf(R.id.btn_progress_details, R.id.btn_pause, R.id.btn_stop).forEach { id ->
                    val control = view.findViewById<View>(id)
                    assertEquals((48 * density).toInt(), control.measuredHeight)
                    assertTrue(control.bottom <= longHeight)
                }
            }
        }
    }
}
