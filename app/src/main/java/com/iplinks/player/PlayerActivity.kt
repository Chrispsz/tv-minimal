package com.iplinks.player

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
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
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.BehindLiveWindowException
import java.util.concurrent.TimeUnit

class PlayerActivity : Activity() {

    // ============================================================
    // PLAYER STATE
    // ============================================================
    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var retryCount = 0
    private var currentUrl: String? = null
    
    // Error counters (reset on successful playback)
    private var audioDiscontinuityCount = 0
    private var decodeErrorCount = 0
    private var networkErrorCount = 0
    
    // Stall detection
    private var lastPlaybackPosition: Long = 0
    private var stallDetectionCount = 0
    private var isCurrentlyPlaying = false
    
    // Session stats
    private var sessionStartTime: Long = 0
    private var totalErrors: Int = 0
    private var totalRecoveries: Int = 0
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Memory monitoring
    private var memoryCheckRunnable: Runnable? = null
    private var stallCheckRunnable: Runnable? = null
    private var counterResetRunnable: Runnable? = null

    // ============================================================
    // CONFIGURATION - Sport Mode Pro
    // ============================================================
    companion object {
        // Buffer - otimizado para live
        private const val MIN_BUFFER_MS = 10000
        private const val MAX_BUFFER_MS = 30000
        private const val BUFFER_FOR_PLAYBACK_MS = 1500
        private const val BUFFER_AFTER_REBUFFER_MS = 3000
        
        // Error limits
        private const val MAX_RETRIES = 3
        private const val MAX_AUDIO_DISCONTINUITY = 5
        private const val MAX_DECODE_ERRORS = 5
        private const val MAX_NETWORK_ERRORS = 3
        private const val MAX_STALL_DETECTIONS = 3
        
        // Recovery delays
        private const val DECODE_RECOVERY_DELAY_MS = 300L
        private const val STALL_CHECK_INTERVAL_MS = 2000L
        private const val COUNTER_RESET_DELAY_MS = 30000L  // 30s sem erro = reset
        
        // Network timeouts
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 15000
        
        // Memory
        private const val MEMORY_CHECK_INTERVAL_MS = 30000L
        private const val MEMORY_WARNING_RATIO = 0.85
        private const val SESSION_RESTART_INTERVAL_MS = 9000000L  // 2.5h
        
        private const val TAG = "PlayerActivity"
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent)
        if (url == null) {
            Log.e(TAG, "No URL provided, finishing")
            finish()
            return
        }

        currentUrl = url
        sessionStartTime = System.currentTimeMillis()

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
        startMonitoring()
        play(url)
        
        Log.i(TAG, "Session started - URL: ${url.take(50)}...")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = getUrlFromIntent(intent) ?: return
        
        // Log previous session stats
        logSessionStats()
        
        // Full reset for new stream
        currentUrl = url
        resetAllCounters()
        sessionStartTime = System.currentTimeMillis()
        
        player?.stop()
        play(url)
        
        Log.i(TAG, "New stream started - URL: ${url.take(50)}...")
    }

    private fun getUrlFromIntent(intent: Intent): String? {
        intent.data?.toString()?.takeIf {
            it.startsWith("http") || it.startsWith("rtmp")
        }?.let { return it }
        
        intent.data?.getQueryParameter("stream_url")?.takeIf {
            it.startsWith("http") || it.startsWith("rtmp")
        }?.let { return it }

        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf {
                it.startsWith("http") || it.startsWith("rtmp")
            }?.let { return it }
        }

        return intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra("video_url")
    }

    // ============================================================
    // PLAYER INITIALIZATION
    // ============================================================
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

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("tv-minimal/2.0")

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(this))
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                setAudioAttributes(audioAttributes, true)

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        handleError(error)
                    }
                    
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.d(TAG, "Video: ${videoSize.width}x${videoSize.height} @ ${videoSize.pixelWidthHeightRatio}x")
                    }
                    
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isCurrentlyPlaying = playing
                        if (playing) {
                            // Reset stall detection when playing
                            stallDetectionCount = 0
                            scheduleCounterReset()
                        }
                    }
                    
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            ExoPlayer.STATE_READY -> {
                                Log.i(TAG, "Playback ready - ${getPlaybackStats()}")
                            }
                            ExoPlayer.STATE_BUFFERING -> {
                                Log.d(TAG, "Buffering...")
                            }
                            ExoPlayer.STATE_ENDED -> {
                                Log.i(TAG, "Playback ended")
                            }
                        }
                    }
                })
            }
    }

    // ============================================================
    // ERROR HANDLER - Centralizado
    // ============================================================
    private fun handleError(error: PlaybackException) {
        totalErrors++
        Log.e(TAG, "Player error #${totalErrors}: ${error.message}")
        
        // Cancel pending counter reset
        cancelCounterReset()
        
        when (val cause = error.cause) {
            is AudioSink.UnexpectedDiscontinuityException -> {
                handleAudioDiscontinuity()
            }
            
            is BehindLiveWindowException -> {
                handleBehindLiveWindow()
            }
            
            is MediaCodecDecoderException -> {
                handleDecodeError(cause)
            }
            
            is androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException -> {
                handleDecodeError(error)
            }
            
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.io.IOException -> {
                handleNetworkError(cause)
            }
            
            else -> {
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                        handleNetworkError(error)
                    }
                    else -> handleGenericError(error)
                }
            }
        }
    }

    // ============================================================
    // ERROR HANDLERS
    // ============================================================
    private fun handleAudioDiscontinuity() {
        audioDiscontinuityCount++
        Log.w(TAG, "Audio discontinuity #$audioDiscontinuityCount/$MAX_AUDIO_DISCONTINUITY")
        
        if (audioDiscontinuityCount >= MAX_AUDIO_DISCONTINUITY) {
            Log.w(TAG, "→ Forcing audio resync")
            audioDiscontinuityCount = 0
            totalRecoveries++
            
            mainHandler.post {
                player?.currentPosition?.let { pos ->
                    player?.seekTo(pos + 100)
                }
            }
        }
    }

    private fun handleBehindLiveWindow() {
        Log.w(TAG, "→ Behind live window, returning to live edge")
        totalRecoveries++
        
        mainHandler.post {
            player?.apply {
                seekToDefaultPosition()
                playWhenReady = true
            }
        }
    }

    private fun handleDecodeError(error: Throwable) {
        decodeErrorCount++
        Log.e(TAG, "Decode error #$decodeErrorCount/$MAX_DECODE_ERRORS: ${error.message}")
        
        if (decodeErrorCount >= MAX_DECODE_ERRORS) {
            Log.e(TAG, "→ Max decode errors, restarting player")
            decodeErrorCount = 0
            totalRecoveries++
            restartPlayer()
            return
        }
        
        mainHandler.postDelayed({
            player?.currentPosition?.let { pos ->
                Log.d(TAG, "→ Decode recovery: seek +200ms")
                player?.seekTo(pos + 200)
            }
        }, DECODE_RECOVERY_DELAY_MS)
    }

    private fun handleNetworkError(error: Throwable) {
        networkErrorCount++
        Log.w(TAG, "Network error #$networkErrorCount/$MAX_NETWORK_ERRORS: ${error.javaClass.simpleName}")
        
        if (networkErrorCount >= MAX_NETWORK_ERRORS) {
            Log.e(TAG, "→ Max network errors, restarting player")
            networkErrorCount = 0
            totalRecoveries++
            restartPlayer()
            return
        }
        
        // Exponential backoff: 2s, 4s, 8s
        val delayMs = (2000L * (1 shl (networkErrorCount - 1))).coerceAtMost(8000)
        Log.d(TAG, "→ Network retry in ${delayMs}ms")
        
        mainHandler.postDelayed({
            if (currentUrl != null) {
                player?.prepare()
            }
        }, delayMs)
    }

    private fun handleGenericError(error: PlaybackException) {
        if (retryCount < MAX_RETRIES && currentUrl != null) {
            retryCount++
            Log.d(TAG, "Generic retry $retryCount/$MAX_RETRIES")
            totalRecoveries++
            restartPlayer()
        } else {
            Log.e(TAG, "→ Max retries reached, cannot recover")
        }
    }

    // ============================================================
    // STALL DETECTION - Novo!
    // ============================================================
    private fun startStallDetection() {
        stallCheckRunnable = object : Runnable {
            override fun run() {
                if (!isCurrentlyPlaying) {
                    mainHandler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
                    return
                }
                
                val currentPos = player?.currentPosition ?: 0
                
                if (lastPlaybackPosition == currentPos && currentPos > 0) {
                    stallDetectionCount++
                    Log.w(TAG, "Stall detected #$stallDetectionCount/$MAX_STALL_DETECTIONS (pos=$currentPos)")
                    
                    if (stallDetectionCount >= MAX_STALL_DETECTIONS) {
                        Log.w(TAG, "→ Forcing recovery from stall")
                        stallDetectionCount = 0
                        totalRecoveries++
                        
                        // Try to recover
                        player?.apply {
                            pause()
                            seekTo(currentPos + 500)
                            play()
                        }
                    }
                } else {
                    // Position changed, reset stall count
                    if (stallDetectionCount > 0) {
                        Log.d(TAG, "Stall recovered naturally")
                    }
                    stallDetectionCount = 0
                }
                
                lastPlaybackPosition = currentPos
                mainHandler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
            }
        }
        
        mainHandler.postDelayed(stallCheckRunnable!!, STALL_CHECK_INTERVAL_MS)
    }

    // ============================================================
    // COUNTER RESET - Novo!
    // ============================================================
    private fun scheduleCounterReset() {
        cancelCounterReset()
        
        counterResetRunnable = Runnable {
            if (isCurrentlyPlaying && (audioDiscontinuityCount > 0 || decodeErrorCount > 0 || networkErrorCount > 0)) {
                Log.d(TAG, "Resetting error counters (stable playback)")
                audioDiscontinuityCount = 0
                decodeErrorCount = 0
                networkErrorCount = 0
            }
        }
        
        mainHandler.postDelayed(counterResetRunnable!!, COUNTER_RESET_DELAY_MS)
    }
    
    private fun cancelCounterReset() {
        counterResetRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    // ============================================================
    // PLAYER RESTART
    // ============================================================
    private fun restartPlayer() {
        mainHandler.post {
            try {
                player?.stop()
                currentUrl?.let { url ->
                    mainHandler.postDelayed({
                        play(url)
                    }, 500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during restart: ${e.message}")
            }
        }
    }

    private fun play(url: String) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
        }
    }

    // ============================================================
    // MEMORY MONITORING
    // ============================================================
    private fun startMonitoring() {
        startMemoryMonitoring()
        startStallDetection()
    }
    
    private fun startMemoryMonitoring() {
        memoryCheckRunnable = object : Runnable {
            override fun run() {
                checkMemory()
                
                val sessionDuration = System.currentTimeMillis() - sessionStartTime
                if (sessionDuration > SESSION_RESTART_INTERVAL_MS) {
                    Log.i(TAG, "Session restart (${sessionDuration / 60000}min elapsed)")
                    totalRecoveries++
                    restartPlayer()
                    sessionStartTime = System.currentTimeMillis()
                }
                
                mainHandler.postDelayed(this, MEMORY_CHECK_INTERVAL_MS)
            }
        }
        
        mainHandler.postDelayed(memoryCheckRunnable!!, MEMORY_CHECK_INTERVAL_MS)
    }

    private fun checkMemory() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsageRatio = usedMemory.toDouble() / maxMemory.toDouble()
        
        val usedMB = usedMemory / (1024 * 1024)
        val maxMB = maxMemory / (1024 * 1024)
        val availMB = memoryInfo.availMem / (1024 * 1024)
        
        // Only log if significant
        if (memoryUsageRatio > 0.7) {
            Log.d(TAG, "Memory: ${usedMB}MB/${maxMB}MB (${(memoryUsageRatio * 100).toInt()}%) | System: ${availMB}MB")
        }
        
        if (memoryUsageRatio > MEMORY_WARNING_RATIO) {
            Log.w(TAG, "High memory (${(memoryUsageRatio * 100).toInt()}%) → GC + restart")
            System.gc()
            totalRecoveries++
            restartPlayer()
            sessionStartTime = System.currentTimeMillis()
        }
        
        if (memoryInfo.lowMemory) {
            Log.w(TAG, "System low memory warning")
            System.gc()
        }
    }

    // ============================================================
    // SESSION STATS
    // ============================================================
    private fun getPlaybackStats(): String {
        val duration = System.currentTimeMillis() - sessionStartTime
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        return "uptime=${minutes}m errors=$totalErrors recoveries=$totalRecoveries"
    }
    
    private fun logSessionStats() {
        val duration = System.currentTimeMillis() - sessionStartTime
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        
        Log.i(TAG, "═══ SESSION STATS ═══")
        Log.i(TAG, "  Duration: ${minutes} minutes")
        Log.i(TAG, "  Errors: $totalErrors")
        Log.i(TAG, "  Recoveries: $totalRecoveries")
        Log.i(TAG, "═════════════════════")
    }
    
    private fun resetAllCounters() {
        retryCount = 0
        audioDiscontinuityCount = 0
        decodeErrorCount = 0
        networkErrorCount = 0
        stallDetectionCount = 0
        totalErrors = 0
        totalRecoveries = 0
    }

    // ============================================================
    // LIFECYCLE CALLBACKS
    // ============================================================
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
        
        // Log final stats
        logSessionStats()
        
        // Cancel all runnables
        memoryCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        stallCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        counterResetRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        
        // Release player
        player?.apply {
            stop()
            release()
        }
        
        player = null
        surfaceView = null
        currentUrl = null
        
        System.gc()
        
        Log.i(TAG, "PlayerActivity destroyed")
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
