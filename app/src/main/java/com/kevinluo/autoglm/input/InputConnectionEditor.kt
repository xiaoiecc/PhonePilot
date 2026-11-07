package com.kevinluo.autoglm.input

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal class InputConnectionEditor(private val connection: InputConnection) : KeyboardEditor {
    override fun beginBatchEdit() = connection.beginBatchEdit()
    override fun endBatchEdit() = connection.endBatchEdit()
    override fun selectAll() = connection.performContextMenuAction(android.R.id.selectAll)
    override fun isEmpty(): Boolean? = connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.isEmpty()
    override fun commitText(text: String, cursorPosition: Int) = connection.commitText(text, cursorPosition)
}
