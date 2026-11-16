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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Live view bundled in the app (web/live-embed.html, built into assets/live), served over the asset loader's https origin. */
const val LIVE_URL = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/live/live-embed.html"

/** The JavaScript that hands one /ws/power message to the page. */
fun pushScript(power: LivePower): String = "window.liveFeed && window.liveFeed.push(${pageJson.encodeToString(LivePower.serializer(), power)})"

/** Every field, empty lists and nulls included: an empty flows list means readings stopped, so it must reach the page. */
private val pageJson = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = true }

fun connectedScript(connected: Boolean): String = "window.liveFeed && window.liveFeed.connected($connected)"

/** The same hand-over, answering whether the page was listening: React mounts after the page loads, so the first try can miss. */
fun handOverScript(power: LivePower): String = "(function(){ if (!window.liveFeed) return false; ${pushScript(power)}; return true })()"

/** No reading for this long and the connection is taken to be dead (readings arrive every few seconds). */
val LIVE_SILENCE = 30.seconds

/**
 * Follows /ws/power for as long as it is called: each reading goes to [onPower]. A dropped connection, or one that has
 * gone quiet for [silence] (a phone back from the background can hold a socket that will never speak again), calls
 * [onLost] and reconnects, waiting 1 s, then 2 s, up to 30 s while it keeps failing.
 */
@OptIn(FlowPreview::class)
suspend fun followLive(stream: () -> Flow<LivePower>, onPower: (LivePower) -> Unit, onLost: () -> Unit, silence: Duration = LIVE_SILENCE) {
    var backoff = 1000L
    while (true) {
        runCatching {
            stream().timeout(silence).collect { p ->
                backoff = 1000L
                onPower(p)
            }
        }
        // A timeout or a dropped socket ends up here; being cancelled (leaving the tab, going to the background) does not.
        currentCoroutineContext().ensureActive()
        onLost()
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(30_000L)
    }
}

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

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only while the app is on screen: the socket closes in the background and a fresh one opens on the way back.
    LaunchedEffect(api, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            followLive(api::livePower, onPower = { latest = it }, onLost = { web?.evaluateJavascript(connectedScript(false), null) })
        }
    }
    LaunchedEffect(ready, latest) {
        val p = latest ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        // Until the page answers that it is listening (a newer reading restarts this).
        repeat(50) {
            val taken = kotlinx.coroutines.CompletableDeferred<Boolean>()
            web?.evaluateJavascript(handOverScript(p)) { taken.complete(it == "true") } ?: return@LaunchedEffect
            if (taken.await()) return@LaunchedEffect
            delay(200)
        }
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
