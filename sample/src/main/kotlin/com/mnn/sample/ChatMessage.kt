package com.mnn.sample

/**
 * Represents a chat message
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Message types for different UI states
 */
sealed class MessageType {
    data class Text(val message: ChatMessage) : MessageType()
    object Loading : MessageType()
    data class Error(val error: String) : MessageType()
}
