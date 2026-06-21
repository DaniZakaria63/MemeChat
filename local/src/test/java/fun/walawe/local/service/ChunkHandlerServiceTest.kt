package `fun`.walawe.local.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ChunkHandlerServiceTest {

    @Test
    fun `each sentence becomes its own chunk`() {
        val sentences = listOf("First sentence.", "Second one here.", "Third.")
        val counter = AtomicLong(1001)
        val chunks = buildChunks(sentences, "msg-1", "conv-1", "User", counter)

        assertEquals(3, chunks.size)
        assertEquals("First sentence.", chunks[0].text)
        assertEquals("Second one here.", chunks[1].text)
        assertEquals("Third.", chunks[2].text)
    }

    @Test
    fun `chunk sequence numbers start at 0 and increment`() {
        val sentences = listOf("Alpha.", "Beta.", "Gamma.", "Delta.")
        val chunks = buildChunks(sentences, "msg-2", "conv-2", "User", AtomicLong(1))

        assertEquals(0, chunks[0].sequence)
        assertEquals(1, chunks[1].sequence)
        assertEquals(2, chunks[2].sequence)
        assertEquals(3, chunks[3].sequence)
    }

    @Test
    fun `faissIds increment sequentially from the given counter`() {
        val sentences = listOf("One.", "Two.", "Three.")
        val chunks = buildChunks(sentences, "msg-3", "conv-3", "User", AtomicLong(500))

        assertEquals(500L, chunks[0].faissId)
        assertEquals(501L, chunks[1].faissId)
        assertEquals(502L, chunks[2].faissId)
    }

    @Test
    fun `messageId and conversationId and role are set on every chunk`() {
        val sentences = listOf("Sentence A.", "Sentence B.")
        val chunks = buildChunks(sentences, "msg-99", "conv-99", "Assistant", AtomicLong(1))

        chunks.forEach {
            assertEquals("msg-99", it.messageId)
            assertEquals("conv-99", it.conversationId)
            assertEquals("Assistant", it.role)
        }
    }

    @Test
    fun `blank sentences are filtered out`() {
        val sentences = listOf("Real content.", "", "   ", "Also real.", "")
        val chunks = buildChunks(sentences, "msg-4", "conv-4", "User", AtomicLong(1))

        assertEquals(2, chunks.size)
        assertEquals("Real content.", chunks[0].text)
        assertEquals("Also real.", chunks[1].text)
    }

    @Test
    fun `sentences exceeding maxChars are truncated`() {
        val sentence = "A".repeat(100)
        val chunks = buildChunks(listOf(sentence), "msg-5", "conv-5", "User", AtomicLong(1), maxChars = 50)

        assertEquals(50, chunks[0].text.length)
        assertEquals("A".repeat(50), chunks[0].text)
    }

    @Test
    fun `empty input produces empty list`() {
        val chunks = buildChunks(emptyList(), "msg-6", "conv-6", "User", AtomicLong(1))
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `default counter starts from non-zero`() {
        val chunks = buildChunks(listOf("Test."), "msg-7", "conv-7", "User")
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].faissId > 0)
    }

    @Test
    fun `paragraph with multiple related sentences produces one chunk per sentence`() {
        val sentences = listOf(
            "The transformer architecture introduced the attention mechanism.",
            "This allows the model to weigh the importance of different words in a sequence.",
            "It has become the foundation of modern large language models.",
        )
        val chunks = buildChunks(sentences, "msg-8", "conv-8", "User", AtomicLong(1))

        assertEquals(3, chunks.size)
        assertEquals(sentences[0], chunks[0].text)
        assertEquals(sentences[1], chunks[1].text)
        assertEquals(sentences[2], chunks[2].text)
        assertEquals(2, chunks[2].sequence)
    }

    @Test
    fun `two related short sentences remain separate chunks`() {
        val sentences = listOf("I like cats.", "Cats are fluffy.")
        val chunks = buildChunks(sentences, "msg-9", "conv-9", "User", AtomicLong(1))

        assertEquals(2, chunks.size)
        assertEquals("I like cats.", chunks[0].text)
        assertEquals("Cats are fluffy.", chunks[1].text)
        assertNotEquals(chunks[0].faissId, chunks[1].faissId)
    }

    @Test
    fun `non-related sentence does not merge with preceding context`() {
        val sentences = listOf(
            "The attention mechanism is the core innovation of transformers.",
            "I also tried a new pasta recipe yesterday and it was delicious.",
            "Multi-head attention allows the model to focus on different parts of the input.",
        )
        val chunks = buildChunks(sentences, "msg-10", "conv-10", "User", AtomicLong(1))

        assertEquals(3, chunks.size)
        assertEquals(sentences[0], chunks[0].text)
        assertEquals(sentences[1], chunks[1].text)
        assertEquals(sentences[2], chunks[2].text)
        assertEquals(0, chunks[0].sequence)
        assertEquals(1, chunks[1].sequence)
        assertEquals(2, chunks[2].sequence)
    }

    @Test
    fun `realistic message with mixed topics produces correct chunks`() {
        val sentences = listOf(
            "Hey, I was reading about transformers in deep learning.",
            "The attention mechanism is really fascinating because it allows the model to weigh different parts of the input.",
            "Also, I tried that new pasta recipe you mentioned.",
            "What do you think about using LoRA for fine-tuning?",
        )
        val chunks = buildChunks(sentences, "msg-11", "conv-11", "User", AtomicLong(1))

        assertEquals(4, chunks.size)
        assertEquals(sentences[0], chunks[0].text)
        assertEquals(sentences[1], chunks[1].text)
        assertEquals(sentences[2], chunks[2].text)
        assertEquals(sentences[3], chunks[3].text)
        assertTrue(chunks[2].text.contains("pasta"))
        assertTrue(chunks[0].text.contains("transformers"))
        assertTrue(chunks[3].text.contains("LoRA"))
    }
}
