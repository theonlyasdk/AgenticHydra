package app.agentichydra

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Which link the in-app browser is showing; null means the sheet is closed. */
@Stable
internal class LinkSheetController {
    var url by mutableStateOf<String?>(null)
        private set

    fun open(url: String) {
        this.url = url
    }

    fun dismiss() {
        url = null
    }
}

@Composable
internal fun rememberLinkSheet(): LinkSheetController = remember { LinkSheetController() }

/** Screens provide this so markdown links open in the link sheet instead of leaving the app. */
internal val LocalLinkOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Routes web links into the sheet (or the default browser) and every other scheme to its app. */
internal fun openInLinkSheet(
    context: Context,
    url: String,
    sheet: LinkSheetController,
    prompt: ExternalIntentPrompt? = null,
) {
    when (Uri.parse(url).scheme?.lowercase()) {
        "http", "https" -> {
            if (usesBuiltInBrowser(context)) sheet.open(url) else openExternally(context, url, prompt)
        }

        else -> openExternally(context, url, prompt)
    }
}

internal fun openExternally(
    context: Context,
    url: String,
    prompt: ExternalIntentPrompt? = null,
) {
    openExternalIntent(
        context,
        prompt,
        Icons.AutoMirrored.Outlined.OpenInNew,
        "This link opens in another app.",
        url,
        "Open it?",
        isUrl = true,
    ) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show() }
    }
}

internal data class LinkUiState(
    val url: String,
    val title: String,
    val host: String,
    val icon: ImageBitmap? = null,
    val progress: Float = 0f,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

internal class LinkActions(
    val back: () -> Unit,
    val forward: () -> Unit,
    val reload: () -> Unit,
    val stop: () -> Unit,
    val copy: () -> Unit,
    val share: () -> Unit,
    val openBrowser: () -> Unit,
    val close: () -> Unit,
)

/** Live WebView state for one sheet; resets whenever a new link is opened. */
@Stable
internal class LinkPageState(initialUrl: String) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var host by mutableStateOf(hostOf(initialUrl))
    var icon by mutableStateOf<ImageBitmap?>(null)
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var webView: WebView? = null

    fun uiState() = LinkUiState(url, title, host, icon, progress, loading, failed, canGoBack, canGoForward)

    fun back(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    fun forward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        failed = false
        loading = true
        progress = 0f
        webView?.reload()
    }

    fun stop() {
        webView?.stopLoading()
        loading = false
    }

    fun syncHistory(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }
}

@Composable
internal fun LinkSheet(controller: LinkSheetController) {
    val url = controller.url ?: return
    val page = remember(url) { LinkPageState(url) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prompt = LocalExternalIntentPrompt.current
    val openExternal: (String) -> Unit = remember(context, prompt) {
        { link -> openExternally(context, link, prompt) }
    }
    val close: () -> Unit = { controller.dismiss() }
    // A ModalBottomSheet would take vertical drags from the WebView, so this page uses the
    // non-swipe sheet variant and closes through its header, the scrim, or system back.
    HydraBottomSheet(
        onDismiss = close,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        fillHeight = true,
        swipeEnabled = false,
        onBackRequest = { if (!page.back()) close() },
    ) { dismiss ->
        LinkSheetContent(
            page,
            LinkActions(
                back = { page.back() },
                forward = { page.forward() },
                reload = { page.reload() },
                stop = { page.stop() },
                copy = { clipboard.setText(AnnotatedString(page.url)) },
                share = { shareText(context, prompt, "Share link", page.url, detail = page.url) },
                openBrowser = { openExternal(page.url) },
                close = dismiss,
            ),
            openExternal,
        )
    }
}

@Composable
private fun LinkSheetContent(
    page: LinkPageState,
    actions: LinkActions,
    openExternal: (String) -> Unit,
) {
    val state = page.uiState()
    Column(Modifier.fillMaxSize()) {
        LinkSheetHeader(state, actions)
        // Reserved strip keeps the header from jumping when a load starts or ends.
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(3.dp)) {
            if (state.loading) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LinkWebView(page, openExternal)
            if (state.failed) LinkError(actions)
        }
    }
}

@Composable
private fun LinkSheetHeader(
    state: LinkUiState,
    actions: LinkActions,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkFavicon(state.icon)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MarqueeTitle(
                state.title.ifBlank { state.host },
                Modifier.fillMaxWidth(),
                surface = cs.surfaceContainerLow,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
            )
            Text(
                state.host,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.padding(start = 8.dp)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.MoreHoriz, "Page options")
            }
            HydraOverflowMenu(menu, { menu = false }, headerText = state.host) {
                HydraMenuItem("Back", Icons.AutoMirrored.Outlined.ArrowBack, enabled = state.canGoBack) {
                    menu = false
                    actions.back()
                }
                HydraMenuItem("Forward", Icons.AutoMirrored.Outlined.ArrowForward, enabled = state.canGoForward) {
                    menu = false
                    actions.forward()
                }
                if (state.loading) {
                    HydraMenuItem("Stop loading", Icons.Outlined.Close) {
                        menu = false
                        actions.stop()
                    }
                } else {
                    HydraMenuItem("Reload", Icons.Outlined.Refresh) {
                        menu = false
                        actions.reload()
                    }
                }
                HydraMenuItem("Copy link", Icons.Outlined.ContentCopy) {
                    menu = false
                    actions.copy()
                }
                HydraMenuItem("Share", Icons.Outlined.Share) {
                    menu = false
                    actions.share()
                }
                HydraMenuItem("Open in browser", Icons.AutoMirrored.Outlined.OpenInNew) {
                    menu = false
                    actions.openBrowser()
                }
            }
        }
        IconButton(onClick = actions.close, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Close, "Close page")
        }
    }
}

/** Site icon clipped to a circle, falling back to a language glyph until one arrives. */
@Composable
private fun LinkFavicon(icon: ImageBitmap?) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(cs.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Outlined.Language, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LinkError(actions: LinkActions) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.surfaceContainerLow) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HydraEmptyState(
                Icons.Outlined.WifiOff,
                "Couldn’t load this page",
                "Check your connection, then try again.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.reload) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
                TextButton(onClick = actions.openBrowser) { Text("Open in browser") }
            }
        }
    }
}

@Composable
private fun LinkWebView(
    page: LinkPageState,
    openExternal: (String) -> Unit,
) {
    val background = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> createWebView(context, page, background, openExternal) },
        onRelease = { view ->
            page.webView = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    page: LinkPageState,
    background: Int,
    openExternal: (String) -> Unit,
): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(background)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    openExternal(request.url.toString())
                    return true
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: Bitmap?,
                ) {
                    val nextHost = hostOf(url)
                    if (nextHost != page.host) page.icon = null
                    page.url = url
                    page.host = nextHost
                    page.loading = true
                    page.failed = false
                    page.syncHistory(view)
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    if (url.isNotBlank()) {
                        page.url = url
                        page.host = hostOf(url)
                    }
                    page.loading = false
                    page.progress = 1f
                    page.syncHistory(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        page.failed = true
                        page.loading = false
                    }
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    page.syncHistory(view)
                }
            }
        webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(
                    view: WebView,
                    newProgress: Int,
                ) {
                    page.progress = newProgress / 100f
                    if (newProgress >= 100) page.loading = false
                }

                override fun onReceivedTitle(
                    view: WebView,
                    title: String?,
                ) {
                    page.title = title.orEmpty()
                }

                override fun onReceivedIcon(
                    view: WebView,
                    icon: Bitmap?,
                ) {
                    page.icon = icon?.asImageBitmap()
                }
            }
        setDownloadListener { url, _, _, _, _ -> url?.let(openExternal) }
        page.webView = this
        loadUrl(page.url)
    }

private fun hostOf(url: String): String {
    val uri = Uri.parse(url)
    return uri.host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
}

@Preview(name = "Link sheet light")
@Preview(name = "Link sheet dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Link sheet large text", fontScale = 1.5f)
@Composable
private fun LinkSheetPreview() {
    HydraTheme {
        val cs = MaterialTheme.colorScheme
        Surface(color = cs.surfaceContainerLow) {
            Column(Modifier.padding(16.dp)) {
                LinkSheetHeader(
                    LinkUiState(
                        url = "https://developer.android.com/guide/topics/ui/overview",
                        title = "User interface and navigation",
                        host = "developer.android.com",
                        loading = true,
                        progress = .42f,
                        canGoBack = true,
                    ),
                    LinkActions(
                        back = {},
                        forward = {},
                        reload = {},
                        stop = {},
                        copy = {},
                        share = {},
                        openBrowser = {},
                        close = {},
                    ),
                )
                Spacer(Modifier.height(13.dp))
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("Page content", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
