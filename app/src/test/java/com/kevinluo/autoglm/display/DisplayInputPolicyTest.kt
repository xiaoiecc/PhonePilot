package com.kevinluo.autoglm.display

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DisplayInputPolicyTest {
    @Test fun `background gestures always carry the private display`() {
        assertEquals(listOf("input", "-d", "19", "tap", "10", "20"),
            DisplayInputPolicy.arguments(19, true, DisplaySessionClient.TAP, intArrayOf(10, 20)).toList())
        assertEquals(listOf("input", "-d", "19", "swipe", "10", "20", "30", "40", "500"),
            DisplayInputPolicy.arguments(19, true, DisplaySessionClient.SWIPE, intArrayOf(10, 20, 30, 40, 500)).toList())
    }
    @Test fun `background rejects primary display and global keys`() {
        assertThrows(IllegalArgumentException::class.java) { DisplayInputPolicy.arguments(0, true, 1, intArrayOf(1, 2)) }
        for (key in listOf(3, 24, 25, 26, 187)) assertThrows(IllegalStateException::class.java) {
            DisplayInputPolicy.arguments(19, true, DisplaySessionClient.KEY, intArrayOf(key))
        }
        assertEquals("4", DisplayInputPolicy.arguments(19, true, DisplaySessionClient.KEY, intArrayOf(4)).last())
        assertEquals("111", DisplayInputPolicy.arguments(19, true, DisplaySessionClient.KEY, intArrayOf(111)).last())
    }
    @Test fun `invalid counts values duration and operation reject before commands`() {
        for (values in listOf(intArrayOf(1), intArrayOf(-1, 2), intArrayOf(1, 1000001))) {
            assertThrows(IllegalArgumentException::class.java) { DisplayInputPolicy.arguments(19, true, 1, values) }
        }
        assertThrows(IllegalArgumentException::class.java) { DisplayInputPolicy.arguments(19, true, 2, intArrayOf(1, 2, 3, 4, 0)) }
        assertThrows(IllegalStateException::class.java) { DisplayInputPolicy.arguments(19, true, 99, intArrayOf()) }
    }
    @Test fun `capture path cannot contain shell or filesystem syntax`() {
        val id = "a".repeat(32)
        assertEquals("/data/local/tmp/screenshot_$id.png", DisplayInputPolicy.screenshotPath(id))
        for (bad in listOf("../target", "a;id", "a\n", "a".repeat(33), "A".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) { DisplayInputPolicy.screenshotPath(bad) }
        }
    }
}
