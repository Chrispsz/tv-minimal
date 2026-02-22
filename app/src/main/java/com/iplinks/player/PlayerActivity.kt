package com.iplinks.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var retryCount = 0
    private var currentUrl: String? = null

    companion object {
        private const val MIN_BUFFER_MS = 15000      // 15s - prevents stuttering
        private const val MAX_BUFFER_MS = 50000      // 50s - handles network hiccups
        private const val BUFFER_FOR_PLAYBACK_MS = 2500
        private const val BUFFER_AFTER_REBUFFER_MS = 5000
        private const val MAX_RETRIES = 3
        private const val TAG = "PlayerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent)
        if (url == null) {
            finish()
            return
        }

        currentUrl = url

        // Essential for TV/player apps
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(surfaceView)
        setContentView(rootLayout)

        initPlayer()
        play(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = getUrlFromIntent(intent) ?: return
        currentUrl = url
        retryCount = 0  // Reset retry counter for new URL
        player?.stop()
        play(url)
    }

    private fun getUrlFromIntent(intent: Intent): String? {
        // 1. URI data como URL direta (http/rtmp)
        intent.data?.toString()?.takeIf {
            it.startsWith("http") || it.startsWith("rtmp")
        }?.let { return it }
        
        // 2. Query parameter do scheme customizado iplinks://play?stream_url=...
        intent.data?.getQueryParameter("stream_url")?.takeIf {
            it.startsWith("http") || it.startsWith("rtmp")
        }?.let { return it }

        // 3. ACTION_SEND com texto
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf {
                it.startsWith("http") || it.startsWith("rtmp")
            }?.let { return it }
        }

        // 4. Extras diretos
        return intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra("video_url")
    }

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(this))
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.message}", error)
                        
                        // Audio timestamp discontinuity - NÃO é erro fatal, player recupera sozinho
                        if (error.cause is AudioSink.UnexpectedDiscontinuityException) {
                            Log.w(TAG, "Audio discontinuity detected - auto-recovered")
                            return
                        }
                        
                        // Outros erros - retry limitado
                        if (retryCount < MAX_RETRIES && currentUrl != null) {
                            retryCount++
                            Log.d(TAG, "Retrying ($retryCount/$MAX_RETRIES)")
                            stop()
                            play(currentUrl!!)
                        }
                    }
                })
            }
    }

    private fun play(url: String) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        surfaceView = null
        currentUrl = null
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
