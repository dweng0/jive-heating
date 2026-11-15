package heating.app.ui.live

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import heating.app.data.HeatingApi
import heating.app.data.LivePower
import kotlinx.coroutines.delay

/** The Live view bundled in the app (web/live-embed.html, built into assets/live), served over the asset loader's https origin. */
const val LIVE_URL = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/live/live-embed.html"

/** The JavaScript that hands one /ws/power message to the page. */
fun pushScript(power: LivePower): String = "window.liveFeed && window.liveFeed.push(${pageJson.encodeToString(LivePower.serializer(), power)})"

/** Every field, empty lists and nulls included: an empty flows list means readings stopped, so it must reach the page. */
private val pageJson = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = true }

fun connectedScript(connected: Boolean): String = "window.liveFeed && window.liveFeed.connected($connected)"

/**
 * Live: the same full-width 3D house and flow diagram as the web app, fed by this app's own /ws/power connection, so it
 * works however the phone reaches the service. Reconnects while the tab is open.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveScreen(api: HeatingApi, contentPadding: PaddingValues) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<LivePower?>(null) }

    LaunchedEffect(api) {
        var backoff = 1000L
        while (true) {
            runCatching {
                api.livePower().collect { p ->
                    backoff = 1000L
                    latest = p
                }
            }
            web?.evaluateJavascript(connectedScript(false), null)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }
    LaunchedEffect(ready, latest) {
        val p = latest ?: return@LaunchedEffect
        if (ready) web?.evaluateJavascript(pushScript(p), null)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        factory = { context ->
            val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true   // remembers House or Diagram
                settings.allowFileAccess = false
                setBackgroundColor(0xFF0A0E13.toInt())
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assets.shouldInterceptRequest(request.url)
                    override fun onPageFinished(view: WebView, url: String) { ready = true }
                }
                loadUrl(LIVE_URL)
                web = this
            }
        },
        onRelease = { it.destroy(); web = null; ready = false },
    )
}
