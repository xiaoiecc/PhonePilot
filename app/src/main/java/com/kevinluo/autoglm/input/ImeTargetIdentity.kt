package com.kevinluo.autoglm.input

/** Known focused-client layouts; unknown or multi-user ambiguity remains unusable. */
internal object ImeTargetIdentity {
    private enum class Layout(val marker: String) {
        LEGACY_FOCUSED_WINDOW("mCurFocusedWindow="),
        BINDING_STATE_CLIENT("mFocusedWindowClient="),
    }

    fun focusedDisplay(dump: String): Int? {
        val matches = dump.lineSequence().flatMap { line ->
            val trimmed = line.trimStart()
            Layout.entries.filter { trimmed.startsWith(it.marker) }.map { it to trimmed }
        }.mapNotNull { (layout, line) -> displayId(line)?.let { layout to it } }.toList()
        if (matches.size != matches.distinct().size) return null
        return matches.map { it.second }.distinct().singleOrNull()
    }

    private fun displayId(line: String): Int? = Regex(
        "(?:\\bdisplayId|\\bmSelfReportedDisplayId)=(\\d+)",
    ).find(line)?.groupValues?.get(1)?.toIntOrNull()
}
