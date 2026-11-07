package com.kevinluo.autoglm.accessibility

object SemanticActionValidator {
    fun isValidSelection(start: Int, end: Int, textLength: Int): Boolean =
        start >= 0 && end >= start && end <= textLength

    fun isValidProgress(value: Float, min: Float, max: Float): Boolean =
        value.isFinite() && value >= min && value <= max

    fun isAppendConfirmed(expected: String, actual: String): Boolean = expected == actual
}
