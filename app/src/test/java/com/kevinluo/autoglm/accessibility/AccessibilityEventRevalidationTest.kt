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
class AccessibilityEventRevalidationTest {
    private class Screen {
        var label = "Skip my free trial"
        var windowId = 7
        val button = mockk<AccessibilityNodeInfo>(relaxed = true)
        private val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        private val window = mockk<AccessibilityWindowInfo>(relaxed = true)
        val service = spyk(PhoneAccessibilityService())
        init {
            every { service.packageName } returns "com.kevinluo.autoglm"
            every { service.windows } returns listOf(window)
            every { window.root } returns root
            every { window.id } answers { windowId }
            every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { window.isActive } returns true
            every { window.isFocused } returns true
            every { root.packageName } returns "com.duolingo"
            every { root.text } returns null
            every { root.contentDescription } returns null
            every { root.collectionInfo } returns null
            every { root.collectionItemInfo } returns null
            every { root.windowId } answers { windowId }
            every { root.childCount } returns 1
            every { root.getChild(0) } returns button
            every { root.isVisibleToUser } returns true
            every { root.isEnabled } returns true
            every { root.actionList } returns emptyList()
            every { button.packageName } returns "com.duolingo"
            every { button.className } returns "android.widget.Button"
            every { button.windowId } answers { windowId }
            every { button.text } answers { label }
            every { button.contentDescription } returns null
            every { button.collectionInfo } returns null
            every { button.collectionItemInfo } returns null
            every { button.viewIdResourceName } returns null
            every { button.isVisibleToUser } returns true
            every { button.isEnabled } returns true
            every { button.isClickable } returns true
            every { button.refresh() } returns true
            every { button.actionList } returns listOf(mockk<AccessibilityNodeInfo.AccessibilityAction> {
                every { id } returns AccessibilityNodeInfo.ACTION_CLICK
                every { label } returns null
            })
            every { button.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) } returns true
        }
        fun event() = service.onAccessibilityEvent(mockk<AccessibilityEvent> {
            every { packageName } returns "com.android.systemui"
        })
    }

    @Test fun `observe then unrelated platform events still permits exact unchanged target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen()
            val observed = screen.service.observe()
            assertEquals("Skip my free trial", observed.nodes.single().label)
            repeat(20) { screen.event() }
            val result = screen.service.execute(UiCommand.Target(observed.id, "n0", NodeAction.Simple.CLICK))
            assertTrue(result.startsWith("Action accepted by Android"), result)
            verify(exactly = 1) { screen.button.performAction(AccessibilityNodeInfo.ACTION_CLICK, any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `changed target after event is rejected without device action`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen()
            val observed = screen.service.observe()
            screen.label = "Purchase subscription"
            screen.event()
            val result = screen.service.execute(UiCommand.Target(observed.id, "n0", NodeAction.Simple.CLICK))
            assertTrue(result.startsWith("ERROR: observed UI changed"), result)
            verify(exactly = 0) { screen.button.performAction(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `different foreground window is rejected even when event callback has not arrived`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen()
            val observed = screen.service.observe()
            screen.windowId = 8
            val result = screen.service.execute(UiCommand.Target(observed.id, "n0", NodeAction.Simple.CLICK))
            assertTrue(result.startsWith("ERROR: active window changed"), result)
            verify(exactly = 0) { screen.button.performAction(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `explicit invalidation cannot be renewed by unchanged tree`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen()
            val observed = screen.service.observe()
            screen.service.invalidateObservation()
            screen.event()
            val result = screen.service.execute(UiCommand.Target(observed.id, "n0", NodeAction.Simple.CLICK))
            assertTrue(result.startsWith("ERROR: stale snapshot"), result)
            verify(exactly = 0) { screen.button.performAction(any(), any()) }
        } finally { Dispatchers.resetMain() }
    }
}
