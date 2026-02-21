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
        private const val MIN_BUFFER_MS = 10000      // 10s mínimo
        private const val MAX_BUFFER_MS = 25000      // 25s máximo
        private const val BUFFER_FOR_PLAYBACK_MS = 2000
        private const val MAX_RETRIES = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent) ?: run { finish(); return }

        setupWindow()
        setupUI()
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
        // URL direta
        intent.data?.toString()?.takeIf { it.startsWith("http") }?.let { return it }

        // Texto compartilhado
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                ?.takeIf { it.startsWith("http") }?.let { return it }
        }

        // Extras
        return intent.getStringExtra("stream_url") ?: intent.getStringExtra("url")
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupUI() {
        val surfaceView = SurfaceView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        setContentView(FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(surfaceView) })
        initPlayer(surfaceView)
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
                        currentMediaItem?.let {
                            if (retryCount < MAX_RETRIES) {
                                retryCount++
                                stop()
                                setMediaItem(it)
                                prepare()
                            }
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
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }
}
