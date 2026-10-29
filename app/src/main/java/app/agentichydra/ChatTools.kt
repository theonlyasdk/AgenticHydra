package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject

/** Read-only tools for chat mode: public web research, the clock, and user questions. */
internal object ChatTools {
    val names=listOf("web_search","web_search_batch","fetch_url","find_in_page","get_time","ask_user")
    fun schema():JSONArray=JSONArray()
        .put(toolSchema("web_search","Search public sources with deduplicated titles, URLs, snippets, and source IDs. Supports domain filters, result limits, best-effort freshness, and a five-minute cache. Results may be blocked or rate-limited.",listOf("query"),
            "query" to stringSchema("Search query, maximum 300 characters."),*searchFilterSchema()))
        .put(toolSchema("web_search_batch","Run one to three independent searches, with at most two requests in parallel. Returns separate structured results and errors per query. Prefer over repeated sequential searches.",listOf("queries"),
            "queries" to JSONObject().put("type","array").put("minItems",1).put("maxItems",3).put("items",stringSchema("Distinct search query, maximum 300 characters.")),*searchFilterSchema()))
        .put(toolSchema("fetch_url","Read a public HTTPS page with source links and text pagination. Use next_offset to continue reading. No credentials, private addresses, scripts, or binary downloads; maximum download 1 MB.",listOf("url"),
            "url" to stringSchema("Public HTTPS URL."),"max_chars" to integerSchema("Text budget, default 10,000 characters.",1000,12000),"offset" to integerSchema("Text offset, default zero.",0,120000)))
        .put(toolSchema("find_in_page","Find literal text in a public HTTPS page, returning line numbers and short contextual excerpts. Reuses cached page text.",listOf("url","query"),
            "url" to stringSchema("Public HTTPS URL."),"query" to stringSchema("Text to find, 1–200 characters."),"max_matches" to integerSchema("Maximum matches, default six.",1,10)))
        .put(toolSchema("get_time","Read the device's current UTC/local time and time zone before interpreting dates.",emptyList()))
        .put(QuestionTool.schema())
}
