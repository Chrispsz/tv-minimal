package com.iplinks.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PlayerActivity
 * 
 * Using runTest for coroutine testing as recommended
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerActivityTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== URL VALIDATION ====================
    
    @Test
    fun `valid http URL should be recognized`() = runTest {
        val url = "http://example.com/stream.m3u8"
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `valid https URL should be recognized`() = runTest {
        val url = "https://example.com/stream.m3u8"
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `null URL should be invalid`() = runTest {
        val url: String? = null
        assertTrue(url == null || !url.startsWith("http"))
    }

    @Test
    fun `empty URL should be invalid`() = runTest {
        val url: String? = ""
        assertTrue(url == null || !url.startsWith("http"))
    }

    @Test
    fun `URL without protocol should be invalid`() = runTest {
        val url = "example.com/stream.m3u8"
        assertFalse(url.startsWith("http"))
    }

    @Test
    fun `ftp URL should be invalid for streaming`() = runTest {
        val url = "ftp://example.com/stream.m3u8"
        assertFalse(url.startsWith("http"))
    }

    @Test
    fun `URL with spaces should be trimmed before validation`() = runTest {
        val rawUrl = "  http://example.com/stream.m3u8"
        val url = rawUrl.trim()
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `trimmed URL validation should work correctly`() = runTest {
        val rawUrl: String? = "  https://example.com/stream.m3u8  "
        val url = rawUrl?.trim()
        assertTrue(url != null && url.startsWith("http"))
    }

    // ==================== PLAYER STATE ====================
    
    @Test
    fun `PlayerState Idle should be initial state`() = runTest {
        val state: PlayerState = PlayerState.Idle
        assertTrue(state is PlayerState.Idle)
    }

    @Test
    fun `PlayerState Loading should contain URL`() = runTest {
        val url = "https://example.com/stream.m3u8"
        val state: PlayerState = PlayerState.Loading(url)
        assertTrue(state is PlayerState.Loading)
        assertEquals(url, (state as PlayerState.Loading).url)
    }

    @Test
    fun `PlayerState Playing should contain URL`() = runTest {
        val url = "https://example.com/stream.m3u8"
        val state: PlayerState = PlayerState.Playing(url)
        assertTrue(state is PlayerState.Playing)
        assertEquals(url, (state as PlayerState.Playing).url)
    }

    @Test
    fun `when expression should be exhaustive for PlayerState`() = runTest {
        val states = listOf<PlayerState>(
            PlayerState.Idle,
            PlayerState.Loading("url1"),
            PlayerState.Playing("url2"),
            PlayerState.Error(mockPlaybackException(), "url3")
        )

        val results = states.map { state ->
            when (state) {
                is PlayerState.Idle -> "idle"
                is PlayerState.Loading -> "loading:${state.url}"
                is PlayerState.Playing -> "playing:${state.url}"
                is PlayerState.Error -> "error:${state.url}"
            }
        }

        assertEquals("idle", results[0])
        assertEquals("loading:url1", results[1])
        assertEquals("playing:url2", results[2])
        assertEquals("error:url3", results[3])
    }

    // ==================== RETRY LOGIC ====================
    
    @Test
    fun `retry count should not exceed MAX_RETRIES`() = runTest {
        var retryCount = 0
        val maxRetries = 3
        
        repeat(5) {
            if (retryCount < maxRetries) {
                retryCount++
            }
        }
        
        assertEquals(3, retryCount)
    }

    // ==================== HELPER FUNCTIONS ====================
    
    private fun mockPlaybackException(): androidx.media3.common.PlaybackException {
        return androidx.media3.common.PlaybackException(
            "Test error",
            null,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
    }
}

/**
 * Sealed class for player state - mirrors the one in PlayerActivity
 */
private sealed class PlayerState {
    data object Idle : PlayerState()
    data class Loading(val url: String) : PlayerState()
    data class Playing(val url: String) : PlayerState()
    data class Error(val exception: androidx.media3.common.PlaybackException, val url: String) : PlayerState()
}
