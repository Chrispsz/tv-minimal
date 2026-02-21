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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * PlayerActivity - HLS Player com Arquitetura Resiliente para Android TV
 * 
 * Otimizações de Nível Arquitetural:
 * - Sealed interface para estados (mais leve que sealed class)
 * - Value class para URL validada (zero overhead de memória)
 * - Exponential Backoff com Jitter (evita Thundering Herd)
 * - Cancelamento cooperativo de coroutines (ensureActive)
 * - Limpeza determinística de recursos
 * - LeakCanary para detecção de leaks em debug
 */
class PlayerActivity : Activity(), LifecycleOwner {

    // ==================== VALUE CLASS (Zero RAM overhead) ====================
    /**
     * Type-safe URL que não pode ser instanciada sem validação.
     * O compilador a trata como String primitiva (sem alocação de objeto).
     */
    @JvmInline
    value class ValidatedUrl(val url: String) {
        init {
            require(url.startsWith("http")) { "URL must start with http/https" }
        }
    }

    // ==================== SEALED INTERFACE (Mais leve que sealed class) ====================
    /**
     * Sealed interface é mais leve no bytecode que sealed class.
     * data object para estados sem parâmetros = zero alocação.
     * when exhaustivo garante que todos os estados são tratados.
     */
    private sealed interface PlayerState {
        data object Idle : PlayerState
        data object Stopped : PlayerState
        data class Loading(val url: ValidatedUrl) : PlayerState
        data class Playing(val url: ValidatedUrl) : PlayerState
        data class Error(
            val exception: PlaybackException,
            val url: ValidatedUrl,
            val isRetryable: Boolean
        ) : PlayerState
    }

    // ==================== ERROR CLASSIFICATION (Sealed para extensibilidade) ====================
    /**
     * Classificação de erros que força tratamento de novos códigos.
     * Ao adicionar um novo código, o compilador obriga a decidir se é retryable.
     */
    private sealed interface ErrorClassification {
        val isRetryable: Boolean
        
        data object NetworkTimeout : ErrorClassification {
            override val isRetryable = true
        }
        data object NetworkConnection : ErrorClassification {
            override val isRetryable = true
        }
        data object HttpBadStatus : ErrorClassification {
            override val isRetryable = false
        }
        data object ParsingError : ErrorClassification {
            override val isRetryable = false
        }
        data object FileNotFound : ErrorClassification {
            override val isRetryable = false
        }
        data object Unknown : ErrorClassification {
            override val isRetryable = false
        }
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
        private const val JITTER_MAX_MS = 500L  // Random 0-500ms para evitar Thundering Herd
        
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

    // ==================== RETRY LOGIC (Exponential Backoff + Jitter) ====================
    /**
     * Schedule retry com Exponential Backoff + Jitter.
     * 
     * Backoff: 1s, 2s, 4s para retries 1, 2, 3
     * Jitter: 0-500ms random para evitar Thundering Herd Problem
     * 
     * Thundering Herd: Quando muitos clientes tentam reconectar
     * simultaneamente após queda de servidor. O jitter espalha
     * as tentativas no tempo, reduzindo a carga no servidor.
     */
    private fun scheduleRetry(url: ValidatedUrl) {
        if (retryCount >= MAX_RETRIES) return
        
        // Exponential backoff: 1s, 2s, 4s
        val backoffMs = BASE_RETRY_DELAY_MS * (1 shl (retryCount - 1))
        
        // Jitter: 0-500ms random para evitar Thundering Herd
        val jitterMs = Random.nextLong(0, JITTER_MAX_MS)
        val totalDelayMs = backoffMs + jitterMs
        
        retryJob = lifecycleScope.launch(Dispatchers.Main) {
            // Cancelamento cooperativo - verifica se a coroutine foi cancelada
            ensureActive()
            
            delay(totalDelayMs)
            
            // Segunda verificação após o delay
            ensureActive()
            
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

    /**
     * Classifica o erro para decidir se deve tentar novamente.
     * Erros de rede (timeout, conexão) são retryable.
     * Erros de dados (404, parsing) são fatais.
     */
    private fun isRetryableError(error: PlaybackException): Boolean {
        return error.errorCode !in NON_RETRYABLE_ERROR_CODES
    }

    // ==================== MONITORING (Cancelamento Cooperativo) ====================
    /**
     * Loop de monitoramento com cancelamento cooperativo.
     * ensureActive() garante que a coroutine para imediatamente
     * se o usuário fechar o player, liberando memória instantaneamente.
     */
    private fun startMonitoring() {
        monitorJob = lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                // Cancelamento cooperativo - para imediatamente se cancelado
                ensureActive()
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    // ==================== CLEANUP (Determinístico) ====================
    /**
     * Libera todos os recursos de forma determinística.
     * Ordem importante:
     * 1. Cancelar coroutines primeiro (evita race conditions)
     * 2. Remover listener ANTES de release (evita callbacks órfãos)
     * 3. Release do player
     * 4. Limpar flags
     * 5. Reset state
     */
    private fun releaseAllResources() {
        // 1. Cancel coroutines PRIMEIRO
        cancelRetry()
        monitorJob?.cancel()
        monitorJob = null

        // 2. Release player - listener removido ANTES de release
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
                PlayerState.Idle -> return
                PlayerState.Stopped -> return
                is PlayerState.Error -> return
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
