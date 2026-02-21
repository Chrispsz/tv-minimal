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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var retryCount = 0

    companion object {
        private const val MIN_BUFFER_MS = 10000
        private const val MAX_BUFFER_MS = 25000
        private const val BUFFER_FOR_PLAYBACK_MS = 2000
        private const val MAX_RETRIES = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent) ?: run { finish(); return }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(surfaceView)
        setContentView(rootLayout)

        initPlayer(surfaceView)
        play(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        getUrlFromIntent(intent)?.let { url ->
            retryCount = 0
            player?.stop()
            play(url)
        }
    }

    private fun getUrlFromIntent(intent: Intent): String? {
        // Intent data (http/rtmp URL)
        intent.data?.toString()?.takeIf { it.startsWith("http") || it.startsWith("rtmp") }?.let { return it }
        
        // Query parameter (iplinks://play?stream_url=...)
        intent.data?.getQueryParameter("stream_url")?.takeIf { it.startsWith("http") }?.let { return it }

        // ACTION_SEND (shared text)
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                ?.takeIf { it.startsWith("http") }?.let { return it }
        }

        // Extras
        return intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
    }

    private fun initPlayer(surfaceView: SurfaceView) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                setWakeMode(C.WAKE_MODE_NETWORK)

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val currentMediaItem = currentMediaItem ?: return
                        if (retryCount < MAX_RETRIES) {
                            retryCount++
                            stop()
                            setMediaItem(currentMediaItem)
                            prepare()
                        }
                    }
                })
            }
    }

    private fun play(url: String) {
        player?.apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.parse(url))
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(3000L)
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.03f)
                            .build()
                    )
                    .build()
            )
            prepare()
        }
    }

    override fun onResume() { super.onResume(); player?.play() }
    override fun onPause() { super.onPause(); player?.pause() }
    override fun onStop() { super.onStop(); finish() }
    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
