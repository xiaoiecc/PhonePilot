package com.kevinluo.autoglm.display

import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.device.CommandResultParcel
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.device.DisplayResultParcel
import com.kevinluo.autoglm.util.Point
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceDisplayRoutingTest {
    private fun receipt(generation: Long = 7, background: Boolean = true, success: Boolean = true) =
        DisplayResultParcel(success, "", "run-a", generation, if (background) 19 else 0, 42, "com.example", 720, 1480, 320, background)

    @Test fun `attached touch and curved swipe never execute physical sendevent`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        every { service.attachDisplay("run-a") } returns receipt()
        every { service.inputDisplay("run-a", 7, any(), any()) } returns CommandResultParcel(0, "", "", false, false)
        val displays = DisplaySessionClient(service)
        displays.attach("run-a")
        val device = DeviceExecutor(service, displays)
        assertTrue(device.tap(10, 20).succeeded)
        assertTrue(device.swipe(listOf(Point(1, 2), Point(10, 20), Point(30, 40)), 500).succeeded)
        verify(exactly = 1) { service.inputDisplay("run-a", 7, DisplaySessionClient.TAP, match { it.contentEquals(intArrayOf(10, 20)) }) }
        verify(exactly = 1) { service.inputDisplay("run-a", 7, DisplaySessionClient.SWIPE, match { it.contentEquals(intArrayOf(1, 2, 30, 40, 500)) }) }
        verify(exactly = 0) { service.executeArgs(any(), any(), any()) }
    }
    @Test fun `failed display input remains a failure with no main display fallback`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        every { service.attachDisplay("run-a") } returns receipt()
        every { service.inputDisplay(any(), any(), any(), any()) } returns CommandResultParcel(1, "", "denied", false, false)
        val displays = DisplaySessionClient(service)
        displays.attach("run-a")
        assertFalse(DeviceExecutor(service, displays).doubleTap(10, 20).succeeded)
        verify(exactly = 1) { service.inputDisplay(any(), any(), any(), any()) }
        verify(exactly = 0) { service.executeArgs(any(), any(), any()) }
    }
    @Test fun `old receipt cannot replace newer display route`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        every { service.attachDisplay("run-a") } returns receipt()
        every { service.switchDisplay("run-a", 7, false) } returns receipt(8, false)
        every { service.inspectDisplay("run-a", 7) } returns receipt(7, true)
        val displays = DisplaySessionClient(service)
        displays.attach("run-a"); displays.switchMode("run-a", 7, false); displays.refresh("run-a", 7)
        assertEquals(8L, displays.state.generation)
        assertFalse(displays.state.background)
    }
}
