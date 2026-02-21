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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var retryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = getUrlFromIntent(intent) ?: run { finish(); return }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val surfaceView = SurfaceView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        setContentView(FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(surfaceView) })

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000, 25000, 2000, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().apply {
            setVideoSurfaceView(surfaceView)
            playWhenReady = true
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (retryCount++ < 3) { stop(); prepare() }
                }
            })
            play(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        getUrlFromIntent(intent)?.let { retryCount = 0; player?.stop(); play(it) }
    }

    private fun getUrlFromIntent(intent: Intent): String? {
        intent.data?.toString()?.takeIf { it.startsWith("http") }?.let { return it }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
        return intent.getStringExtra("stream_url") ?: intent.getStringExtra("url")
    }

    private fun play(url: String) {
        player?.setMediaItem(MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(3000L)
                .setMinPlaybackSpeed(0.97f)
                .setMaxPlaybackSpeed(1.03f)
                .build())
            .build())?.prepare()
    }

    override fun onResume() { super.onResume(); player?.play() }
    override fun onPause() { super.onPause(); player?.pause() }
    override fun onStop() { super.onStop(); finish() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }
}
