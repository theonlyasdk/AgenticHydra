package app.agentichydra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun isPublicAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 255
    val second = bytes.getOrNull(1)?.toInt()?.and(255) ?: 0
    return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress &&
        !address.isMulticastAddress &&
        !(bytes.size == 16 && (first and 254) == 252) &&
        !(bytes.size == 4 && (first == 0 || first >= 224 || (first == 100 && second in 64..127)))
}

class WorkWeb(
    client: OkHttpClient,
) {
    private val client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(25, TimeUnit.SECONDS)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String) =
                        Dns.SYSTEM.lookup(hostname).also { addresses ->
                            if (addresses.isEmpty() || !addresses.all(::isPublicAddress)) {
                                throw UnknownHostException("Only public web addresses are supported.")
                            }
                        }
                },
            ).build()

    private data class Page(
        val url: HttpUrl,
        val body: String,
        val location: String?,
    )

    private suspend fun page(rawUrl: String): Page {
        var url = rawUrl.toHttpUrl()
        repeat(5) {
            require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) {
                "Use a public HTTPS URL without credentials."
            }
            if (url.host.contains(':') || Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(url.host)) {
                require(isPublicAddress(InetAddress.getByName(url.host))) { "Only public web addresses are supported." }
            }
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android) Hydra/1.2")
                    .header("Accept", "text/html,application/xhtml+xml,application/rss+xml,text/plain,application/json")
                    .build()
            val result =
                suspendCancellableCoroutine<Page> { continuation ->
                    val call = client.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(
                                call: Call,
                                e: IOException,
                            ) {
                                continuation.resumeWithException(e)
                            }

                            override fun onResponse(
                                call: Call,
                                response: Response,
                            ) {
                                try {
                                    response.use {
                                        if (it.isRedirect) {
                                            continuation.resume(
                                                Page(
                                                    request.url,
                                                    "",
                                                    it.header("Location")
                                                        ?: throw IOException("Redirect has no destination."),
                                                ),
                                            )
                                            return
                                        }
                                        if (!it.isSuccessful) throw IOException("Website returned HTTP ${it.code}.")
                                        val body = it.body ?: throw IOException("Website returned no content.")
                                        val type = body.contentType()
                                        require(
                                            type == null || type.type == "text" ||
                                                type.subtype.contains("xml") || type.subtype.contains("json"),
                                        ) {
                                            "This tool reads text pages, not binary downloads."
                                        }
                                        val bytes = ByteArray(1_048_577)
                                        var length = 0
                                        body.byteStream().use { input ->
                                            while (length < bytes.size) {
                                                val n = input.read(bytes, length, bytes.size - length)
                                                if (n < 0) break
                                                length += n
                                            }
                                        }
                                        require(length <= 1_048_576) { "Page exceeds the 1 MB limit." }
                                        continuation.resume(
                                            Page(
                                                request.url,
                                                String(
                                                    bytes,
                                                    0,
                                                    length,
                                                    type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8,
                                                ),
                                                null,
                                            ),
                                        )
                                    }
                                } catch (e: Exception) {
                                    continuation.resumeWithException(e)
                                }
                            }
                        },
                    )
                }
            if (result.location == null) return result
            url = url.resolve(result.location) ?: throw IOException("Invalid redirect URL.")
        }
        throw IOException("Too many website redirects.")
    }

    private val searchCache = WebSearchCache()

    @Volatile private var duckDuckGoRetryAt = 0L
    private val pageCache = LinkedHashMap<String, Pair<Long, WebDocument>>()

    private data class WebDocument(
        val url: String,
        val title: String,
        val text: String,
        val links: List<String>,
    )

    suspend fun search(
        query: String,
        options: SearchOptions = SearchOptions(),
    ): String = boundedSearchJson(searchResult(query, options))

    private suspend fun searchResult(
        query: String,
        options: SearchOptions,
    ): JSONObject {
        require(query.isNotBlank() && query.length <= 300) { "Use a search query of 1–300 characters." }
        val expanded = options.query(query.trim())
        val key = "$expanded|${options.timeRange}|${options.maxResults}"
        searchCache.get(key)?.let { return it }
        var source = "DuckDuckGo"
        val htmlUrl =
            "https://html.duckduckgo.com/html/"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", expanded)
                .apply { if (options.timeRange != "all") addQueryParameter("df", options.timeRange.take(1)) }
                .build()
        var hits =
            if (System.currentTimeMillis() < duckDuckGoRetryAt) {
                emptyList()
            } else {
                try {
                    parseSearchHits(page(htmlUrl.toString()).body)
                } catch (
                    e: CancellationException,
                ) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        if (hits.isEmpty()) {
            // A blocked HTML endpoint should not slow down every query in the same task.
            if (System.currentTimeMillis() >= duckDuckGoRetryAt) {
                duckDuckGoRetryAt = System.currentTimeMillis() + 5 * 60 * 1000
            }
            source = "Bing RSS"
            val rssUrl =
                "https://www.bing.com/search"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("format", "rss")
                    .addQueryParameter("q", expanded)
                    .apply {
                        if (options.timeRange != "all") {
                            addQueryParameter(
                                "qft",
                                "+filterui:age-lt" +
                                    when (options.timeRange) {
                                        "day" -> "1440"
                                        "week" -> "10080"
                                        "month" -> "43200"
                                        else -> "525600"
                                    },
                            )
                        }
                    }.build()
            hits = parseSearchRss(page(rssUrl.toString()).body)
        }
        check(
            hits.isNotEmpty(),
        ) { "Search is unavailable, blocked, or returned no results. No sources were retrieved." }
        val filtered = hits.filter { options.accepts(it.host) }.distinctBy { it.url }.take(options.maxResults)
        val result =
            JSONObject()
                .put("query", query.trim())
                .put("source", source)
                .put(
                    "retrieved_at",
                    java.time.Instant
                        .now()
                        .toString(),
                ).put(
                    "cached",
                    false,
                ).put("time_range", options.timeRange)
                .put("results", JSONArray().apply { filtered.forEach { put(it.json()) } })
        if (options.timeRange != "all") {
            result.put(
                "date_filter_note",
                "Provider date filtering is best effort; verify publication dates in original sources.",
            )
        }
        if (filtered.isEmpty()) {
            result.put(
                "warning",
                "No returned results matched the domain filters. Broaden the query or filters.",
            )
        }
        searchCache.put(key, result)
        return result
    }

    suspend fun searchBatch(
        queries: List<String>,
        options: SearchOptions,
    ): String =
        coroutineScope {
            require(
                queries.size in 1..3 && queries.distinct().size == queries.size &&
                    queries.all {
                        it.isNotBlank() && it.length <= 300
                    },
            ) { "Use one to three distinct queries of 1–300 characters." }
            val semaphore = Semaphore(2)
            val searches =
                queries
                    .map { query ->
                        async {
                            semaphore.withPermit {
                                try {
                                    searchResult(query, options)
                                } catch (
                                    e: CancellationException,
                                ) {
                                    throw e
                                } catch (e: Exception) {
                                    JSONObject()
                                        .put("query", query)
                                        .put(
                                            "error",
                                            e.message ?: "Search failed.",
                                        ).put("results", JSONArray())
                                }
                            }
                        }
                    }.awaitAll()
            boundedSearchJson(JSONObject().put("searches", JSONArray(searches)))
        }

    private suspend fun document(url: String): WebDocument {
        synchronized(
            pageCache,
        ) { pageCache[url]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60 * 1000 }?.let { return it.second } }
        val page = page(url)
        val doc = Jsoup.parse(page.body, page.url.toString())
        doc.select("script,style,noscript,svg,nav,footer,header,aside,form").remove()
        val content = doc.selectFirst("main,article") ?: doc.body()
        val links =
            content
                .select("a[href]")
                .mapNotNull { element ->
                    val href = canonicalSearchUrl(element.absUrl("href"))
                    if (href?.startsWith("https://") == true) "${element.text().take(100)}: $href" else null
                }.distinct()
                .take(12)
        val text =
            content
                .wholeText()
                .lineSequence()
                .map { it.trim() }
                .joinToString(
                    "\n",
                ).replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .take(120000)
        val document = WebDocument(page.url.toString(), doc.title().take(300), text, links)
        synchronized(pageCache) {
            pageCache[url] = System.currentTimeMillis() to document
            while (pageCache.size > 16) {
                pageCache.remove(pageCache.keys.first())
            }
        }
        return document
    }

    suspend fun fetch(
        url: String,
        maxChars: Int = 10000,
        offset: Int = 0,
    ): String {
        require(
            maxChars in 1000..12000 && offset in 0..120000,
        ) { "Use 1,000–12,000 characters and a valid text offset." }
        val doc = document(url)
        require(offset <= doc.text.length) { "Offset is beyond the page text." }
        val end = (offset + maxChars).coerceAtMost(doc.text.length)
        val result =
            JSONObject()
                .put("source", doc.url)
                .put("title", doc.title)
                .put("offset", offset)
                .put("total_characters", doc.text.length)
                .put("text", doc.text.substring(offset, end))
                .put("next_offset", if (end < doc.text.length) end else JSONObject.NULL)
                .put("links", JSONArray(doc.links.take(8)))
        while (result.toString().length > 15000 && (result.getJSONArray("links").length() > 0)) {
            result.getJSONArray("links").remove(result.getJSONArray("links").length() - 1)
        }
        // JSON escaping can make code-heavy text larger than its raw text length.
        while (result.toString().length > 15500) {
            val text = result.getString("text")
            result.put("text", text.dropLast(500))
            result.put(
                "next_offset",
                offset + result.getString("text").length,
            )
        }
        return result.toString()
    }

    suspend fun find(
        url: String,
        query: String,
        maxMatches: Int = 6,
    ): String {
        require(query.isNotBlank() && query.length <= 200 && maxMatches in 1..10) {
            "Use a query of 1–200 characters and 1–10 matches."
        }
        val doc = document(url)
        val matches =
            doc.text
                .lines()
                .mapIndexedNotNull { index, line ->
                    val at = line.indexOf(query, ignoreCase = true)
                    if (at < 0) {
                        null
                    } else {
                        JSONObject()
                            .put(
                                "line",
                                index + 1,
                            ).put(
                                "excerpt",
                                line.substring(
                                    (at - 180).coerceAtLeast(0),
                                    (at + query.length + 350).coerceAtMost(line.length),
                                ),
                            )
                    }
                }.take(maxMatches)
        return JSONObject()
            .put("source", doc.url)
            .put("title", doc.title)
            .put("query", query)
            .put("matches", JSONArray(matches))
            .toString()
    }
}

internal fun parseSearchHtml(html: String): String =
    parseSearchHits(html).take(8).joinToString("\n\n") {
        "${it.title}\n${it.url}\n${it.snippet.take(500)}"
    }
