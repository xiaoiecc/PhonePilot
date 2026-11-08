package com.kevinluo.autoglm.input

/** IME surface placement is not the editor's display. Unknown/vendor formats fail closed. */
internal object ImeTargetIdentity {
    fun focusedDisplay(dump: String): Int? {
        val focused = dump.lineSequence().filter { it.trimStart().startsWith("mCurFocusedWindow=") }.toList()
        if (focused.size != 1) return null
        return Regex("client=ClientState\\{[^}]*\\bdisplayId=(\\d+)\\}").find(focused.single())
            ?.groupValues?.get(1)?.toIntOrNull()
    }
}
