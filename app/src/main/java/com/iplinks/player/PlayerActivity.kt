package com.iplinks.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var progressBar: ProgressBar? = null
    private var streamUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
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
        
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        }
        rootLayout.addView(progressBar)
        
        setContentView(rootLayout)
        
        streamUrl = getStreamUrl(intent)
        
        if (streamUrl != null) {
            initPlayer()
            play(streamUrl!!)
        } else {
            Toast.makeText(this, "No stream URL found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            streamUrl = getStreamUrl(it)
            streamUrl?.let { url ->
                if (player == null) initPlayer()
                play(url)
            }
        }
    }

    private fun getStreamUrl(intent: Intent): String? {
        // Check for iplinks:// scheme
        if (intent.scheme == "iplinks") {
            return intent.dataString?.substringAfter("iplinks://")
        }
        
        // Check for VIEW intent with URI
        intent.data?.toString()?.let { url ->
            if (url.startsWith("http://") || url.startsWith("https://") || 
                url.startsWith("rtmp://") || url.startsWith("rtmps://")) {
                return url
            }
        }
        
        // Check for SEND intent with text
        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                return sharedText.trim()
            }
        }
        
        // Check extras
        return intent.getStringExtra("stream_url") 
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra("video_url")
    }

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
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
                    override fun onIsLoadingChanged(isLoading: Boolean) {
                        progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                    
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            ExoPlayer.STATE_READY -> progressBar?.visibility = View.GONE
                            ExoPlayer.STATE_BUFFERING -> progressBar?.visibility = View.VISIBLE
                            ExoPlayer.STATE_ENDED -> {
                                // For live streams, restart
                                streamUrl?.let { handler.postDelayed({ play(it) }, 1000) }
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        // Retry on error
                        handler.postDelayed({ streamUrl?.let { play(it) } }, 2000)
                    }
                })
            }
    }

    private fun play(url: String) {
        try {
            player?.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            player?.prepare()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && streamUrl != null) {
            initPlayer()
            play(streamUrl!!)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
