package com.mnn.sample

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying chat messages in RecyclerView.
 * Supports image thumbnails (VLM user messages) and collapsible thinking blocks.
 */
class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        private val messageImage: ImageView     = itemView.findViewById(R.id.messageImage)
        private val messageText: TextView       = itemView.findViewById(R.id.messageText)
        private val thinkingLabel: TextView     = itemView.findViewById(R.id.thinkingLabel)
        private val thinkingText: TextView      = itemView.findViewById(R.id.thinkingText)
        private val timestampText: TextView     = itemView.findViewById(R.id.timestampText)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            timestampText.text = timeFormat.format(Date(message.timestamp))

            // ---- Image thumbnail ----
            val imgPath = message.imagePath
            if (imgPath != null && File(imgPath).exists()) {
                val bmp = BitmapFactory.decodeFile(imgPath)
                if (bmp != null) {
                    messageImage.setImageBitmap(bmp)
                    messageImage.visibility = View.VISIBLE
                } else {
                    messageImage.visibility = View.GONE
                }
            } else {
                messageImage.visibility = View.GONE
            }

            // ---- Thinking disclosure (tap-to-collapse) ----
            val thought = message.thinkingText
            if (!thought.isNullOrBlank()) {
                thinkingLabel.text = "🧠 Hide reasoning"
                thinkingText.text = thought
                // Start EXPANDED so content is immediately visible
                thinkingText.visibility = View.VISIBLE
                thinkingLabel.visibility = View.VISIBLE
                thinkingLabel.setOnClickListener {
                    val expanded = thinkingText.visibility == View.VISIBLE
                    thinkingText.visibility = if (expanded) View.GONE else View.VISIBLE
                    thinkingLabel.text = if (expanded) "🧠 Show reasoning" else "🧠 Hide reasoning"
                }
            } else {
                thinkingLabel.visibility = View.GONE
                thinkingText.visibility = View.GONE
                thinkingLabel.text = "🧠 Show reasoning"
                thinkingLabel.setOnClickListener(null)
            }

            // ---- Bubble alignment + colour ----
            val layoutParams = messageContainer.layoutParams as LinearLayout.LayoutParams
            if (message.isUser) {
                layoutParams.gravity = Gravity.END
                messageContainer.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light))
                messageText.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white))
            } else {
                layoutParams.gravity = Gravity.START
                messageContainer.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white))
                messageText.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.black))
            }
            messageContainer.layoutParams = layoutParams
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
