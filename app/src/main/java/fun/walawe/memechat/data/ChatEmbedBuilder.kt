package `fun`.walawe.memechat.data

class ChatEmbedBuilder {
    companion object {
        private const val IM_START = "<|im_start|>"
        private const val IM_END = "<|im_end|>"
        private const val THINK = "<think>\n"

        fun buildWithContext(
            systemPrompt: String,
            contextHistory: List<String>,
            currentMessage: String,
            forReasoning: Boolean = false,
        ): String {
            val sb = StringBuilder()

            sb.append("${IM_START}system\n")
            sb.append(systemPrompt)
            if (contextHistory.isNotEmpty()) {
                sb.append("\n\nRelevant context from past conversations:\n")
                for (text in contextHistory) {
                    sb.append("- $text\n")
                }
            }
            sb.append("$IM_END\n")
            sb.append("${IM_START}user\n")
            sb.append(currentMessage)
            sb.append("$IM_END\n")
            sb.append("${IM_START}assistant\n")
            if (forReasoning) sb.append(THINK)

            return sb.toString()
        }
    }
}