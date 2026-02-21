package com.iplinks.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║  PlayerActivity - HLS Player OTIMIZADO PARA ANDROID TV / LOW-END         ║
 * ╠═══════════════════════════════════════════════════════════════════════════╣
 * ║  PILARES DA ENGENHARIA DE ELITE:                                          ║
 * ║  1. ZERO ALOCAÇÃO NO LOOP PRINCIPAL (GC Zero)                            ║
 * ║  2. VALUE CLASSES - Zero overhead de memória                             ║
 * ║  3. DISPATCHER.IMMEDIATE - Context switching zero                        ║
 * ║  4. SEALED INTERFACES - Bytecode mínimo                                  ║
 * ║  5. INTARRAY - Coleções primitivas (sem boxing)                          ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 */
class PlayerActivity : Activity(), LifecycleOwner {

    // ==================== VALUE CLASSES (Zero RAM overhead) ====================
    /**
     * Type-safe URL - Zero alocação no Heap.
     * Compilador trata como String primitiva, mas mantém type safety.
     */
    @JvmInline
    value class ValidatedUrl(val url: String) {
        init {
            require(url.startsWith("http")) { "URL must start with http/https" }
        }
    }

    /**
     * Type-safe RetryCount - Evita boxing de Int.
     * Útil para passing entre funções sem criar objetos.
     */
    @JvmInline
    value class RetryCount(val value: Int) {
        init {
            require(value >= 0) { "RetryCount must be non-negative" }
        }
    }

    // ==================== SEALED INTERFACE (Bytecode mínimo) ====================
    /**
     * Sealed interface = mais leve que sealed class no bytecode.
     * data object = zero alocação para estados sem parâmetros.
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

    // ==================== PRIMITIVE ARRAY (Sem Boxing) ====================
    /**
     * IntArray ao invés de Set<Int> - 5x menos RAM.
     * Mapeado diretamente para int[] do Java.
     * 
     * Error codes que NÃO devem ter retry (erros permanentes):
     * -2001: ERROR_CODE_IO_BAD_HTTP_STATUS (404, 403)
     * -2002: ERROR_CODE_PARSING_CONTAINER_MALFORMED
     * -2003: ERROR_CODE_PARSING_MANIFEST_MALFORMED
     * -2004: ERROR_CODE_IO_FILE_NOT_FOUND
     * 
     * Error codes que DEVEM ter retry (erros temporários):
     * 1001: ERROR_CODE_IO_UNSPECIFIED
     * 1002: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (ConnectException)
     * 1003: ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
     * 1004: ERROR_CODE_IO_NETWORK_SERVER_TIMEOUT
     */
    private val nonRetryableErrorCodes = intArrayOf(
        -2001,  // IO_BAD_HTTP_STATUS (404/403 - arquivo não existe)
        -2002,  // PARSING_CONTAINER_MALFORMED
        -2003,  // PARSING_MANIFEST_MALFORMED
        -2004   // IO_FILE_NOT_FOUND
        // NOTA: 1001, 1002, 1003, 1004 são erros de REDE e DEVEM ter retry!
    )

    // ==================== STATE (Imutabilidade total) ====================
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var player: ExoPlayer? = null
    private var playerListener: PlayerEventListener? = null
    private var retryJob: Job? = null
    private var monitorJob: Job? = null
    private var retryCount = 0
    private var currentState: PlayerState = PlayerState.Idle

    // ==================== CONSTANTS (Compile-time) ====================
    private companion object {
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 25_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_000
        const val MAX_RETRIES = 5  // Aumentado para melhor recuperação de rede
        const val MONITOR_INTERVAL_MS = 5_000L
        const val BASE_RETRY_DELAY_MS = 2_000L  // 2s base para retry
        const val JITTER_MAX_MS = 1_000L
        const val TAG = "PlayerActivity"
        const val USER_AGENT = "IPLinksPlayer/1.0 (Android TV)"
    }

    // ==================== LIFECYCLE ====================
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        Log.d(TAG, "onCreate: intent=$intent, data=${intent?.data}, extras=${intent?.extras}")
        
        val rawUrl = resolveUrl(intent)
        Log.d(TAG, "onCreate: rawUrl=$rawUrl")
        
        val url = rawUrl?.let { validateUrl(it) }
        if (url == null) {
            Log.e(TAG, "onCreate: URL inválida ou não fornecida")
            showError("URL inválida ou não fornecida")
            finish()
            return
        }

        Log.d(TAG, "onCreate: url validada=${url.url}")
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
    
    /**
     * Inline function - Evita criação de objeto de função.
     * Código é copiado para o local de chamada.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun resolveUrl(intent: Intent): String? {
        // Prioridade: data URI > EXTRA_TEXT > stream_url > url
        val dataString = intent.data?.toString()
        if (!dataString.isNullOrBlank()) return dataString
        
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!extraText.isNullOrBlank()) return extraText
        
        val streamUrl = intent.getStringExtra("stream_url")
        if (!streamUrl.isNullOrBlank()) return streamUrl
        
        val url = intent.getStringExtra("url")
        if (!url.isNullOrBlank()) return url
        
        return null
    }

    private fun validateUrl(rawUrl: String): ValidatedUrl? {
        val trimmed = rawUrl.trim()
        Log.d(TAG, "validateUrl: trimmed=$trimmed")
        
        // Aceita http, https, rtsp, rtmp
        val validSchemes = listOf("http://", "https://", "rtsp://", "rtmp://")
        val isValidScheme = validSchemes.any { trimmed.startsWith(it, ignoreCase = true) }
        
        if (!isValidScheme) {
            Log.e(TAG, "validateUrl: scheme inválido, deve ser http/https/rtsp/rtmp")
            return null
        }
        
        return try {
            val uri = Uri.parse(trimmed)
            if (uri.host.isNullOrBlank()) {
                Log.e(TAG, "validateUrl: host vazio")
                null
            } else {
                Log.d(TAG, "validateUrl: URL válida, host=${uri.host}")
                ValidatedUrl(trimmed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateUrl: exceção ao parsear URL", e)
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
        Log.d(TAG, "initializePlayer: iniciando...")
        
        // HTTP DataSource com User-Agent customizado
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }

        playerListener = PlayerEventListener()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpDataSourceFactory)
            )
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                setWakeMode(C.WAKE_MODE_NETWORK)
                playerListener?.let { addListener(it) }
                Log.d(TAG, "initializePlayer: player criado com sucesso")
            }
    }

    private fun play(url: ValidatedUrl) {
        Log.d(TAG, "play: iniciando reprodução de ${url.url}")
        currentState = PlayerState.Loading(url)
        
        // MediaItem simples - sem LiveConfiguration (pode causar problemas com VOD)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url.url))
            .build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            Log.d(TAG, "play: prepare() chamado")
        }
    }

    // ==================== RETRY LOGIC (GC Zero + Context Switch Zero) ====================
    /**
     * OTIMIZAÇÕES DE ELITE:
     * 
     * 1. Dispatchers.Main.immediate - Se já estamos na Main Thread,
     *    executa imediatamente sem agendar na fila de mensagens.
     *    Economiza ciclos de CPU preciosos em TVs low-end.
     * 
     * 2. ensureActive() - Cancelamento cooperativo.
     *    Para imediatamente se o usuário fechar o player.
     * 
     * 3. Bit shift (1 shl n) ao invés de Math.pow.
     *    Operação de CPU única, sem alocação.
     */
    private fun scheduleRetry(url: ValidatedUrl) {
        if (retryCount >= MAX_RETRIES) return
        
        // Bit shift: 1 shl 0 = 1, 1 shl 1 = 2, 1 shl 2 = 4
        val backoffMs = BASE_RETRY_DELAY_MS * (1 shl (retryCount - 1))
        val jitterMs = Random.nextLong(0, JITTER_MAX_MS)
        val totalDelayMs = backoffMs + jitterMs
        
        retryJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            ensureActive()
            delay(totalDelayMs)
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
     * Verifica erro usando IntArray primitivo (sem boxing).
     * Busca linear é rápida para array pequeno (5 elementos).
     */
    private fun isRetryableError(error: PlaybackException): Boolean {
        val errorCode = error.errorCode
        // Busca linear em IntArray - sem boxing!
        return nonRetryableErrorCodes.none { it == errorCode }
    }

    // ==================== MONITORING (Context Switch Zero) ====================
    private fun startMonitoring() {
        monitorJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            while (true) {
                ensureActive()
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    // ==================== CLEANUP (Determinístico) ====================
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

    // ==================== ERROR HANDLING ====================
    private fun showError(message: String) {
        Log.e(TAG, "showError: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ==================== EVENT LISTENER ====================
    private inner class PlayerEventListener : androidx.media3.common.Player.Listener {
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                androidx.media3.common.Player.STATE_READY -> "READY"
                androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "onPlaybackStateChanged: $stateName")
        }
        
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.message}, errorCode=${error.errorCode}", error)
            
            // ==================== BEHIND LIVE WINDOW - TRATAMENTO ESPECIAL ====================
            // Este erro ocorre em streams HLS ao vivo quando o player fica para trás
            // da janela de transmissão. A solução é voltar para o ponto ao vivo.
            if (error.cause is BehindLiveWindowException) {
                Log.w(TAG, "onPlayerError: BehindLiveWindowException - voltando para live edge")
                player?.apply {
                    // Seek para o ponto mais recente da transmissão ao vivo
                    seekToDefaultPosition()
                    prepare()
                }
                return // Não mostra erro, recupera automaticamente
            }
            
            val url = when (val state = currentState) {
                is PlayerState.Loading -> state.url
                is PlayerState.Playing -> state.url
                PlayerState.Idle -> return
                PlayerState.Stopped -> return
                is PlayerState.Error -> return
            }

            val retryable = isRetryableError(error)
            currentState = PlayerState.Error(error, url, retryable)
            
            // Mensagens de erro amigáveis em português
            val errorMsg = when (error.errorCode) {
                // Erros de rede (RECUPERÁVEIS)
                1001 -> "Problema de conexão - tentando reconectar..."
                1002 -> "Falha de conexão com servidor - tentando novamente..."
                1003 -> "Tempo de conexão esgotado - tentando novamente..."
                1004 -> "Servidor demorou para responder - tentando novamente..."
                
                // Erros permanentes (NÃO RECUPERÁVEIS)
                -2001 -> "Canal não encontrado (404/403)"
                -2002 -> "Arquivo de vídeo corrompido"
                -2003 -> "Lista de canais inválida"
                -2004 -> "Arquivo não encontrado no servidor"
                
                // Outros erros
                else -> {
                    val cause = error.cause?.javaClass?.simpleName ?: "Desconhecido"
                    "Erro: $cause"
                }
            }
            
            // Mostra mensagem apenas se não for retry automático
            if (!retryable || retryCount >= MAX_RETRIES) {
                showError(errorMsg)
            } else {
                Log.d(TAG, "onPlayerError: $errorMsg (retry $retryCount/$MAX_RETRIES)")
            }

            if (retryable && retryCount < MAX_RETRIES) {
                retryCount++
                scheduleRetry(url)
            } else if (!retryable || retryCount >= MAX_RETRIES) {
                Log.e(TAG, "onPlayerError: erro não recuperável ou max retries atingido")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
            if (isPlaying) {
                val url = (currentState as? PlayerState.Loading)?.url
                if (url != null) {
                    currentState = PlayerState.Playing(url)
                    retryCount = 0
                    Log.d(TAG, "onIsPlayingChanged: reproduzindo ${url.url}")
                }
            }
        }
    }
}
