package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticActionValidatorTest {
    @Test
    fun `selection must stay within current text`() {
        assertTrue(SemanticActionValidator.isValidSelection(0, 3, 3))
        assertFalse(SemanticActionValidator.isValidSelection(1, 4, 3))
        assertFalse(SemanticActionValidator.isValidSelection(-1, 2, 3))
        assertFalse(SemanticActionValidator.isValidSelection(3, 2, 3))
    }

    @Test
    fun `progress must be finite and inside node range`() {
        assertTrue(SemanticActionValidator.isValidProgress(0.5f, 0f, 1f))
        assertFalse(SemanticActionValidator.isValidProgress(2f, 0f, 1f))
        assertFalse(SemanticActionValidator.isValidProgress(Float.NaN, 0f, 1f))
        assertFalse(SemanticActionValidator.isValidProgress(Float.POSITIVE_INFINITY, 0f, 1f))
    }

    @Test
    fun `append is confirmed only when the final value matches`() {
        assertTrue(SemanticActionValidator.isAppendConfirmed("abc", "abc"))
        assertFalse(SemanticActionValidator.isAppendConfirmed("abc", "ab"))
    }
}
