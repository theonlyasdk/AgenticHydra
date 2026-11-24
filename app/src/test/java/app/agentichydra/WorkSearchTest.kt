package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkSearchTest {
    @Test
    fun filtersRespectDomainBoundariesAndRejectInjectedSyntax() {
        val options = SearchOptions(includeDomains = listOf("android.com"), excludeDomains = listOf("ads.android.com"))
        assertTrue(options.accepts("developer.android.com"))
        assertFalse(options.accepts("android.com.evil.example"))
        assertFalse(options.accepts("ads.android.com"))
        assertTrue(options.query("compose").contains("site:android.com"))
        assertTrue(runCatching { SearchOptions(includeDomains = listOf("example.com OR cats")) }.isFailure)
        assertTrue(runCatching { SearchOptions(maxResults = 200) }.isFailure)
    }

    @Test
    fun searchParsersUnwrapLinksRemoveTrackersAndDeduplicate() {
        val html =
            """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=""" +
                """https%3A%2F%2Fdeveloper.android.com%2Fguide%3Futm_source%3Dsearch">Android guide</a>""" +
                """<div class="result__snippet">Official docs</div></div>""" +
                """<div class="result"><a class="result__a" href="https://developer.android.com/guide">""" +
                """Duplicate</a></div>"""
        val hits = parseSearchHits(html)
        assertEquals(1, hits.size)
        assertEquals("https://developer.android.com/guide", hits.single().url)
        val rss =
            parseSearchRss(
                """<rss><channel><item><title>Guide</title><link>https://example.com/guide</link><description><![CDATA[<b>Useful</b> source]]></description></item></channel></rss>""",
            )
        assertEquals("Useful source", rss.single().snippet)
        assertNull(canonicalSearchUrl("javascript:alert(1)"))
    }

    @Test
    fun cacheExpiresAndToolBudgetPreservesValidJson() {
        var now = 0L
        val cache = WebSearchCache { now }
        val result =
            JSONObject().put("query", "test").put(
                "results",
                JSONArray().apply {
                    repeat(10) { put(JSONObject().put("snippet", "s".repeat(500))) }
                },
            )
        cache.put("key", result)
        assertTrue(cache.get("key")!!.getBoolean("cached"))
        now = 300_001
        assertNull(cache.get("key"))
        val json = boundedSearchJson(result, 1500)
        assertTrue(json.length <= 1500)
        val bounded = JSONObject(json)
        assertTrue(bounded.getBoolean("truncated"))
        assertTrue(bounded.getJSONArray("results").length() < 10)
    }

    @Test
    fun batchBudgetDropsTheTailOfTheLargestEncodedGroup() {
        val longRow = JSONObject().put("snippet", "\"\n\t".repeat(500))
        val first = JSONArray().put(JSONObject().put("snippet", "first")).put(longRow)
        val second = JSONArray().put(JSONObject().put("snippet", "second").put("url", "https://example.org"))
        val result = JSONObject().put(
            "searches",
            JSONArray().put(JSONObject().put("results", first)).put(JSONObject().put("results", second)),
        )

        val bounded = boundedSearchJson(result, 220)

        assertTrue(bounded.length <= 220)
        assertTrue(JSONObject(bounded).getBoolean("truncated"))
        assertEquals("first", first.getJSONObject(0).getString("snippet"))
        assertEquals(1, first.length())
        assertEquals(1, second.length())
    }

    @Test
    fun pageBudgetTracksEscapingAndKeepsPaginationAtTheLastCharacter() {
        val offset = 999
        val text = "</\\\"\r\n\t\u0001".repeat(1200)
        val result = JSONObject()
            .put("text", text)
            .put("links", JSONArray().put("https://example.org/guide"))
            .put("next_offset", JSONObject.NULL)

        val bounded = boundedFetchedPage(result, offset)
        val page = JSONObject(bounded)
        val body = page.getString("text")
        assertTrue(bounded.length <= 15500)
        assertTrue(body.isNotEmpty() && body.length < text.length)
        assertTrue(text.startsWith(body))
        assertEquals(offset + body.length, page.getInt("next_offset"))
        assertEquals(0, page.getJSONArray("links").length())
        val oneMoreChunk = page.put("text", text.take(body.length + 500))
            .put("next_offset", offset + body.length + 500)
        assertTrue(oneMoreChunk.toString().length > 15500)
    }
}
