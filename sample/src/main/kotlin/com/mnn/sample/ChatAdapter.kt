package com.mnn.sample

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying chat messages in RecyclerView
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
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            timestampText.text = timeFormat.format(Date(message.timestamp))

            // Style based on sender
            val layoutParams = messageContainer.layoutParams as LinearLayout.LayoutParams
            
            if (message.isUser) {
                // User message - right aligned, blue background
                layoutParams.gravity = Gravity.END
                messageContainer.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light)
                )
                messageText.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white)
                )
            } else {
                // AI message - left aligned, white background
                layoutParams.gravity = Gravity.START
                messageContainer.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white)
                )
                messageText.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.black)
                )
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
