package com.kevinluo.autoglm.task

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunEventIdentityTest {
    @Test fun `late completion failure and minimize cannot affect another task`() {
        assertFalse(RunEventIdentity.accepts("old", "new"))
        assertFalse(RunEventIdentity.accepts("old", "new", requiresActive = true, active = true))
        assertFalse(RunEventIdentity.accepts("old", null))
    }

    @Test fun `current completion stays valid while terminal minimize is rejected`() {
        assertTrue(RunEventIdentity.accepts("current", "current"))
        assertFalse(RunEventIdentity.accepts("current", "current", requiresActive = true, active = false))
        assertTrue(RunEventIdentity.accepts("current", "current", requiresActive = true, active = true))
    }
}
