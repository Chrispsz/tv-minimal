package com.iplinks.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var retryCount = 0
    private var currentUrl: String? = null
    private val syncHandler = Handler(Looper.getMainLooper())
    private var lastSyncCheckTime: Long = 0
    private var initialPosition: Long = 0

    companion object {
        private const val MIN_BUFFER_MS = 10000      // 10s - reduced for less drift
        private const val MAX_BUFFER_MS = 30000      // 30s - reduced from 50s
        private const val BUFFER_FOR_PLAYBACK_MS = 2500
        private const val BUFFER_AFTER_REBUFFER_MS = 5000
        private const val MAX_RETRIES = 3
        private const val SYNC_CHECK_INTERVAL_MS = 30000L  // Check every 30s
        private const val MAX_DRIFT_MS = 500L               // Max allowed drift
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent)
        if (url == null) {
            finish()
            return
        }

        currentUrl = url

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
        retryCount = 0
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
        // Smaller buffer = less audio drift accumulation
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Audio attributes for proper handling
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(this))
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                setAudioAttributes(audioAttributes, true)
                
                // Handle audio becoming noisy (headphone disconnect)
                setHandleAudioBecomingNoisy(true)

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (retryCount < MAX_RETRIES && currentUrl != null) {
                            retryCount++
                            stop()
                            play(currentUrl!!)
                        }
                    }
                })
            }
    }

    private fun play(url: String) {
        player?.apply {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.05f)    // Allow slight speedup to catch up
                        .setMinPlaybackSpeed(0.95f)   // Allow slight slowdown
                        .setTargetOffsetMs(3000L)     // Target 3s from live edge
                        .build()
                )
                .build()
            
            setMediaItem(mediaItem)
            prepare()
            
            // Reset sync tracking
            lastSyncCheckTime = System.currentTimeMillis()
            initialPosition = currentPosition
            
            // Start periodic sync check
            startSyncCheck()
        }
    }

    private fun startSyncCheck() {
        syncHandler.postDelayed(object : Runnable {
            override fun run() {
                player?.let { p ->
                    // Check for significant audio/video drift
                    val currentTime = System.currentTimeMillis()
                    val elapsed = currentTime - lastSyncCheckTime
                    
                    if (elapsed >= SYNC_CHECK_INTERVAL_MS) {
                        // Calculate expected vs actual progress
                        val expectedProgress = p.currentPosition
                        val bufferInfo = p.bufferedPosition
                        
                        // If drift is too large, soft reload
                        if (bufferInfo > 0 && (bufferInfo - expectedProgress) > MAX_DRIFT_MS) {
                            // Seek to near live position to resync
                            p.seekTo(maxOf(0, bufferInfo - 2000))
                        }
                        
                        lastSyncCheckTime = currentTime
                    }
                    
                    // Continue checking
                    syncHandler.postDelayed(this, SYNC_CHECK_INTERVAL_MS)
                }
            }
        }, SYNC_CHECK_INTERVAL_MS)
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
        syncHandler.removeCallbacksAndMessages(null)
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
