package com.iplinks.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.URLUtil
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
 * - Exponential Backoff para retry
 * - URLUtil para validação robusta
 * - Value class para type-safety sem overhead
 */
class PlayerActivity : Activity(), LifecycleOwner {

    // ==================== VALUE CLASS (Zero overhead) ====================
    @JvmInline
    value class ValidatedUrl(val url: String) {
        init {
            require(url.startsWith("http")) { "URL must start with http/https" }
        }
    }

    // ==================== SEALED CLASS (Exhaustive States) ====================
    private sealed class PlayerState {
        data object Idle : PlayerState()
        data object Stopped : PlayerState()
        data class Loading(val url: ValidatedUrl) : PlayerState()
        data class Playing(val url: ValidatedUrl) : PlayerState()
        data class Error(
            val exception: PlaybackException,
            val url: ValidatedUrl,
            val isRetryable: Boolean
        ) : PlayerState()
    }

    // ==================== STATE ====================
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var player: ExoPlayer? = null
    private var playerListener: PlayerEventListener? = null
    private var retryJob: Job? = null
    private var monitorJob: Job? = null
    private var retryCount = 0
    private var currentState: PlayerState = PlayerState.Idle

    // ==================== COMPANION ====================
    companion object {
        private const val MIN_BUFFER_MS = 10_000
        private const val MAX_BUFFER_MS = 25_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_000
        private const val MAX_RETRIES = 3
        private const val MONITOR_INTERVAL_MS = 5_000L
        private const val BASE_RETRY_DELAY_MS = 1_000L
        
        private val NON_RETRYABLE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )
    }

    // ==================== LIFECYCLE ====================
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        val url = resolveUrl(intent)?.let { validateUrl(it) }
        if (url == null) {
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
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        releaseAllResources()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = resolveUrl(intent)?.let { validateUrl(it) }
        if (url != null) {
            cancelRetry()
            retryCount = 0
            player?.stop()
            play(url)
        }
    }

    // ==================== URL RESOLUTION & VALIDATION ====================
    private fun resolveUrl(intent: Intent): String? {
        return intent.data?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
    }

    private fun validateUrl(rawUrl: String): ValidatedUrl? {
        val trimmed = rawUrl.trim()
        if (!URLUtil.isNetworkUrl(trimmed)) return null
        
        return try {
            val uri = Uri.parse(trimmed)
            if (uri.host.isNullOrBlank()) null else ValidatedUrl(trimmed)
        } catch (e: Exception) {
            null
        }
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }

        playerListener = PlayerEventListener()

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

    private fun play(url: ValidatedUrl) {
        currentState = PlayerState.Loading(url)
        
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url.url))
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

    // ==================== RETRY LOGIC ====================
    private fun scheduleRetry(url: ValidatedUrl) {
        if (retryCount >= MAX_RETRIES) return
        
        val delayMs = BASE_RETRY_DELAY_MS * (1 shl (retryCount - 1))
        
        retryJob = lifecycleScope.launch(Dispatchers.Main) {
            if (!isActive) return@launch
            delay(delayMs)
            if (!isActive) return@launch
            player?.apply {
                stop()
                play(url)
            }
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun isRetryableError(error: PlaybackException): Boolean {
        return error.errorCode !in NON_RETRYABLE_ERROR_CODES
    }

    // ==================== MONITORING ====================
    private fun startMonitoring() {
        monitorJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    // ==================== CLEANUP ====================
    private fun releaseAllResources() {
        // 1. Cancel coroutines
        cancelRetry()
        monitorJob?.cancel()
        monitorJob = null

        // 2. Release player - IMPORTANT: remove listener first!
        player?.apply {
            playerListener?.let { removeListener(it) }
            setVideoSurfaceView(null)
            stop()
            release()
        }
        player = null
        playerListener = null

        // 3. Clear flags
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 4. Reset state
        currentState = PlayerState.Idle
        retryCount = 0
    }

    // ==================== EVENT LISTENER ====================
    private inner class PlayerEventListener : androidx.media3.common.Player.Listener {
        
        override fun onPlayerError(error: PlaybackException) {
            val url = when (val state = currentState) {
                is PlayerState.Loading -> state.url
                is PlayerState.Playing -> state.url
                else -> return
            }

            val retryable = isRetryableError(error)
            currentState = PlayerState.Error(error, url, retryable)

            if (retryable && retryCount < MAX_RETRIES) {
                retryCount++
                scheduleRetry(url)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                val url = (currentState as? PlayerState.Loading)?.url
                if (url != null) {
                    currentState = PlayerState.Playing(url)
                    retryCount = 0
                }
            }
        }
    }
}
