package com.kevinluo.autoglm.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OverlayPolicyTest {
    @Test fun `pause visibility masks nested hides without stealing them and cannot reopen a closed run`() {
        val policy = OverlayPolicy()
        policy.onRunChanged(RunSnapshot("run", RunPhase.RUNNING))
        val outer = policy.acquireHide()
        val inner = policy.acquireHide()
        val pause = policy.acquirePauseVisibility()
        val secondPause = policy.acquirePauseVisibility()
        assertTrue(policy.snapshot.value.visible)
        assertEquals(2, policy.snapshot.value.hiddenLeaseCount)
        pause.close()
        assertTrue(policy.snapshot.value.visible)
        secondPause.close()
        assertFalse(policy.snapshot.value.visible)
        inner.close()
        assertFalse(policy.snapshot.value.visible)
        val stalePause = policy.acquirePauseVisibility()
        policy.close()
        stalePause.close()
        outer.close()
        assertFalse(policy.snapshot.value.visible)
        policy.onRunChanged(RunSnapshot("new", RunPhase.RUNNING))
        val currentHide = policy.acquireHide()
        stalePause.close()
        assertFalse(policy.snapshot.value.visible)
        currentHide.close()
        assertTrue(policy.snapshot.value.visible)
    }
    @Test fun `manual background visibility and active run foreground behavior are preserved`() {
        val policy = OverlayPolicy()
        policy.enableUser()
        assertFalse(policy.snapshot.value.visible)
        policy.onAppBackground()
        assertTrue(policy.snapshot.value.visible)
        policy.onAppForeground()
        assertFalse(policy.snapshot.value.visible)
        policy.onRunChanged(RunSnapshot("run", RunPhase.RUNNING))
        assertTrue(policy.snapshot.value.visible)
        policy.disableUser()
        assertTrue(policy.snapshot.value.visible)
    }

    @Test fun `nested hides restore only after the last lease is released`() {
        val policy = OverlayPolicy()
        policy.onRunChanged(RunSnapshot("run", RunPhase.RUNNING))
        val outer = policy.acquireHide()
        val inner = policy.acquireHide()
        assertFalse(policy.snapshot.value.visible)
        outer.close()
        assertFalse(policy.snapshot.value.visible)
        inner.close()
        assertTrue(policy.snapshot.value.visible)
        outer.close()
        assertEquals(0, policy.snapshot.value.hiddenLeaseCount)
    }

    @Test fun `old leases cannot reopen a user closed window or interfere with a new run`() {
        val policy = OverlayPolicy()
        policy.onRunChanged(RunSnapshot("old", RunPhase.RUNNING))
        val stale = policy.acquireHide()
        policy.close()
        stale.close()
        assertFalse(policy.snapshot.value.visible)
        policy.onRunChanged(RunSnapshot("old", RunPhase.FAILED))
        assertFalse(policy.snapshot.value.visible)
        policy.onRunChanged(RunSnapshot("new", RunPhase.RUNNING))
        val current = policy.acquireHide()
        stale.close()
        assertFalse(policy.snapshot.value.visible)
        current.close()
        assertTrue(policy.snapshot.value.visible)
    }

    @Test fun `completion panel remains until foreground entry and minimization changes only presentation`() {
        val policy = OverlayPolicy()
        policy.onRunChanged(RunSnapshot("run", RunPhase.RUNNING))
        policy.setMinimized(true)
        assertTrue(policy.snapshot.value.visible)
        policy.onRunChanged(RunSnapshot("run", RunPhase.COMPLETED))
        assertTrue(policy.snapshot.value.completionVisible)
        assertTrue(policy.snapshot.value.visible)
        assertTrue(policy.snapshot.value.minimized)
        policy.onAppForeground()
        assertFalse(policy.snapshot.value.visible)
        policy.onRunChanged(RunSnapshot("run", RunPhase.COMPLETED, totalInputTokens = 1))
        assertFalse(policy.snapshot.value.visible)
    }

    @Test fun `command results never report timeout truncation or absent exit status as success`() {
        assertTrue(CommandResult(0).succeeded)
        assertFalse(CommandResult(null).succeeded)
        assertFalse(CommandResult(1).succeeded)
        assertFalse(CommandResult(0, timedOut = true).succeeded)
        assertFalse(CommandResult(0, outputTruncated = true).succeeded)
    }
}
