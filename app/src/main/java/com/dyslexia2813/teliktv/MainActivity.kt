package com.dyslexia2813.teliktv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        private const val CHANNEL_URL = "https://telik.live/zhivaya-priroda.html"
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_RESOLVE_TIME_MS = 15000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var resolverWebView: WebView
    private var player: ExoPlayer? = null
    private val resolving = AtomicBoolean(false)
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            keepScreenOn = true
        }
        setContentView(playerView)
        createResolverWebView()
        resolveAndPlay()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createResolverWebView() {
        resolverWebView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    request?.url?.toString()?.let(::checkForStreamUrl)
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        "(function(){var a=document.querySelectorAll('video,source');for(var i=0;i<a.length;i++){if(a[i].src)return a[i].src;}return '';})()"
                    ) { value ->
                        val urlValue = value.trim('"').replace("\\/", "/")
                        if (urlValue.contains(".m3u8")) checkForStreamUrl(urlValue)
                    }
                }
            }
        }
    }

    private fun checkForStreamUrl(url: String) {
        if (url.contains(".m3u8", ignoreCase = true) && !resolved) {
            runOnUiThread { playResolvedUrl(url) }
        }
    }

    private fun resolveAndPlay() {
        if (!resolving.compareAndSet(false, true)) return
        resolved = false
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
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Android TV)")
            .setDefaultRequestProperties(mapOf("Referer" to "https://cdntvmedia.com/"))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playerView.postDelayed({ resolveAndPlay() }, RETRY_DELAY_MS)
                    }
                })
                exoPlayer.setMediaItem(MediaItem.fromUri(url))
                exoPlayer.prepare()
                exoPlayer.play()
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
