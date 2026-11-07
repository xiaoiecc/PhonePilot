package com.kevinluo.autoglm

import android.content.Context
import android.os.IBinder
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.task.TaskExecutionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ComponentServiceLifecycleTest {
    private fun component(service: IUserService, agent: PhoneAgent): ComponentManager {
        val constructor = ComponentManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        return constructor.newInstance(mockk<Context>()).also { manager ->
            ComponentManager::class.java.getDeclaredField("userService").apply { isAccessible = true }.set(manager, service)
            ComponentManager::class.java.getDeclaredField("phoneAgentInternal").apply { isAccessible = true }.set(manager, agent)
        }
    }

    @Test fun `duplicate callbacks for one binder preserve the active agent`() {
        val binder = mockk<IBinder>()
        val service = mockk<IUserService>()
        val secondProxy = mockk<IUserService>()
        every { service.asBinder() } returns binder
        every { secondProxy.asBinder() } returns binder
        val agent = mockk<PhoneAgent>(relaxed = true)
        val manager = component(service, agent)
        manager.onServiceConnected(secondProxy)
        assertSame(agent, manager.phoneAgent)
        verify(exactly = 0) { agent.cancelNetwork() }
    }

    @Test fun `a real disconnect cancels old operations and clears dependent components`() {
        val service = mockk<IUserService>()
        val agent = mockk<PhoneAgent>(relaxed = true)
        val manager = component(service, agent)
        mockkObject(TaskExecutionManager)
        try {
            every { TaskExecutionManager.onRunnerUnavailable() } returns Unit
            manager.onServiceDisconnected()
            verify(exactly = 1) { TaskExecutionManager.onRunnerUnavailable() }
            assertNull(manager.phoneAgent)
            assertNull(manager.screenshotService)
            assertNull(manager.deviceExecutor)
        } finally {
            unmockkObject(TaskExecutionManager)
        }
    }
}
