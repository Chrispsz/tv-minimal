package com.iplinks.player

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        getStreamUrl()?.let { initPlayer(); play(it) } ?: finish()
    }

    private fun getStreamUrl(): String? {
        intent.data?.toString()?.let { return it }
        if (intent.action == android.content.Intent.ACTION_SEND)
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { return it }
        return intent.getStringExtra("stream_url") ?: intent.getStringExtra("url")
    }

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(this)).build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Auto-retry para estabilidade em streams IPTV
                        prepare()
                    }
                })
            }
        
        playerView?.player = player
        playerView?.useController = true
    }

    private fun play(url: String) {
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.playWhenReady = true
    }

    override fun onStart() { super.onStart(); if (player == null) getStreamUrl()?.let { initPlayer(); play(it) } }
    override fun onResume() { super.onResume(); hideSystemUI(); player?.play() }
    override fun onPause() { super.onPause(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
