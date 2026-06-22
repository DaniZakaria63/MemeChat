package `fun`.walawe.memechat.data

class ChatEmbedBuilder {
    companion object {
        private const val IM_START = "<|im_start|>"
        private const val IM_END = "<|im_end|>"
        private const val THINK = "<think>\n"
        private const val MEDIA_MARKER = "<__media__>\n"

        fun buildWithContext(
            systemPrompt: String,
            contextHistory: List<String>,
            currentMessage: String,
            forReasoning: Boolean = false,
            includeMediaMarker: Boolean = false,
            webResults: List<String> = emptyList(),
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
            if (webResults.isNotEmpty()) {
                sb.append("\n\nWeb search results:\n")
                for (result in webResults) {
                    sb.append("- $result\n")
                }
            }
            sb.append("$IM_END\n")
            sb.append("${IM_START}user\n")
            if (includeMediaMarker) sb.append(MEDIA_MARKER)
            sb.append(currentMessage)
            sb.append("$IM_END\n")
            sb.append("${IM_START}assistant\n")
            if (forReasoning) sb.append(THINK)

            return sb.toString()
        }
    }
}