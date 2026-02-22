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

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var retryCount = 0
    private var currentUrl: String? = null
    private var audioDiscontinuityCount = 0
    private var decodeErrorCount = 0
    private var networkErrorCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Memory monitoring
    private var memoryCheckRunnable: Runnable? = null
    private var sessionStartTime: Long = 0
    private var lastMemoryCheck: Long = 0

    companion object {
        private const val MIN_BUFFER_MS = 15000
        private const val MAX_BUFFER_MS = 50000
        private const val BUFFER_FOR_PLAYBACK_MS = 2500
        private const val BUFFER_AFTER_REBUFFER_MS = 5000
        
        private const val MAX_RETRIES = 3
        private const val MAX_AUDIO_DISCONTINUITY = 5
        
        // ========== MELHORIA 1: Video Decode ==========
        private const val MAX_DECODE_ERRORS = 5
        private const val DECODE_RECOVERY_DELAY_MS = 500L
        
        // ========== MELHORIA 2: Network Timeout ==========
        private const val CONNECT_TIMEOUT_MS = 15000      // 15s para conectar
        private const val READ_TIMEOUT_MS = 20000         // 20s para ler dados
        private const val MAX_NETWORK_ERRORS = 3
        
        // ========== MELHORIA 3: Memory ==========
        private const val MEMORY_CHECK_INTERVAL_MS = 60000L  // Checa a cada 1 min
        private const val MEMORY_WARNING_RATIO = 0.85        // 85% da heap usada
        private const val SESSION_RESTART_INTERVAL_MS = 14400000L // 4 horas - restart preventivo
        
        private const val TAG = "PlayerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent)
        if (url == null) {
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
        startMemoryMonitoring()
        play(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = getUrlFromIntent(intent) ?: return
        
        // Reset all counters for new stream
        currentUrl = url
        retryCount = 0
        audioDiscontinuityCount = 0
        decodeErrorCount = 0
        networkErrorCount = 0
        sessionStartTime = System.currentTimeMillis()
        
        player?.stop()
        play(url)
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

        // ========== MELHORIA 2: Network Timeout ==========
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("tv-minimal/1.0")

        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            httpDataSourceFactory
        )

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
                        Log.d(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                    }
                })
            }
    }

    // ========== HANDLER CENTRALIZADO DE ERROS ==========
    private fun handleError(error: PlaybackException) {
        Log.e(TAG, "Player error: ${error.message}", error)
        
        when (val cause = error.cause) {
            // Audio timestamp discontinuity
            is AudioSink.UnexpectedDiscontinuityException -> {
                handleAudioDiscontinuity()
            }
            
            // Behind live window (HLS live streams)
            is BehindLiveWindowException -> {
                handleBehindLiveWindow()
            }
            
            // ========== MELHORIA 1: Video Decode Errors ==========
            is MediaCodecDecoderException -> {
                handleDecodeError(cause)
            }
            
            // Generic decoder errors
            is androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException -> {
                handleDecodeError(error)
            }
            
            // ========== MELHORIA 2: Network Errors ==========
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.io.IOException -> {
                handleNetworkError(cause)
            }
            
            // Check for network-related error codes
            else -> {
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                        handleNetworkError(error)
                    }
                    else -> {
                        // Unknown error - try retry
                        handleGenericError(error)
                    }
                }
            }
        }
    }
    
    // ========== AUDIO DISCONTINUITY ==========
    private fun handleAudioDiscontinuity() {
        audioDiscontinuityCount++
        Log.w(TAG, "Audio discontinuity #$audioDiscontinuityCount")
        
        if (audioDiscontinuityCount >= MAX_AUDIO_DISCONTINUITY) {
            Log.w(TAG, "Forcing audio resync due to repeated discontinuities")
            audioDiscontinuityCount = 0
            
            mainHandler.post {
                player?.currentPosition?.let { pos ->
                    player?.seekTo(pos + 100)
                }
            }
        }
    }
    
    // ========== BEHIND LIVE WINDOW ==========
    private fun handleBehindLiveWindow() {
        Log.w(TAG, "BehindLiveWindowException - returning to live edge")
        mainHandler.post {
            player?.apply {
                seekToDefaultPosition()
                playWhenReady = true
            }
        }
    }
    
    // ========== MELHORIA 1: VIDEO DECODE ERRORS ==========
    private fun handleDecodeError(error: Throwable) {
        decodeErrorCount++
        Log.e(TAG, "Decode error #$decodeErrorCount: ${error.message}")
        
        if (decodeErrorCount >= MAX_DECODE_ERRORS) {
            Log.e(TAG, "Max decode errors reached - forcing player restart")
            decodeErrorCount = 0
            restartPlayer()
            return
        }
        
        // Try soft recovery: skip current frame
        mainHandler.postDelayed({
            player?.currentPosition?.let { pos ->
                Log.d(TAG, "Decode recovery: seeking forward 200ms")
                player?.seekTo(pos + 200)
            }
        }, DECODE_RECOVERY_DELAY_MS)
    }
    
    // ========== MELHORIA 2: NETWORK ERRORS ==========
    private fun handleNetworkError(error: Throwable) {
        networkErrorCount++
        Log.w(TAG, "Network error #$networkErrorCount: ${error.javaClass.simpleName} - ${error.message}")
        
        if (networkErrorCount >= MAX_NETWORK_ERRORS) {
            Log.e(TAG, "Max network errors reached - forcing player restart")
            networkErrorCount = 0
            restartPlayer()
            return
        }
        
        // Exponential backoff retry
        val delayMs = (2000L * (1 shl (networkErrorCount - 1))).coerceAtMost(10000)
        Log.d(TAG, "Network retry in ${delayMs}ms")
        
        mainHandler.postDelayed({
            if (currentUrl != null) {
                player?.prepare()
            }
        }, delayMs)
    }
    
    // ========== GENERIC ERROR ==========
    private fun handleGenericError(error: PlaybackException) {
        if (retryCount < MAX_RETRIES && currentUrl != null) {
            retryCount++
            Log.d(TAG, "Generic retry ($retryCount/$MAX_RETRIES)")
            restartPlayer()
        } else {
            Log.e(TAG, "Max retries reached - cannot recover")
        }
    }
    
    // ========== PLAYER RESTART ==========
    private fun restartPlayer() {
        mainHandler.post {
            try {
                player?.stop()
                currentUrl?.let { url ->
                    // Small delay before restart
                    mainHandler.postDelayed({
                        play(url)
                    }, 500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during player restart", e)
            }
        }
    }

    private fun play(url: String) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
        }
    }

    // ========== MELHORIA 3: MEMORY MONITORING ==========
    private fun startMemoryMonitoring() {
        memoryCheckRunnable = object : Runnable {
            override fun run() {
                checkMemory()
                
                // Check session duration for preventive restart
                val sessionDuration = System.currentTimeMillis() - sessionStartTime
                if (sessionDuration > SESSION_RESTART_INTERVAL_MS) {
                    Log.i(TAG, "Session restart triggered (4h elapsed) - preventing memory buildup")
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
        
        val availableMB = memoryInfo.availMem / (1024 * 1024)
        val usedMB = usedMemory / (1024 * 1024)
        val maxMB = maxMemory / (1024 * 1024)
        
        Log.d(TAG, "Memory: ${usedMB}MB / ${maxMB}MB (${(memoryUsageRatio * 100).toInt()}%) | System available: ${availableMB}MB")
        
        // ========== MELHORIA 3: Memory Warning ==========
        if (memoryUsageRatio > MEMORY_WARNING_RATIO) {
            Log.w(TAG, "High memory usage detected (${(memoryUsageRatio * 100).toInt()}%) - forcing GC and player restart")
            
            // Request GC
            System.gc()
            
            // Restart player to clear buffers
            restartPlayer()
            
            // Reset session timer
            sessionStartTime = System.currentTimeMillis()
        }
        
        // Check if system is low on memory
        if (memoryInfo.lowMemory) {
            Log.w(TAG, "System low memory warning - clearing resources")
            System.gc()
        }
        
        lastMemoryCheck = System.currentTimeMillis()
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
        
        // Clean up memory monitoring
        memoryCheckRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        
        mainHandler.removeCallbacksAndMessages(null)
        
        // Release player resources
        player?.apply {
            stop()
            release()
        }
        
        player = null
        surfaceView = null
        currentUrl = null
        
        // Request GC on destroy
        System.gc()
        
        Log.i(TAG, "PlayerActivity destroyed - all resources cleaned")
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
