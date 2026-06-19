package `fun`.walawe.memechat.data

import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole

@Deprecated("Use ChatEmbedBuilder instead. This class is used due to Naive RAG. Now using advanced RAG, so this class became not relevant")
object ChatMLBuilder {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    private const val THINK = "<think>\n"

    fun buildFull(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        forReasoning: Boolean = false,
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("${IM_START}system\n")
            sb.append(systemPrompt)
            sb.append("$IM_END\n")
        }
        for ((role, content) in messages) {
            sb.append("$IM_START$role\n")
            sb.append(content)
            sb.append("$IM_END\n")
        }
        sb.append("${IM_START}assistant\n")
        if (forReasoning) sb.append(THINK)
        return sb.toString()
    }

    fun buildTurn(
        userMessage: String,
        forReasoning: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.append("${IM_START}user\n")
        sb.append(userMessage)
        sb.append("$IM_END\n")
        sb.append("${IM_START}assistant\n")
        if (forReasoning) sb.append(THINK)
        return sb.toString()
    }

    fun buildFullFromHistory(
        systemPrompt: String,
        history: List<ChatMessage>,
        forReasoning: Boolean = false,
    ): String {
        val messages = history.map { msg ->
            when (msg.role) {
                ChatRole.User -> "user" to msg.text
                ChatRole.Assistant -> "assistant" to msg.text
                ChatRole.System -> "system" to msg.text
            }
        }
        return buildFull(systemPrompt, messages, forReasoning)
    }
}
