package app.agentichydra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun isPublicAddress(address:InetAddress):Boolean {
    val bytes=address.address
    val first=bytes[0].toInt() and 255
    val second=bytes.getOrNull(1)?.toInt()?.and(255)?:0
    return !address.isAnyLocalAddress&&!address.isLoopbackAddress&&!address.isLinkLocalAddress&&!address.isSiteLocalAddress&&!address.isMulticastAddress&&
        !(bytes.size==16&&(first and 254)==252)&&!(bytes.size==4&&(first==0||first>=224||(first==100&&second in 64..127)))
}

class WorkWeb(client:OkHttpClient) {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(25,TimeUnit.SECONDS)
        .dns(object:Dns {
            override fun lookup(hostname:String)=Dns.SYSTEM.lookup(hostname).also{addresses->
                if(addresses.isEmpty()||!addresses.all(::isPublicAddress))throw UnknownHostException("Only public web addresses are supported.")
            }
        }).build()
    private data class Page(val url:HttpUrl,val body:String,val location:String?)

    private suspend fun page(rawUrl:String):Page {
        var url=rawUrl.toHttpUrl()
        repeat(5) {
            require(url.isHttps&&url.port==443&&url.username.isEmpty()&&url.password.isEmpty()){"Use a public HTTPS URL without credentials."}
            if(url.host.contains(':')||Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(url.host))
                require(isPublicAddress(InetAddress.getByName(url.host))){"Only public web addresses are supported."}
            val request=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Android) Hydra/1.2")
                .header("Accept","text/html,application/xhtml+xml,application/rss+xml,text/plain,application/json").build()
            val result=suspendCancellableCoroutine<Page> { continuation ->
                val call=client.newCall(request)
                continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException){continuation.resumeWithException(e)}
                    override fun onResponse(call:Call,response:Response) {
                        try {
                            response.use {
                                if(it.isRedirect){continuation.resume(Page(request.url,"",it.header("Location")?:throw IOException("Redirect has no destination.")));return}
                                if(!it.isSuccessful)throw IOException("Website returned HTTP ${it.code}.")
                                val body=it.body?:throw IOException("Website returned no content.")
                                val type=body.contentType()
                                require(type==null||type.type=="text"||type.subtype.contains("xml")||type.subtype.contains("json")){"This tool reads text pages, not binary downloads."}
                                val bytes=ByteArray(1_048_577)
                                var length=0
                                body.byteStream().use { input ->
                                    while(length<bytes.size){val n=input.read(bytes,length,bytes.size-length);if(n<0)break;length+=n}
                                }
                                require(length<=1_048_576){"Page exceeds the 1 MB limit."}
                                continuation.resume(Page(request.url,String(bytes,0,length,type?.charset(Charsets.UTF_8)?:Charsets.UTF_8),null))
                            }
                        } catch(e:Exception){continuation.resumeWithException(e)}
                    }
                })
            }
            if(result.location==null)return result
            url=url.resolve(result.location)?:throw IOException("Invalid redirect URL.")
        }
        throw IOException("Too many website redirects.")
    }

    suspend fun search(query:String):String {
        require(query.isNotBlank()&&query.length<=300){"Use a search query of 1–300 characters."}
        val htmlUrl="https://html.duckduckgo.com/html/".toHttpUrl().newBuilder().addQueryParameter("q",query).build()
        try {
            val results=parseSearchHtml(page(htmlUrl.toString()).body)
            if(results.isNotBlank())return "Web search: $query\n\n$results"
        } catch(e:CancellationException){throw e}catch(_:Exception){ /* Try the public RSS search endpoint. */ }
        val rssUrl="https://www.bing.com/search".toHttpUrl().newBuilder().addQueryParameter("format","rss").addQueryParameter("q",query).build()
        val rss=page(rssUrl.toString())
        val items=Jsoup.parse(rss.body,"",Parser.xmlParser()).select("item").take(8)
        check(items.isNotEmpty()){"Search is unavailable or blocked by the search site. Try again later; no results were retrieved."}
        return "Web search: $query\n\n"+items.joinToString("\n\n"){"${it.selectFirst("title")?.text()}\n${it.selectFirst("link")?.text()}\n${it.selectFirst("description")?.text()?.take(500)}"}
    }

    suspend fun fetch(url:String):String {
        val page=page(url)
        val doc=Jsoup.parse(page.body,page.url.toString())
        doc.select("script,style,noscript,svg,nav,footer").remove()
        val content=doc.selectFirst("main,article")?:doc.body()
        val links=content.select("a[href]").mapNotNull { element ->
            val href=element.absUrl("href")
            if(href.startsWith("https://"))"${element.text().take(100)}: $href" else null
        }.distinct().take(12)
        return "Source: ${page.url}\nTitle: ${doc.title()}\n\n${content.wholeText().trim().take(12000)}\n\nLinks:\n${links.joinToString("\n")}".take(16000)
    }
}

internal fun parseSearchHtml(html:String):String = Jsoup.parse(html,"https://html.duckduckgo.com").select(".result").take(8).mapNotNull { result ->
    val link=result.selectFirst("a.result__a")?:return@mapNotNull null
    val wrapper=runCatching{link.absUrl("href").toHttpUrl()}.getOrNull()?:return@mapNotNull null
    val url=wrapper.queryParameter("uddg")?:wrapper.toString()
    if(!url.startsWith("https://")&&!url.startsWith("http://"))return@mapNotNull null
    "${link.text()}\n$url\n${result.selectFirst(".result__snippet")?.text().orEmpty().take(500)}"
}.joinToString("\n\n")
