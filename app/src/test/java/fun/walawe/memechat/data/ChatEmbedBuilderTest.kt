package `fun`.walawe.memechat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class ChatEmbedBuilderTest {

    private val systemPrompt = "You are a helpful assistant."
    private val sampleContext = listOf(
        "The user previously asked about Rust ownership.",
        "They were interested in borrowing and lifetimes.",
    )
    private val userMessage = "Can you explain what a trait is?"

    @Test
    fun `builds minimal prompt with system and user message`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = emptyList(),
            currentMessage = userMessage,
        )

        assertTrue(result.startsWith("<|im_start|>system\n"))
        assertTrue(result.contains(systemPrompt))
        assertTrue(result.contains("<|im_start|>user\n"))
        assertTrue(result.contains(userMessage))
        assertTrue(result.endsWith("<|im_start|>assistant\n"))
        assertFalse(result.contains("<think>"))
        assertFalse(result.contains("Relevant context"))
    }

    @Test
    fun `builds prompt with context history injected into system section`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = sampleContext,
            currentMessage = userMessage,
        )

        assertTrue(result.startsWith("<|im_start|>system\n"))
        assertTrue(result.contains("Relevant context from past conversations"))
        for (chunk in sampleContext) {
            assertTrue("Expected context text in result: $chunk", result.contains(chunk))
        }
        assertTrue(result.contains("<|im_start|>user\n"))
        assertTrue(result.contains(userMessage))
    }

    @Test
    fun `includes think token when forReasoning is true`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = emptyList(),
            currentMessage = userMessage,
            forReasoning = true,
        )

        assertTrue(result.endsWith("<|im_start|>assistant\n<think>\n"))
        assertTrue(result.contains("<think>"))
    }

    @Test
    fun `omits think token when forReasoning is false`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = emptyList(),
            currentMessage = userMessage,
            forReasoning = false,
        )

        assertFalse(result.contains("<think>"))
        assertTrue(result.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `handles empty system prompt`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = "",
            contextHistory = listOf("Some context."),
            currentMessage = "Hello",
        )

        assertTrue(result.startsWith("<|im_start|>system\n"))
        assertTrue(result.contains("Some context."))
    }

    @Test
    fun `handles empty user message`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = sampleContext,
            currentMessage = "",
        )

        assertTrue(result.contains("<|im_start|>user\n<|im_end|>"))
    }

    @Test
    fun `injects all context items as bullet points`() {
        val contexts = listOf("First.", "Second.", "Third.")
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = contexts,
            currentMessage = userMessage,
        )

        for (text in contexts) {
            assertTrue("Expected bullet point: $text", result.contains("- $text"))
        }
    }

    @Test
    fun `preserves special characters in context text`() {
        val context = listOf("Line 1\nLine 2", "Price: $100 (50% off)", "Use <tag> quotes")
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = systemPrompt,
            contextHistory = context,
            currentMessage = userMessage,
        )

        for (chunk in context) {
            assertTrue("Expected preserved text: $chunk", result.contains(chunk))
        }
    }

    @Test
    fun `produces valid ChatML structure with all sections`() {
        val result = ChatEmbedBuilder.buildWithContext(
            systemPrompt = "System prompt.",
            contextHistory = listOf("Ctx."),
            currentMessage = "Msg.",
            forReasoning = true,
        )

        // Verify overall structure: system → user → assistant
        val systemIdx = result.indexOf("<|im_start|>system\n")
        val userIdx = result.indexOf("<|im_start|>user\n")
        val assistantIdx = result.indexOf("<|im_start|>assistant\n")
        val thinkIdx = result.indexOf("<think>\n")

        assertTrue("system must precede user", systemIdx < userIdx)
        assertTrue("user must precede assistant", userIdx < assistantIdx)
        assertTrue("assistant must precede think", assistantIdx < thinkIdx)
        assertTrue(result.endsWith("<|im_start|>assistant\n<think>\n"))

        // system and user sections are closed; assistant has no closing tag
        val endCount = result.split("<|im_end|>").size - 1
        assertEquals(2, endCount)
    }
}
