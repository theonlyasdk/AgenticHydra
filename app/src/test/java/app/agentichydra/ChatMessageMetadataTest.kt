package app.agentichydra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatMessageMetadataTest {
    @Test fun legacyHistoryLoadsWithoutInventedTimesOrMetrics() {
        val message=Message.fromJson(JSONObject("""{"role":"assistant","text":"A saved answer"}"""))
        assertEquals("A saved answer",message.text)
        assertEquals(0L,message.createdAt)
        assertNull(message.metrics)
        assertTrue(message.id.isNotBlank())
    }
    @Test fun responseAndModelMetadataSurviveHistorySerialization() {
        val message=Message("assistant","Result",id="stable-response",createdAt=1234,
            modelId="custom/model",modelName="Project model",providerName="Custom endpoint",
            metrics=ResponseMetrics(100,2000,true,3234))
        assertEquals(message,Message.fromJson(message.json()))
        assertEquals(50.0,message.metrics!!.tokensPerSecond,0.001)
        val marker=Message("model","Project model",modelId="custom/model",createdAt=1234,
            previousModelId="custom/original",previousModelName="Original model",previousProviderName="Ollama")
        assertEquals(marker,Message.fromJson(marker.json()))
    }
}
