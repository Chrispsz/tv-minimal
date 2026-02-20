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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var currentUrl: String? = null

    companion object {
        private var instance: PlayerActivity? = null
        
        fun finishCurrent() {
            instance?.finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Finish any previous instance
        instance?.let { 
            it.player?.release()
            it.finish()
        }
        instance = this
        
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
        
        setContentView(rootLayout)
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Stop current and start new
        player?.stop()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = getUrlFromIntent(intent)
        
        if (url != null && url != currentUrl) {
            currentUrl = url
            if (player == null) initPlayer()
            play(url)
        }
    }

    private fun getUrlFromIntent(intent: Intent): String? {
        intent.data?.toString()?.let { url ->
            if (url.startsWith("http") || url.startsWith("rtmp")) return url
        }
        
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.let { text ->
                if (text.startsWith("http") || text.startsWith("rtmp")) return text
            }
        }
        
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
        hideSystemUI()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        // When user leaves, finish this activity
        // Next stream will start fresh
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        if (instance == this) instance = null
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
