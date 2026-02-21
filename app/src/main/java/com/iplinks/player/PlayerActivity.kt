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
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var retryCount = 0
    private var currentUrl: String? = null

    companion object {
        // Buffer otimizado para HLS live streams
        // Valores baseados em: https://www.akamai.com/blog/performance/enhancing-video-streaming-quality-for-exoplayer-part-2
        private const val MIN_BUFFER_MS = 10000           // 10s - mínimo para evitar stuttering
        private const val MAX_BUFFER_MS = 25000           // 25s - máximo para reduzir drift
        private const val BUFFER_FOR_PLAYBACK_MS = 2000   // 2s - início rápido
        private const val BUFFER_AFTER_REBUFFER_MS = 5000 // 5s - após rebuffer
        
        private const val MAX_RETRIES = 3
        
        // HLS segment duration típico é 6-10s
        // Manter buffer alinhado com segmentos
        private const val TARGET_LIVE_OFFSET_MS = 3000L   // 3s do live edge
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getUrlFromIntent(intent)
        if (url == null) {
            finish()
            return
        }

        currentUrl = url

        // Essential for TV/player apps
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
        play(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = getUrlFromIntent(intent) ?: return
        currentUrl = url
        retryCount = 0
        player?.stop()
        play(url)
    }

    private fun getUrlFromIntent(intent: Intent): String? {
        // 1. URI data como URL direta (http/rtmp)
        intent.data?.toString()?.takeIf {
            it.startsWith("http") || it.startsWith("rtmp")
        }?.let { return it }
        
        // 2. Query parameter do scheme customizado iplinks://play?stream_url=...
        intent.data?.getQueryParameter("stream_url")?.takeIf {
            it.startsWith("http") || it.startsWith("rtmp")
        }?.let { return it }

        // 3. ACTION_SEND com texto
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf {
                it.startsWith("http") || it.startsWith("rtmp")
            }?.let { return it }
        }

        // 4. Extras diretos
        return intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra("video_url")
    }

    private fun initPlayer() {
        // Allocator otimizado para streaming
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        
        // LoadControl otimizado para HLS live
        // Fonte: https://proandroiddev.com/preloading-media-a-future-forward-approach-with-exoplayer-877ca6b0873d
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)  // Sem limite de bytes
            .setPrioritizeTimeOverSizeThresholds(true)  // Importante para live streams
            .build()

        // TrackSelector com configurações padrão otimizadas
        val trackSelector = DefaultTrackSelector(this).apply {
            // Preferência por melhor qualidade disponível
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }

        // Audio attributes para conteúdo de filme/TV
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)  // Pausa ao desconectar fone
                
                // Wake lock para evitar que o sistema mate o app
                setWakeMode(C.WAKE_MODE_NETWORK)

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (retryCount < MAX_RETRIES && currentUrl != null) {
                            retryCount++
                            stop()
                            play(currentUrl!!)
                        }
                    }
                })
            }
    }

    private fun play(url: String) {
        player?.apply {
            // MediaItem com LiveConfiguration para sincronização automática
            // Fonte: https://developer.android.com/media/media3/exoplayer/hls
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(TARGET_LIVE_OFFSET_MS)
                        .setMinOffsetMs(1000L)          // Mínimo 1s do live edge
                        .setMaxOffsetMs(30000L)         // Máximo 30s do live edge
                        .setMinPlaybackSpeed(0.97f)     // Permite desacelerar até 3%
                        .setMaxPlaybackSpeed(1.03f)     // Permite acelerar até 3%
                        .build()
                )
                .build()
            
            setMediaItem(mediaItem)
            prepare()
        }
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
        player?.release()
        player = null
        surfaceView = null
        currentUrl = null
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
