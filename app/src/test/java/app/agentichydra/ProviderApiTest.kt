package app.agentichydra

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderApiTest {
    @Test
    fun openAiCatalogFiltersNonChatAndPreservesModelIds() {
        val catalog =
            ProviderCatalog.parse(
                JSONObject(
                    """{"data":[{"id":"nvidia/test-instruct","owned_by":"nvidia"},{"id":"nvidia/embed-v2"},{"id":""},{"id":"qwen/qwen3-32b"}]}""",
                ),
            )
        assertEquals(listOf("nvidia/test-instruct", "qwen/qwen3-32b"), catalog.map { it.id })
        assertEquals("nvidia", catalog.first().owner)
    }

    @Test
    fun geminiUsesGenerationMetadataAndNormalizesNames() {
        val catalog =
            ProviderCatalog.parse(
                JSONObject(
                    """{"models":[{"name":"models/gemini-current-flash","displayName":"Current Flash","thinking":true,"supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/embedding-current","supportedGenerationMethods":["embedContent"]},{"name":"models/audio-only","supportedGenerationMethods":["bidiGenerateContent"]}]}""",
                ),
                true,
            )
        assertEquals(1, catalog.size)
        assertEquals("gemini-current-flash", catalog.first().id)
        assertEquals("Current Flash", catalog.first().name)
        assertEquals(true, catalog.first().thinking)
    }

    @Test
    fun cacheRoundTripPreservesMetadata() {
        val original = ModelSpec("gemini-current", "Current", "Google", true)
        assertEquals(
            original,
            ProviderCatalog.parse(JSONObject().put("data", org.json.JSONArray().put(original.json()))).single(),
        )
    }

    @Test
    fun followsCatalogPaginationWithoutDroppingLaterModels() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"first"}],"has_more":true,"last_id":"first"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"second"}],"has_more":false}"""))
            val result =
                ProviderCatalog(
                    OkHttpClient(),
                ).fetch(Provider.CUSTOM, server.url("/v1").toString().trimEnd('/'), "")
            assertEquals(listOf("first", "second"), result.map { it.id })
            assertEquals("/v1/models", server.takeRequest().path)
            assertEquals("/v1/models?after=first", server.takeRequest().path)
        }
    }

    @Test
    fun refusesCredentialsOverCleartextBeforeMakingRequest() {
        assertTrue(
            runCatching {
                ProviderCatalog(OkHttpClient()).fetch(Provider.CUSTOM, "http://127.0.0.1:1/v1", "secret")
            }.isFailure,
        )
    }

    @Test
    fun reportsAuthErrorsWithoutLeakingBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive upstream body"))
            val error =
                runCatching {
                    ProviderCatalog(
                        OkHttpClient(),
                    ).fetch(Provider.CUSTOM, server.url("/v1").toString(), "")
                }.exceptionOrNull()
            assertTrue(error?.message?.contains("API key") == true)
            assertFalse(error?.message?.contains("sensitive") == true)
        }
    }

    @Test
    fun reasoningEffortUsesProviderSpecificPayloads() {
        val nim = JSONObject()
        ReasoningPolicy.apply(nim, ReasoningPolicy.profile(Provider.NIM, ModelSpec("qwen/qwen3-32b")), "high")
        assertTrue(nim.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertFalse(nim.has("reasoning_effort"))
        val gemini = JSONObject()
        ReasoningPolicy.apply(gemini, ReasoningPolicy.profile(Provider.GEMINI, ModelSpec("gemini-3.8-flash")), "medium")
        assertEquals("medium", gemini.getString("reasoning_effort"))
        val superModel = ReasoningPolicy.profile(Provider.NIM, ModelSpec("nvidia/nemotron-3-super-120b-a12b"))
        assertEquals(listOf("none", "low", "high"), superModel.levels)
    }

    @Test
    fun unknownModelsAndDefaultEffortNeverReceiveInventedParameters() {
        val payload = JSONObject()
        ReasoningPolicy.apply(payload, ReasoningPolicy.profile(Provider.NIM, ModelSpec("unknown/new-model")), "high")
        assertEquals(0, payload.length())
        ReasoningPolicy.apply(payload, ReasoningProfile(listOf("low", "high")), "auto")
        assertEquals(0, payload.length())
        assertFalse(ReasoningPolicy.profile(Provider.GEMINI, ModelSpec("gemini-3.8-flash", thinking = false)).available)
    }
}
