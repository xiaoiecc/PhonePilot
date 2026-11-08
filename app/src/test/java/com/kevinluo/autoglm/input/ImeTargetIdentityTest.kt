package com.kevinluo.autoglm.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImeTargetIdentityTest {
    @Test fun `IME fallback surface never proves editor display`() {
        assertNull(ImeTargetIdentity.focusedDisplay("mCurTokenDisplayId=4\nmCurClient=ClientState{abc displayId=4}"))
        assertEquals(4, ImeTargetIdentity.focusedDisplay("mCurTokenDisplayId=0\n  mCurFocusedWindow=Window{abc} client=ClientState{abc uid=123 pid=42 displayId=4}"))
    }
    @Test fun `unknown null duplicate and malformed targets are rejected`() {
        assertNull(ImeTargetIdentity.focusedDisplay("mCurFocusedWindow=null"))
        val valid = "mCurFocusedWindow=Window{abc} client=ClientState{abc displayId=2}"
        assertNull(ImeTargetIdentity.focusedDisplay("$valid\n$valid"))
        assertNull(ImeTargetIdentity.focusedDisplay("mCurFocusedWindow=Window{abc} client=ClientState{abc displayId=-1}"))
    }
}
