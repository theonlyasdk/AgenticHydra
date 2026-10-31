package app.agentichydra

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.Locale

data class SearchOptions(
    val maxResults: Int = 6,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    val timeRange: String = "all",
) {
    init {
        require(
            maxResults in 1..10 && timeRange in setOf("all", "day", "week", "month", "year"),
        ) { "Use 1–10 results and a valid time range." }
        require(
            includeDomains.size <= 4 && excludeDomains.size <= 4 &&
                (includeDomains + excludeDomains).all {
                    it.length <= 100 &&
                        it.matches(
                            Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}"),
                        )
                },
        ) { "Use up to four plain domains per filter, without URLs or wildcards." }
        require(includeDomains.none { it in excludeDomains }) { "A domain cannot be both included and excluded." }
    }

    fun accepts(host: String) =
        (includeDomains.isEmpty() || includeDomains.any { host == it || host.endsWith(".$it") }) &&
            excludeDomains.none { host == it || host.endsWith(".$it") }

    fun query(raw: String): String =
        buildString {
            append(raw)
            if (includeDomains.isNotEmpty()) {
                append(
                    includeDomains.joinToString(" OR ", prefix = " (", postfix = ")") {
                        "site:$it"
                    },
                )
            }
            excludeDomains.forEach { append(" -site:$it") }
        }

    companion object {
        fun fromJson(args: JSONObject): SearchOptions {
            fun domains(key: String) =
                args
                    .optJSONArray(key)
                    ?.let { a ->
                        (0 until a.length()).map { a.getString(it).trim().lowercase(Locale.ROOT) }.distinct()
                    }.orEmpty()
            return SearchOptions(
                args.optInt("max_results", 6),
                domains("include_domains"),
                domains("exclude_domains"),
                args.optString("time_range", "all"),
            )
        }
    }
}

internal data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
) {
    val host get() = url.toHttpUrlOrNull()?.host.orEmpty()

    fun json() =
        JSONObject()
            .put("id", "s_" + Integer.toUnsignedString(url.hashCode(), 16))
            .put("title", title.take(200))
            .put("url", url)
            .put("domain", host)
            .put("snippet", snippet.take(500))
}

internal fun canonicalSearchUrl(raw: String): String? {
    val url =
        raw.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.toString().length <= 2048
        } ?: return null
    val builder = url.newBuilder().fragment(null)
    url.queryParameterNames
        .filter {
            it.startsWith("utm_") || it in setOf("fbclid", "gclid", "msclkid")
        }.forEach { builder.removeAllQueryParameters(it) }
    return builder.build().toString()
}

internal fun parseSearchHits(html: String): List<SearchHit> =
    Jsoup
        .parse(html, "https://html.duckduckgo.com")
        .select(".result")
        .mapNotNull { result ->
            val link = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val wrapper = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val url = canonicalSearchUrl(wrapper.queryParameter("uddg") ?: wrapper.toString()) ?: return@mapNotNull null
            SearchHit(link.text(), url, result.selectFirst(".result__snippet")?.text().orEmpty())
        }.distinctBy { it.url }

internal fun parseSearchRss(xml: String): List<SearchHit> =
    Jsoup
        .parse(xml, "", Parser.xmlParser())
        .select("item")
        .mapNotNull { item ->
            val url = canonicalSearchUrl(item.selectFirst("link")?.text().orEmpty()) ?: return@mapNotNull null
            SearchHit(
                item.selectFirst("title")?.text().orEmpty(),
                url,
                Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text(),
            )
        }.distinctBy { it.url }

/** Never cut JSON in the middle of a result when the agent's tool budget is reached. */
internal fun boundedSearchJson(
    value: JSONObject,
    maxChars: Int = 14500,
): String {
    val groups =
        value.optJSONArray("searches")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOf(value)
    while (value.toString().length > maxChars) {
        val results =
            groups
                .mapNotNull {
                    it.optJSONArray(
                        "results",
                    )
                }.filter { it.length() > 0 }
                .maxByOrNull { it.toString().length } ?: break
        results.remove(results.length() - 1)
        value.put("truncated", true)
    }
    return value.toString()
}

internal class WebSearchCache(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(
        val json: String,
        val at: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun get(key: String): JSONObject? {
        val entry = entries[key] ?: return null
        if (now() - entry.at >= 5 * 60 * 1000) {
            entries.remove(key)
            return null
        }
        return JSONObject(entry.json).put("cached", true)
    }

    @Synchronized
    fun put(
        key: String,
        value: JSONObject,
    ) {
        entries.remove(key)
        entries[key] = Entry(value.toString(), now())
        while (entries.size > 32) {
            entries.remove(entries.keys.first())
        }
    }
}

internal fun searchFilterSchema(): Array<Pair<String, JSONObject>> =
    arrayOf(
        "max_results" to integerSchema("Maximum results per query, default 6.", 1, 10),
        "include_domains" to
            JSONObject()
                .put(
                    "type",
                    "array",
                ).put("items", stringSchema("Plain domain, e.g. developer.android.com."))
                .put("maxItems", 4),
        "exclude_domains" to
            JSONObject().put("type", "array").put("items", stringSchema("Plain domain to exclude.")).put("maxItems", 4),
        "time_range" to
            stringSchema(
                "Best-effort search-provider date filter. Publication dates are not independently verified.",
            ).put("enum", JSONArray(listOf("all", "day", "week", "month", "year"))),
    )

/**
 * Function schemas for the research tools chat, task, and background engines all offer with the
 * same parameters. Builders return a fresh object so two engines can never share a mutable JSON tree.
 */
internal object ResearchToolSchemas {
    fun webSearch(): JSONObject =
        toolSchema(
            "web_search",
            "Search public sources with deduplicated titles, URLs, snippets, and source IDs. Supports domain filters, result limits, best-effort freshness, and a five-minute cache. Results may be blocked or rate-limited.",
            listOf("query"),
            "query" to stringSchema("Search query, maximum 300 characters."),
            *searchFilterSchema(),
        )

    fun webSearchBatch(): JSONObject =
        toolSchema(
            "web_search_batch",
            "Run one to three independent searches, with at most two requests in parallel. Returns separate structured results and errors per query. Prefer over repeated sequential searches.",
            listOf("queries"),
            "queries" to
                JSONObject()
                    .put("type", "array")
                    .put("minItems", 1)
                    .put("maxItems", 3)
                    .put("items", stringSchema("Distinct search query, maximum 300 characters.")),
            *searchFilterSchema(),
        )

    fun fetchUrl(): JSONObject =
        toolSchema(
            "fetch_url",
            "Read a public web page with source links and text pagination. Use next_offset to continue reading. No credentials, private addresses, scripts, or binary downloads; maximum download 1 MB. Parsed pages are cached five minutes.",
            listOf("url"),
            "url" to stringSchema("Public web URL."),
            "max_chars" to integerSchema("Text budget, default 10,000 characters.", 1000, 12000),
            "offset" to integerSchema("Text offset, default zero.", 0, 120000),
        )

    fun findInPage(): JSONObject =
        toolSchema(
            "find_in_page",
            "Find literal text in a public web page, returning line numbers and short contextual excerpts. Reuses cached page text; efficient for locating relevant passages.",
            listOf("url", "query"),
            "url" to stringSchema("Public web URL."),
            "query" to stringSchema("Text to find, 1–200 characters."),
            "max_matches" to integerSchema("Maximum matches, default six.", 1, 10),
        )

    fun getTime(): JSONObject =
        toolSchema(
            "get_time",
            "Read the device's current UTC/local time and time zone before interpreting dates.",
            emptyList(),
        )
}
