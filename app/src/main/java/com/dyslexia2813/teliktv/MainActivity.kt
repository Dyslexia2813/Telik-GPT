package com.dyslexia2813.teliktv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        private const val TAG = "TelikTV"
        private const val CHANNEL_URL = "https://telik.live/zhivaya-priroda.html"
        private const val RETRY_DELAY_MS = 5000L
        private const val MAX_RESOLVE_TIME_MS = 20000L
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var resolverWebView: WebView
    private var player: ExoPlayer? = null
    private val resolving = AtomicBoolean(false)
    private var resolved = false
    private var streamReferer: String? = null
    private var streamOrigin: String? = null
    private var streamCookie: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        buildUi()
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
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createResolverWebView() {
        resolverWebView = WebView(this).apply {
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.contains(".m3u8", ignoreCase = true)) {
                        Log.d(TAG, "Stream URL detected: $url")
                        captureStreamContext(url, request.requestHeaders)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request != null) {
                        Log.e(TAG, "Web HTTP ${errorResponse?.statusCode}: ${request.url}")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request != null && error != null) {
                        Log.e(TAG, "Web error ${error.errorCode}: ${error.description} URL=${request.url}")
                    }
                }
            }
        }
        root.addView(resolverWebView, FrameLayout.LayoutParams(1, 1))
    }

    private fun captureStreamContext(url: String, headers: Map<String, String>) {
        if (resolved) return
        streamReferer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        streamOrigin = headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        streamCookie = CookieManager.getInstance().getCookie(url)
        runOnUiThread { playResolvedUrl(url) }
    }

    private fun resolveAndPlay() {
        if (!resolving.compareAndSet(false, true)) return
        resolved = false
        streamReferer = null
        streamOrigin = null
        streamCookie = null
        resolverWebView.stopLoading()
        resolverWebView.loadUrl(CHANNEL_URL)
        resolverWebView.postDelayed({
            if (!resolved && resolving.get()) scheduleResolveRetry()
        }, MAX_RESOLVE_TIME_MS)
    }

    private fun playResolvedUrl(url: String) {
        if (resolved) return
        resolved = true
        resolving.set(false)
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

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Playback error: ${error.errorCodeName} ${error.message}", error)
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
                    Log.e(TAG, "ExoPlayer start exception", t)
                }
            }
    }

    private fun scheduleResolveRetry() {
        resolving.set(false)
        resolverWebView.stopLoading()
        resolverWebView.postDelayed({ resolveAndPlay() }, RETRY_DELAY_MS)
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
