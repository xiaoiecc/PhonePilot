package com.kevinluo.autoglm.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kevinluo.autoglm.computer.runtime.ToolCommandDecoder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityDisplayRoutingTest {
    private class Page(val title: String, val windowId: Int) {
        val button = mockk<AccessibilityNodeInfo>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val window = mockk<AccessibilityWindowInfo>(relaxed = true)
        init {
            listOf(root, button).forEach { node ->
                every { node.packageName } returns "test.display.app"
                every { node.windowId } returns windowId
                every { node.text } returns null
                every { node.contentDescription } returns null
                every { node.viewIdResourceName } returns null
                every { node.collectionInfo } returns null
                every { node.collectionItemInfo } returns null
                every { node.isVisibleToUser } returns true
                every { node.isEnabled } returns true
                every { node.refresh() } returns true
                every { node.actionList } returns emptyList()
            }
            every { root.childCount } returns 1
            every { root.getChild(0) } returns button
            every { button.childCount } returns 0
            every { button.text } returns title
            every { button.className } returns "android.widget.Button"
            every { button.isClickable } returns true
            every { button.actionList } returns listOf(mockk<AccessibilityNodeInfo.AccessibilityAction> {
                every { id } returns AccessibilityNodeInfo.ACTION_CLICK
                every { label } returns null
            })
            every { button.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) } returns true
            every { window.root } returns root
            every { window.id } returns windowId
            every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { window.isActive } returns true
            every { window.isFocused } returns true
        }
    }

    private class Displays {
        val primary = Page("Primary private chat", 7)
        val background = Page("Background target", 9)
        val service = spyk(PhoneAccessibilityService())
        var backgroundReadable = true
        init {
            every { service.packageName } returns "com.kevinluo.autoglm"
            // This seam models the OS per-display window result. Main-screen convenience
            // getters deliberately fail: no background read/action may consult them.
            every { service.windows } throws IllegalStateException("Unexpected default display read")
            every { service.rootInActiveWindow } throws IllegalStateException("Unexpected active root fallback")
            every { service.windowsForDisplay(any()) } answers {
                when (firstArg<Int>()) {
                    0 -> listOf(primary.window)
                    2 -> if (backgroundReadable) listOf(background.window) else emptyList()
                    else -> emptyList()
                }
            }
        }
    }

    @Test fun `background observation action and search stay on the selected display`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val displays = Displays()
            displays.service.selectDisplay(2)
            val observed = displays.service.observe()
            assertTrue(observed.text.contains("Background target"))
            assertFalse(observed.text.contains("Primary private chat"))
            val search = displays.service.execute(UiCommand.Find("Primary private chat", null, null, false))
            assertTrue(search.contains("No matching nodes"), search)
            val fresh = displays.service.observe()
            val result = displays.service.execute(UiCommand.Target(fresh.id, "n0", NodeAction.Simple.CLICK))
            assertTrue(result.startsWith("Action accepted by Android"), result)
            verify(exactly = 1) { displays.background.button.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) }
            verify(exactly = 0) { displays.primary.button.performAction(any(), any()) }
            verify(exactly = 0) { displays.service.windows }
            verify(exactly = 0) { displays.service.rootInActiveWindow }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `missing background windows never falls back to primary tree or old targets`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val displays = Displays()
            displays.service.selectDisplay(2)
            val observed = displays.service.observe()
            displays.backgroundReadable = false
            val result = displays.service.execute(UiCommand.Target(observed.id, "n0", NodeAction.Simple.CLICK))
            assertTrue(result.startsWith("ERROR:"), result)
            val unavailable = displays.service.observe()
            assertTrue(unavailable.nodes.isEmpty())
            assertTrue(unavailable.text.contains("unavailable on the selected display"))
            assertFalse(unavailable.text.contains("Primary private chat"))
            verify(exactly = 0) { displays.primary.button.performAction(any(), any()) }
            verify(exactly = 0) { displays.background.button.performAction(any(), any()) }
            verify(exactly = 0) { displays.service.windows }
            verify(exactly = 0) { displays.service.rootInActiveWindow }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `selecting another display or renewing the same display invalidates old node authority`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val displays = Displays()
            val primary = displays.service.observe()
            displays.service.selectDisplay(2)
            assertTrue(displays.service.execute(UiCommand.Target(primary.id, "n0", NodeAction.Simple.CLICK))
                .startsWith("ERROR: stale snapshot"))
            val background = displays.service.observe()
            displays.service.selectDisplay(2)
            assertTrue(displays.service.execute(UiCommand.Target(background.id, "n0", NodeAction.Simple.CLICK))
                .startsWith("ERROR: stale snapshot"))
            verify(exactly = 0) { displays.primary.button.performAction(any(), any()) }
            verify(exactly = 0) { displays.background.button.performAction(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `four explicit direction actions retain their Android IDs without labels or coordinate fallbacks`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val directions = listOf(
                Triple("scroll_up", NodeAction.Simple.SCROLL_UP, android.R.id.accessibilityActionScrollUp),
                Triple("scroll_down", NodeAction.Simple.SCROLL_DOWN, android.R.id.accessibilityActionScrollDown),
                Triple("scroll_left", NodeAction.Simple.SCROLL_LEFT, android.R.id.accessibilityActionScrollLeft),
                Triple("scroll_right", NodeAction.Simple.SCROLL_RIGHT, android.R.id.accessibilityActionScrollRight),
            )
            for ((operation, expectedAction, androidAction) in directions) {
                val displays = Displays()
                val node = displays.background.button
                every { node.isScrollable } returns true
                every { node.actionList } returns listOf(mockk<AccessibilityNodeInfo.AccessibilityAction> {
                    every { id } returns androidAction
                    every { label } returns null
                })
                every { node.performAction(androidAction, any()) } returns true
                displays.service.selectDisplay(2)
                val observed = displays.service.observe()
                assertEquals(operation, observed.nodes.single().actions.single().id)
                val decoded = ToolCommandDecoder.ui(buildJsonObject {
                    put("operation", operation); put("snapshot_id", observed.id); put("node_id", "n0")
                    put("commentary", "Scroll the current list.")
                })
                assertEquals(UiCommand.Target(observed.id, "n0", expectedAction), decoded)
                val result = displays.service.execute(decoded)
                assertTrue(result.startsWith("Action accepted by Android"), result)
                verify(exactly = 1) { node.performAction(androidAction, any()) }
                verify(exactly = 0) { displays.primary.button.performAction(any(), any()) }
            }
        } finally { Dispatchers.resetMain() }
    }
}
