package com.kevinluo.autoglm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServiceReconnectPolicyTest {
    @Test fun `automatic reconnect stops after its bounded backoff`() {
        val policy = ServiceReconnectPolicy()
        assertEquals(250L, policy.nextDelayMs())
        assertEquals(1000L, policy.nextDelayMs())
        assertEquals(3000L, policy.nextDelayMs())
        repeat(10) { assertNull(policy.nextDelayMs()) }
    }

    @Test fun `explicit refresh allows another bounded reconnect window`() {
        val policy = ServiceReconnectPolicy()
        repeat(4) { policy.nextDelayMs() }
        policy.reset()
        assertEquals(250L, policy.nextDelayMs())
    }

    @Test fun `a stable connection renews the full retry budget for a later failure`() {
        var now = 0L
        val policy = ServiceReconnectPolicy { now }
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.connected()
        now = ServiceReconnectPolicy.STABILITY_WINDOW_MS - 1
        assertFalse(policy.resetIfStable())
        now++
        assertTrue(policy.resetIfStable())
        policy.disconnected()
        assertEquals(250L, policy.nextDelayMs())
        assertEquals(1000L, policy.nextDelayMs())
        assertEquals(3000L, policy.nextDelayMs())
        assertNull(policy.nextDelayMs())
    }

    @Test fun `brief connections and stale stability callbacks cannot renew exhausted retries`() {
        var now = 0L
        val policy = ServiceReconnectPolicy { now }
        repeat(3) {
            policy.nextDelayMs()
            policy.connected()
            now += 100
            assertFalse(policy.resetIfStable())
            policy.disconnected()
        }
        now += ServiceReconnectPolicy.STABILITY_WINDOW_MS
        assertFalse(policy.resetIfStable())
        assertNull(policy.nextDelayMs())
    }
}
