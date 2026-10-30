package app.agentichydra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatMessageMetadataTest {
    @Test
    fun legacyHistoryLoadsWithoutInventedTimesOrMetrics() {
        val message = Message.fromJson(JSONObject("""{"role":"assistant","text":"A saved answer"}"""))
        assertEquals("A saved answer", message.text)
        assertEquals(0L, message.createdAt)
        assertNull(message.metrics)
        assertTrue(message.id.isNotBlank())
    }

    @Test
    fun responseAndModelMetadataSurviveHistorySerialization() {
        val message =
            Message(
                "assistant",
                "Result",
                id = "stable-response",
                createdAt = 1234,
                modelId = "custom/model",
                modelName = "Project model",
                providerName = "Custom endpoint",
                metrics = ResponseMetrics(100, 2000, true, 3234),
            )
        assertEquals(message, Message.fromJson(message.json()))
        assertEquals(50.0, message.metrics!!.tokensPerSecond, 0.001)
        val marker =
            Message(
                "model",
                "Project model",
                modelId = "custom/model",
                createdAt = 1234,
                previousModelId = "custom/original",
                previousModelName = "Original model",
                previousProviderName = "Ollama",
            )
        assertEquals(marker, Message.fromJson(marker.json()))
    }

    @Test
    fun thoughtSurvivesHistorySerialization() {
        val message = Message("assistant", "Result text", thought = "Thinking step by step...")
        val restored = Message.fromJson(message.json())
        assertEquals("Thinking step by step...", restored.thought)
        assertEquals("Result text", restored.text)
    }

    @Test
    fun extractThoughtHandlesRawAndTags() {
        val (text1, thought1) = extractThought("<think>\nMy thoughts\n</think>\nActual text", "")
        assertEquals("Actual text", text1)
        assertEquals("My thoughts", thought1)

        val (text2, thought2) = extractThought("Actual text", "Provided reasoning")
        assertEquals("Actual text", text2)
        assertEquals("Provided reasoning", thought2)

        val (text3, thought3) = extractThought("   <think>Thinking without close", "")
        assertEquals("", text3)
        assertEquals("Thinking without close", thought3)

        val (text4, thought4) = extractThought("Code with <think> tag inside", "")
        assertEquals("Code with <think> tag inside", text4)
        assertEquals("", thought4)
    }
}
