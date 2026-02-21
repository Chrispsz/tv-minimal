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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PlayerActivity - HLS Player otimizado para Android TV
 * 
 * Otimizações implementadas:
 * - lifecycleScope para coroutines automáticas
 * - Sealed class para estados do player
 * - Limpeza completa de recursos no onDestroy
 * - LeakCanary para detecção de leaks em debug
 */
class PlayerActivity : Activity(), LifecycleOwner {

    // ==================== STATE ====================
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var player: ExoPlayer? = null
    private var playerListener: PlayerEventListener? = null
    private var monitorJob: Job? = null
    private var retryCount = 0
    
    // Estado atual do player usando sealed class
    private sealed class PlayerState {
        data object Idle : PlayerState()
        data class Loading(val url: String) : PlayerState()
        data class Playing(val url: String) : PlayerState()
        data class Error(val exception: PlaybackException, val url: String) : PlayerState()
    }
    private var currentState: PlayerState = PlayerState.Idle

    // ==================== COMPANION ====================
    companion object {
        private const val MIN_BUFFER_MS = 10_000      // 10s - buffer mínimo
        private const val MAX_BUFFER_MS = 25_000      // 25s - buffer máximo  
        private const val BUFFER_FOR_PLAYBACK_MS = 2_000
        private const val MAX_RETRIES = 3
        private const val MONITOR_INTERVAL_MS = 5_000L
    }

    // ==================== LIFECYCLE ====================
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        val url = resolveUrl(intent)?.trim()
        if (url == null || !url.startsWith("http")) {
            finish()
            return
        }

        setupWindow()
        setupUI()
        startMonitoring()
        play(url)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // Em TV, parar a activity é comum - não forçar finish
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        releaseAllResources()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = resolveUrl(intent)?.trim()
        if (url != null && url.startsWith("http")) {
            retryCount = 0
            player?.stop()
            play(url)
        }
    }

    // ==================== URL RESOLUTION ====================
    private fun resolveUrl(intent: Intent): String? {
        // Prioridade: data URI > ACTION_SEND > extras
        return intent.data?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
    }

    // ==================== SETUP ====================
    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupUI() {
        val surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        val container = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(surfaceView)
        }
        
        setContentView(container)
        initializePlayer(surfaceView)
    }

    // ==================== PLAYER ====================
    private fun initializePlayer(surfaceView: SurfaceView) {
        // LoadControl otimizado para streaming
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // TrackSelector para melhor qualidade
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
            )
        }

        // Listener como classe separada para melhor controle
        playerListener = PlayerEventListener()

        // ExoPlayer instance
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                setWakeMode(C.WAKE_MODE_NETWORK)
                playerListener?.let { addListener(it) }
            }
    }

    private fun play(url: String) {
        currentState = PlayerState.Loading(url)
        
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3000L)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
        }
    }

    // ==================== MONITORING ====================
    private fun startMonitoring() {
        monitorJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                // Log do estado atual para debug
                // Em produção, isso seria removido ou condicional
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    // ==================== CLEANUP ====================
    private fun releaseAllResources() {
        // 1. Cancelar coroutines
        monitorJob?.cancel()
        monitorJob = null

        // 2. Liberar player
        player?.apply {
            playerListener?.let { removeListener(it) }
            setVideoSurfaceView(null)
            stop()
            release()
        }
        player = null
        playerListener = null

        // 3. Limpar flags de window
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 4. Reset state
        currentState = PlayerState.Idle
        retryCount = 0
    }

    // ==================== EVENT LISTENER ====================
    private inner class PlayerEventListener : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val url = (currentState as? PlayerState.Loading)?.url
                ?: (currentState as? PlayerState.Playing)?.url
                ?: return

            currentState = PlayerState.Error(error, url)

            if (retryCount < MAX_RETRIES) {
                retryCount++
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(1000L) // Delay antes de retry
                    player?.apply {
                        stop()
                        play(url)
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                val url = (currentState as? PlayerState.Loading)?.url
                if (url != null) {
                    currentState = PlayerState.Playing(url)
                    retryCount = 0 // Reset retry count on successful playback
                }
            }
        }
    }
}
