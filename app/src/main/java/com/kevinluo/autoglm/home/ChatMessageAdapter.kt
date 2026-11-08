package com.kevinluo.autoglm.home

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.history.HistoryMessage
import com.kevinluo.autoglm.history.HistoryMessageRole

/** Renders the conversation itself, independently of per-step execution diagnostics. */
class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {
    private var messages: List<HistoryMessage> = emptyList()

    fun submitMessages(updated: List<HistoryMessage>) {
        if (messages == updated) return
        val previous = messages
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = updated.size
            override fun areItemsTheSame(old: Int, new: Int) =
                old == new && previous[old].timestamp == updated[new].timestamp &&
                    previous[old].role == updated[new].role
            override fun areContentsTheSame(old: Int, new: Int) = previous[old] == updated[new]
        })
        messages = updated.toList()
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false),
    )

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) = holder.bind(messages[position])

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val row = view as LinearLayout
        private val card = view.findViewById<MaterialCardView>(R.id.chatMessageCard)
        private val role = view.findViewById<TextView>(R.id.chatMessageRole)
        private val text = view.findViewById<TextView>(R.id.chatMessageText)
        private val imageScroll = view.findViewById<View>(R.id.chatMessageImageScroll)
        private val images = view.findViewById<LinearLayout>(R.id.chatMessageImages)

        fun bind(message: HistoryMessage) {
            val user = message.role == HistoryMessageRole.USER
            row.gravity = if (user) Gravity.END else Gravity.START
            val width = (itemView.parent as? View)?.width?.takeIf { it > 0 }
                ?: itemView.resources.displayMetrics.widthPixels
            text.maxWidth = ((width * 0.8f).toInt() -
                (28 * itemView.resources.displayMetrics.density).toInt()).coerceAtLeast(1)
            card.setCardBackgroundColor(ContextCompat.getColor(itemView.context,
                if (user) R.color.surface_variant else R.color.surface))
            role.setText(if (user) R.string.chat_you else R.string.chat_assistant)
            text.text = message.text
            text.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            if (message.text.isNotEmpty()) android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS)
            images.removeAllViews()
            imageScroll.visibility = if (message.images.isEmpty()) View.GONE else View.VISIBLE
            val density = itemView.resources.displayMetrics.density
            message.images.forEach { image ->
                val thumbnail = ImageView(itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams((200 * density).toInt(), (140 * density).toInt()).apply {
                        marginEnd = (8 * density).toInt()
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = itemView.context.getString(R.string.chat_image)
                }
                val bitmap = ChatImageDecoder.decode(image.path, 400)
                if (bitmap != null) thumbnail.setImageBitmap(bitmap) else thumbnail.visibility = View.GONE
                images.addView(thumbnail)
            }
        }
    }
}
