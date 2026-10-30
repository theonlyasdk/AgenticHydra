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
        val bounded = JSONObject(boundedSearchJson(result, 1500))
        assertTrue(bounded.getBoolean("truncated"))
        assertTrue(bounded.getJSONArray("results").length() < 10)
    }
}
