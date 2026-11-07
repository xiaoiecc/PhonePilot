package com.kevinluo.autoglm.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityClickMatchingTest {
    private class ListScreen {
        var windowId = 7
        var alphaPreview = "old preview"
        var betaName = "Beta"
        private fun node(id: String?, clickable: Boolean = false, scrollable: Boolean = false): AccessibilityNodeInfo {
            val node = mockk<AccessibilityNodeInfo>(relaxed = true)
            every { node.windowId } answers { windowId }
            every { node.packageName } returns "test.dynamic.list"
            every { node.className } returns if (clickable) "android.widget.Button" else "android.view.ViewGroup"
            every { node.viewIdResourceName } returns id
            every { node.text } returns null
            every { node.contentDescription } returns null
            every { node.collectionInfo } returns null
            every { node.collectionItemInfo } returns null
            every { node.childCount } returns 0
            every { node.isVisibleToUser } returns true
            every { node.isEnabled } returns true
            every { node.isClickable } returns clickable
            every { node.isScrollable } returns scrollable
            every { node.refresh() } returns true
            val actionId = when { clickable -> AccessibilityNodeInfo.ACTION_CLICK; scrollable -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; else -> null }
            val action = actionId?.let {
                mockk<AccessibilityNodeInfo.AccessibilityAction>().also { action ->
                    every { action.id } returns it
                    every { action.label } returns null
                }
            }
            every { node.actionList } returns listOfNotNull(action)
            every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) } returns true
            return node
        }
        val alpha = node("test:id/row", clickable = true)
        val beta = node("test:id/row", clickable = true)
        private val scroller = node("test:id/list", scrollable = true)
        private val root = node(null)
        private val window = mockk<AccessibilityWindowInfo>(relaxed = true)
        var rows = listOf(alpha, beta)
        val service = spyk(PhoneAccessibilityService())
        init {
            every { alpha.contentDescription } answers { "Alpha, $alphaPreview" }
            every { beta.contentDescription } answers { "$betaName, another preview" }
            every { scroller.childCount } answers { rows.size }
            every { scroller.getChild(any()) } answers { rows[firstArg()] }
            every { root.childCount } returns 1
            every { root.getChild(0) } returns scroller
            every { service.packageName } returns "com.kevinluo.autoglm"
            every { service.windows } returns listOf(window)
            every { window.root } returns root
            every { window.id } answers { windowId }
            every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { window.isActive } returns true
            every { window.isFocused } returns true
        }
        fun dirty() = service.onAccessibilityEvent(mockk<AccessibilityEvent> {
            every { packageName } returns "test.dynamic.list"
        })
        fun command(snapshot: UiSnapshot) = UiCommand.ClickMatching(snapshot.id, "n0_0", "Alpha, ", SemanticMatchMode.PREFIX)
    }

    @Test fun `explicit matching clicks reordered unique row using fresh fingerprint and returns semantic result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = ListScreen()
            val before = screen.service.observe()
            assertEquals("Alpha, old preview", before.nodes.single { it.id == "n0_0" }.label)
            screen.rows = listOf(screen.beta, screen.alpha)
            screen.alphaPreview = "new incoming preview"
            screen.dirty()
            val result = screen.service.execute(screen.command(before))
            assertTrue(result.startsWith("Action accepted by Android"), result)
            verify(exactly = 1) { screen.alpha.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) }
            verify(exactly = 0) { screen.beta.performAction(any(), any()) }
            // The old source ID was superseded, not rebound to the fresh list position.
            assertTrue(screen.service.execute(screen.command(before)).startsWith("ERROR: stale source snapshot"))
            verify(exactly = 1) { screen.alpha.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `new duplicate row fails without clicking either candidate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = ListScreen()
            val before = screen.service.observe()
            screen.betaName = "Alpha"
            screen.dirty()
            val result = screen.service.execute(screen.command(before))
            assertTrue(result.startsWith("ERROR:"), result)
            assertTrue(result.contains("2 current rows"), result)
            verify(exactly = 0) { screen.alpha.performAction(any(), any()) }
            verify(exactly = 0) { screen.beta.performAction(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `explicit invalidation and changed selected window never resolve old list source`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val invalid = ListScreen()
            val before = invalid.service.observe()
            invalid.service.invalidateObservation()
            assertTrue(invalid.service.execute(invalid.command(before)).startsWith("ERROR: stale source snapshot"))
            verify(exactly = 0) { invalid.alpha.performAction(any(), any()) }
            val moved = ListScreen()
            val source = moved.service.observe()
            moved.windowId = 8
            val result = moved.service.execute(moved.command(source))
            assertTrue(result.startsWith("ERROR:"), result)
            assertTrue(result.contains("selected window changed"), result)
            verify(exactly = 0) { moved.alpha.performAction(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }
}
