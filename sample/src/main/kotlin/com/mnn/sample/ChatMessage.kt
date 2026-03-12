package com.mnn.sample

/**
 * Represents a chat message.
 * @param imagePath  Absolute path to an attached image (user messages sent to a VLM).
 * @param thinkingText  Chain-of-thought block from the model (stripped from [text]).
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val thinkingText: String? = null
)

/**
 * Message types for different UI states
 */
sealed class MessageType {
    data class Text(val message: ChatMessage) : MessageType()
    object Loading : MessageType()
    data class Error(val error: String) : MessageType()
}
