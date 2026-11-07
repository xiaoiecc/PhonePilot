package com.kevinluo.autoglm.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.history.ConversationHistory
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.history.ConversationSummary
import com.kevinluo.autoglm.task.TaskExecutionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Main-chat session switcher. Selecting a conversation restores messages, not device actions. */
class ChatSessionPicker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val history: HistoryManager,
    private val open: suspend (String) -> Boolean,
) {
    private val dialog = BottomSheetDialog(context)
    private var observer: Job? = null
    private var opening: Job? = null

    fun show() {
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        val heading = TextView(context).apply {
            setText(R.string.chat_sessions)
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val empty = TextView(context).apply {
            setText(R.string.chat_sessions_empty)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        val list = ListView(context).apply { divider = null }
        val entries = mutableListOf<ConversationSummary>()
        val adapter = object : ArrayAdapter<ConversationSummary>(context, android.R.layout.simple_list_item_2, android.R.id.text1, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                val item = getItem(position) ?: return row
                row.findViewById<TextView>(android.R.id.text1).apply {
                    text = if (item.conversationId == TaskExecutionManager.taskState.value.conversationId)
                        context.getString(R.string.chat_session_current, item.title) else item.title
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                row.findViewById<TextView>(android.R.id.text2).apply {
                    val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.lastActivityTime))
                    text = "$date  ${item.messages.lastOrNull()?.text ?: item.completionMessage.orEmpty()}"
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                return row
            }
        }
        list.adapter = adapter
        content.addView(heading)
        content.addView(empty)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(content)
        content.layoutParams = content.layoutParams.apply {
            height = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            list.isEnabled = false
            opening = scope.launch {
                try {
                    if (open(selected.conversationId)) {
                        if (selected.previousResponseId == null && selected.endTime != null) Toast.makeText(context, R.string.chat_session_text_recovery, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    } else Toast.makeText(context, R.string.chat_session_open_failed, Toast.LENGTH_LONG).show()
                } finally { list.isEnabled = true }
            }
        }
        dialog.setOnDismissListener { observer?.cancel(); opening?.cancel() }
        dialog.show()
        observer = scope.launch {
            combine(history.historyList, TaskExecutionManager.messages, TaskExecutionManager.taskState) { tasks, messages, state ->
                val summaries = ConversationHistory.summaries(tasks)
                val currentId = state.conversationId
                if (currentId != null && messages.isNotEmpty() && summaries.none { it.conversationId == currentId }) {
                    listOf(ConversationSummary(currentId, messages.first().text, messages.first().timestamp, messages.last().timestamp,
                        0, 0, false, null, null, messages.toList(), null)) + summaries
                } else summaries
            }.collect { conversations ->
                adapter.setNotifyOnChange(false)
                adapter.clear()
                adapter.addAll(conversations)
                adapter.notifyDataSetChanged()
                empty.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
            }
        }
    }

    fun dismiss() { observer?.cancel(); opening?.cancel(); dialog.dismiss() }
}
