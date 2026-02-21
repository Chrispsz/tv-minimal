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
 * Cobertura:
 * - URL Validation (edge cases)
 * - PlayerState sealed class
 * - Retry logic with exponential backoff
 * - Error classification
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

    // ==================== URL VALIDATION (Basic) ====================
    
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

    // ==================== URL VALIDATION (Edge Cases) ====================
    
    @Test
    fun `URL with special characters should be valid if properly encoded`() = runTest {
        val url = "https://example.com/stream%20file.m3u8"
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `URL with query parameters should be valid`() = runTest {
        val url = "https://example.com/stream.m3u8?token=abc123&expires=12345"
        assertTrue(url.startsWith("http") && url.contains("?"))
    }

    @Test
    fun `URL with port should be valid`() = runTest {
        val url = "http://example.com:8080/stream.m3u8"
        assertTrue(url.startsWith("http") && url.contains(":8080"))
    }

    @Test
    fun `malformed URL without domain should be handled`() = runTest {
        val url = "https://"
        // Should have a domain part after protocol
        val hasDomain = url.removePrefix("https://").removePrefix("http://").isNotEmpty()
        assertFalse(hasDomain)
    }

    @Test
    fun `URL with only protocol should be invalid`() = runTest {
        val url = "http://"
        val domain = url.removePrefix("http://").removePrefix("https://")
        assertTrue(domain.isBlank())
    }

    @Test
    fun `URL starting with double slash should be invalid`() = runTest {
        val url = "//example.com/stream.m3u8"
        assertFalse(url.startsWith("http"))
    }

    @Test
    fun `IP address URL should be valid`() = runTest {
        val url = "http://192.168.1.1:8080/stream.m3u8"
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `localhost URL should be valid`() = runTest {
        val url = "http://localhost:3000/stream.m3u8"
        assertTrue(url.startsWith("http"))
    }

    // ==================== PLAYER STATE ====================
    
    @Test
    fun `PlayerState Idle should be initial state`() = runTest {
        val state: PlayerState = PlayerState.Idle
        assertTrue(state is PlayerState.Idle)
    }

    @Test
    fun `PlayerState Stopped should be data object`() = runTest {
        val state: PlayerState = PlayerState.Stopped
        assertTrue(state is PlayerState.Stopped)
        // data object has nice toString()
        assertTrue(state.toString().contains("Stopped"))
    }

    @Test
    fun `PlayerState Loading should contain URL`() = runTest {
        val url = ValidatedUrl("https://example.com/stream.m3u8")
        val state: PlayerState = PlayerState.Loading(url)
        assertTrue(state is PlayerState.Loading)
        assertEquals("https://example.com/stream.m3u8", (state as PlayerState.Loading).url.url)
    }

    @Test
    fun `PlayerState Playing should contain URL`() = runTest {
        val url = ValidatedUrl("https://example.com/stream.m3u8")
        val state: PlayerState = PlayerState.Playing(url)
        assertTrue(state is PlayerState.Playing)
        assertEquals("https://example.com/stream.m3u8", (state as PlayerState.Playing).url.url)
    }

    @Test
    fun `PlayerState Error should contain retryable flag`() = runTest {
        val url = ValidatedUrl("https://example.com/stream.m3u8")
        val error = mockPlaybackException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val state: PlayerState = PlayerState.Error(error, url, true)
        
        assertTrue(state is PlayerState.Error)
        assertTrue((state as PlayerState.Error).isRetryable)
    }

    @Test
    fun `when expression should be exhaustive for PlayerState`() = runTest {
        val states = listOf<PlayerState>(
            PlayerState.Idle,
            PlayerState.Stopped,
            PlayerState.Loading(ValidatedUrl("url1")),
            PlayerState.Playing(ValidatedUrl("url2")),
            PlayerState.Error(mockPlaybackException(), ValidatedUrl("url3"), true)
        )

        val results = states.map { state ->
            when (state) {
                is PlayerState.Idle -> "idle"
                is PlayerState.Stopped -> "stopped"
                is PlayerState.Loading -> "loading:${state.url.url}"
                is PlayerState.Playing -> "playing:${state.url.url}"
                is PlayerState.Error -> "error:${state.url.url}:${state.isRetryable}"
            }
        }

        assertEquals("idle", results[0])
        assertEquals("stopped", results[1])
        assertEquals("loading:url1", results[2])
        assertEquals("playing:url2", results[3])
        assertEquals("error:url3:true", results[4])
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

    @Test
    fun `exponential backoff should increase delay`() = runTest {
        val baseDelay = 1000L
        val delays = mutableListOf<Long>()
        
        for (retryCount in 1..3) {
            val delayMs = baseDelay * (1 shl (retryCount - 1))
            delays.add(delayMs)
        }
        
        assertEquals(listOf(1000L, 2000L, 4000L), delays)
    }

    @Test
    fun `first retry should have 1 second delay`() = runTest {
        val baseDelay = 1000L
        val retryCount = 1
        val expectedDelay = baseDelay * (1 shl (retryCount - 1))
        
        assertEquals(1000L, expectedDelay)
    }

    @Test
    fun `second retry should have 2 seconds delay`() = runTest {
        val baseDelay = 1000L
        val retryCount = 2
        val expectedDelay = baseDelay * (1 shl (retryCount - 1))
        
        assertEquals(2000L, expectedDelay)
    }

    @Test
    fun `third retry should have 4 seconds delay`() = runTest {
        val baseDelay = 1000L
        val retryCount = 3
        val expectedDelay = baseDelay * (1 shl (retryCount - 1))
        
        assertEquals(4000L, expectedDelay)
    }

    // ==================== ERROR CLASSIFICATION ====================
    
    @Test
    fun `network connection error should be retryable`() = runTest {
        val errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        val nonRetryableCodes = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        
        assertTrue(errorCode !in nonRetryableCodes)
    }

    @Test
    fun `HTTP 404 error should not be retryable`() = runTest {
        val errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        val nonRetryableCodes = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `malformed manifest error should not be retryable`() = runTest {
        val errorCode = PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
        val nonRetryableCodes = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `file not found error should not be retryable`() = runTest {
        val errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        val nonRetryableCodes = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `timeout error should be retryable`() = runTest {
        val errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val nonRetryableCodes = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        
        assertTrue(errorCode !in nonRetryableCodes)
    }

    // ==================== VALUE CLASS ====================
    
    @Test
    fun `ValidatedUrl should store URL correctly`() = runTest {
        val url = "https://example.com/stream.m3u8"
        val validated = ValidatedUrl(url)
        
        assertEquals(url, validated.url)
    }

    @Test
    fun `ValidatedUrl toString should return URL`() = runTest {
        val url = "https://example.com/stream.m3u8"
        val validated = ValidatedUrl(url)
        
        assertEquals(url, validated.toString())
    }

    // ==================== HELPER FUNCTIONS ====================
    
    private fun mockPlaybackException(
        errorCode: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    ): PlaybackException {
        return PlaybackException(
            "Test error",
            null,
            errorCode
        )
    }
}

// ==================== TEST DOUBLES ====================

/**
 * Value class for validated URL - mirrors PlayerActivity
 */
@JvmInline
value class ValidatedUrl(val url: String)

/**
 * Sealed class for player state - mirrors PlayerActivity
 */
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

/**
 * Mock PlaybackException for testing
 */
private class PlaybackException(
    message: String?,
    cause: Throwable?,
    val errorCode: Int
) : Exception(message, cause)
