package com.dyslexia2813.teliktv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        private const val TAG = "TelikTV-DIAG"
        private const val CHANNEL_URL = "https://telik.live/zhivaya-priroda.html"
        private const val RETRY_DELAY_MS = 5000L
        private const val MAX_RESOLVE_TIME_MS = 20000L
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var resolverWebView: WebView
    private lateinit var diagnosticView: TextView
    private var player: ExoPlayer? = null
    private val resolving = AtomicBoolean(false)
    private var resolved = false
    private var lastStreamUrl: String? = null
    private var streamReferer: String? = null
    private var streamOrigin: String? = null
    private var streamCookie: String? = null
    private var requestCount = 0
    private var streamRequestCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        buildUi()
        logStatus("1. Loading page...", true)
        createResolverWebView()
        resolveAndPlay()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        diagnosticView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            setPadding(24, 18, 24, 18)
            isVerticalScrollBarEnabled = true
        }
        val diagnosticParams = FrameLayout.LayoutParams(-1, -1).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16, 16, 16, 16)
        }
        root.addView(diagnosticView, diagnosticParams)
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createResolverWebView() {
        resolverWebView = WebView(this).apply {
            // The resolver must be attached to the window. The previous implementation
            // created a WebView but never added it to the view hierarchy.
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    trace("WebView console: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    request ?: return super.shouldInterceptRequest(view, request)
                    requestCount++
                    val url = request.url.toString()
                    val headers = request.requestHeaders
                    trace(
                        "REQ #$requestCount ${if (request.isForMainFrame) "MAIN" else "SUB"} " +
                            "${request.method} $url"
                    )

                    if (url.contains(".m3u8", ignoreCase = true)) {
                        streamRequestCount++
                        trace("5. Stream URL request detected (#$streamRequestCount)")
                        trace("Stream URL: $url")
                        trace("Request headers: ${headers.entries.joinToString()}" )
                        captureStreamContext(url, headers)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    trace("WebView page started: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    trace("2. Page loaded: $url")
                    evaluateVideoSources(view)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request != null) {
                        trace(
                            "WEB HTTP ERROR ${errorResponse?.statusCode} " +
                                "${request.method} ${request.url} " +
                                "${errorResponse?.reasonPhrase ?: ""}"
                        )
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request != null && error != null) {
                        trace("WEB ERROR ${error.errorCode}: ${error.description} URL=${request.url}")
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    trace("WEB SSL ERROR: ${error?.primaryError} URL=${error?.url}")
                    handler?.cancel()
                }
            }
        }

        // 1x1 attached resolver: invisible but active.
        val params = FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = 0
            topMargin = 0
        }
        root.addView(resolverWebView, params)
    }

    private fun evaluateVideoSources(view: WebView?) {
        view ?: return
        view.evaluateJavascript(
            """
            (function(){
              var out=[];
              document.querySelectorAll('iframe').forEach(function(x){out.push('IFRAME:'+x.src);});
              document.querySelectorAll('video,source').forEach(function(x){if(x.src)out.push('VIDEO:'+x.src);});
              return out.join('\\n');
            })()
            """.trimIndent()
        ) { value ->
            val decoded = value.trim('"').replace("\\/", "/").replace("\\n", "\n")
            if (decoded.isNotBlank()) {
                trace("3. DOM media/iframe sources:\n$decoded")
                if (decoded.contains("IFRAME:")) trace("4. Iframe detected.")
                decoded.lines().forEach { line ->
                    val candidate = line.removePrefix("VIDEO:").trim()
                    if (candidate.contains(".m3u8", ignoreCase = true)) {
                        checkForStreamUrl(candidate)
                    }
                }
            } else {
                trace("3. DOM media/iframe sources: none found on main document")
            }
        }
    }

    private fun captureStreamContext(url: String, headers: Map<String, String>) {
        if (resolved) return
        lastStreamUrl = url
        streamReferer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        streamOrigin = headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        streamCookie = CookieManager.getInstance().getCookie(url)
        trace("Captured Referer: ${streamReferer ?: "<none>"}")
        trace("Captured Origin: ${streamOrigin ?: "<none>"}")
        trace("Captured Cookie: ${if (streamCookie.isNullOrBlank()) "<none>" else "present"}")
        runOnUiThread { playResolvedUrl(url) }
    }

    private fun checkForStreamUrl(url: String) {
        if (url.contains(".m3u8", ignoreCase = true) && !resolved) {
            trace("5. Stream URL found: $url")
            runOnUiThread { playResolvedUrl(url) }
        }
    }

    private fun resolveAndPlay() {
        if (!resolving.compareAndSet(false, true)) return
        resolved = false
        lastStreamUrl = null
        streamReferer = null
        streamOrigin = null
        streamCookie = null
        requestCount = 0
        streamRequestCount = 0

        trace("\n=== RESOLVE ATTEMPT ===")
        trace("1. Loading page: $CHANNEL_URL")
        resolverWebView.stopLoading()
        resolverWebView.loadUrl(CHANNEL_URL)

        resolverWebView.postDelayed({
            if (!resolved && resolving.get()) {
                trace("TIMEOUT: no playable .m3u8 detected within ${MAX_RESOLVE_TIME_MS} ms")
                scheduleResolveRetry()
            }
        }, MAX_RESOLVE_TIME_MS)
    }

    private fun playResolvedUrl(url: String) {
        if (resolved) return
        resolved = true
        resolving.set(false)
        lastStreamUrl = url
        trace("6. Preparing ExoPlayer")
        trace("Stream URL: $url")
        trace("Referer: ${streamReferer ?: "<none>"}")
        trace("Origin: ${streamOrigin ?: "<none>"}")
        trace("Cookie: ${if (streamCookie.isNullOrBlank()) "<none>" else "present"}")

        resolverWebView.stopLoading()
        player?.release()

        val requestProperties = linkedMapOf<String, String>()
        requestProperties["User-Agent"] = resolverWebView.settings.userAgentString
        streamReferer?.takeIf { it.isNotBlank() }?.let { requestProperties["Referer"] = it }
        streamOrigin?.takeIf { it.isNotBlank() }?.let { requestProperties["Origin"] = it }
        streamCookie?.takeIf { it.isNotBlank() }?.let { requestProperties["Cookie"] = it }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestProperties)

        trace("7. Starting ExoPlayer")
        trace("Player headers: ${requestProperties.keys.joinToString()}")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val state = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> playbackState.toString()
                        }
                        trace("Player state: $state")
                        if (playbackState == Player.STATE_BUFFERING) trace("8. Buffering...")
                        if (playbackState == Player.STATE_READY) trace("Player READY")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        trace("9. isPlaying=$isPlaying")
                        if (isPlaying) trace("10. PLAYING")
                    }

                    override fun onRenderedFirstFrame() {
                        trace("VIDEO FRAME RENDERED: first frame received")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val cause = error.cause
                        val details = buildString {
                            append("ERROR\n")
                            append("PlaybackException: ${error.message}\n")
                            append("errorCode=${error.errorCode}\n")
                            append("errorName=${error.errorCodeName}\n")
                            append("cause=${cause?.javaClass?.name}: ${cause?.message}\n")
                            var current: Throwable? = cause
                            var depth = 0
                            while (current != null && depth < 5) {
                                append("cause[$depth]=${current.javaClass.name}: ${current.message}\n")
                                current = current.cause
                                depth++
                            }
                        }
                        trace(details)
                        Log.e(TAG, details, error)
                        trace("Playback failed. Retrying resolver in ${RETRY_DELAY_MS} ms")
                        playerView.postDelayed({ resolveAndPlay() }, RETRY_DELAY_MS)
                    }
                })

                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(url))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()

                try {
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } catch (t: Throwable) {
                    trace("EXOPLAYER START EXCEPTION: ${t.javaClass.name}: ${t.message}")
                    Log.e(TAG, "ExoPlayer start exception", t)
                }
            }
    }

    private fun scheduleResolveRetry() {
        resolving.set(false)
        resolverWebView.stopLoading()
        trace("Retrying page resolver...")
        resolverWebView.postDelayed({ resolveAndPlay() }, RETRY_DELAY_MS)
    }

    private fun trace(message: String, log: Boolean = true) {
        Log.d(TAG, message)
        if (log && ::diagnosticView.isInitialized) {
            runOnUiThread {
                diagnosticView.append("$message\n")
                diagnosticView.scrollTo(0, diagnosticView.layout?.height ?: 0)
            }
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        resolverWebView.destroy()
        super.onDestroy()
    }
}
