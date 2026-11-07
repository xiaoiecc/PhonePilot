package com.kevinluo.autoglm.device

import com.kevinluo.autoglm.IUserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceCommandBoundaryTest {
    @Test fun `model package syntax is rejected before any privileged command`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        val executor = DeviceExecutor(service)
        listOf("com.example; input keyevent 3 #", "com.example\ninput keyevent 3", "com.example$(id)", "-p com.example").forEach {
            assertFalse(executor.launchApp(it).succeeded)
        }
        verify(exactly = 0) { service.executeArgs(any(), any(), any()) }
    }

    @Test fun `launch resolves and starts exact argument arrays without a shell`() = runBlocking<Unit> {
        val commands = mutableListOf<List<String>>()
        val service = mockk<IUserService>()
        every { service.executeArgs(any(), any(), any()) } answers {
            val argv = firstArg<Array<String>>().toList()
            commands += argv
            CommandResultParcel(0, if (argv.first() == "pm") "com.example/.MainActivity\n" else "Starting", "", false, false)
        }
        assertTrue(DeviceExecutor(service).launchApp("com.example").succeeded)
        assertEquals(listOf("am", "start", "-n", "com.example/.MainActivity"), commands.last())
        assertTrue(commands.none { it.first() == "sh" })
        assertEquals("com.example", commands.first().last())
    }

    @Test fun `unexpected launcher component cannot reach am start`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        every { service.executeArgs(any(), any(), any()) } returns CommandResultParcel(0,
            "com.example/.Main; input keyevent 3\n", "", false, false)
        assertFalse(DeviceExecutor(service).launchApp("com.example").succeeded)
        verify(exactly = 1) { service.executeArgs(any(), any(), any()) }
    }

    @Test fun `a failed first tap does not replay or execute the second tap`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        every { service.executeArgs(any(), any(), any()) } returns CommandResultParcel(1, "", "denied", false, false)
        assertFalse(DeviceExecutor(service).doubleTap(10, 20).succeeded)
        verify(exactly = 1) { service.executeArgs(any(), any(), any()) }
    }

    @Test fun `transport preserves status timeout and truncation separately from output words`() = runBlocking<Unit> {
        val service = mockk<IUserService>()
        val client = DeviceCommandClient(service)
        every { service.executeArgs(any(), any(), any()) } returnsMany listOf(
            CommandResultParcel(0, "Error permission denied are document text", "", false, false),
            CommandResultParcel(0, "", "", true, false), CommandResultParcel(0, "partial", "", false, true),
            CommandResultParcel(null, "", "disconnected", false, false),
        )
        assertTrue(client.executeArgs(listOf("echo", "text")).succeeded)
        assertFalse(client.executeArgs(listOf("echo", "text")).succeeded)
        assertFalse(client.executeArgs(listOf("echo", "text")).succeeded)
        assertFalse(client.executeArgs(listOf("echo", "text")).succeeded)
    }
}
