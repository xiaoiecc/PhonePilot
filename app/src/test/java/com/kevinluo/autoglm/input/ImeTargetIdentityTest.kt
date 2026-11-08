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

    @Test fun `Android 14 self reported focused client is supported`() {
        val dump = "  mCurFocusedWindow=Window{abc} client=ClientState{abc mUid=123 mPid=42 mSelfReportedDisplayId=7}"
        assertEquals(7, ImeTargetIdentity.focusedDisplay(dump))
    }

    @Test fun `Android 15 and 16 binding state focused clients are supported`() {
        val android15 = """
            mFocusedWindow()=android.os.BinderProxy@abc
            softInputMode=0x0
            mFocusedWindowClient=ClientState{abc mUid=123 mPid=42 mSelfReportedDisplayId=11}
        """.trimIndent()
        val android16 = """
            mFocusedWindow=android.os.BinderProxy@abc
            mFocusedWindowSoftInputMode=0x0
            mFocusedWindowClient=ClientState{abc mUid=123 mPid=42 mSelfReportedDisplayId=12}
        """.trimIndent()
        assertEquals(11, ImeTargetIdentity.focusedDisplay(android15))
        assertEquals(12, ImeTargetIdentity.focusedDisplay(android16))
    }

    @Test fun `same display on different known layouts is not treated as multiple targets`() {
        val dump = "mCurFocusedWindow=Window{abc} client=ClientState{abc displayId=4}\n" +
            "mFocusedWindowClient=ClientState{def mSelfReportedDisplayId=4}"
        assertEquals(4, ImeTargetIdentity.focusedDisplay(dump))
    }
}
