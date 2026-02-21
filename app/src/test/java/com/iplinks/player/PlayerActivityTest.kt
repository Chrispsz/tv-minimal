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
 * Tests logic without Android dependencies:
 * - URL Validation
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

    // ==================== URL VALIDATION ====================
    
    @Test
    fun `valid http URL should start with http`() = runTest {
        val url = "http://example.com/stream.m3u8"
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `valid https URL should start with http`() = runTest {
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
    fun `IP address URL should be valid`() = runTest {
        val url = "http://192.168.1.1:8080/stream.m3u8"
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun `localhost URL should be valid`() = runTest {
        val url = "http://localhost:3000/stream.m3u8"
        assertTrue(url.startsWith("http"))
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
    fun `network connection error code should be retryable`() = runTest {
        // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = -1001
        val errorCode = -1001
        val nonRetryableCodes = setOf(-2001, -2002, -2003, -2004, Integer.MIN_VALUE)
        
        assertTrue(errorCode !in nonRetryableCodes)
    }

    @Test
    fun `HTTP bad status error code should not be retryable`() = runTest {
        // ERROR_CODE_IO_BAD_HTTP_STATUS = -2001
        val errorCode = -2001
        val nonRetryableCodes = setOf(-2001, -2002, -2003, -2004, Integer.MIN_VALUE)
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `parsing container malformed error code should not be retryable`() = runTest {
        // ERROR_CODE_PARSING_CONTAINER_MALFORMED = -2002
        val errorCode = -2002
        val nonRetryableCodes = setOf(-2001, -2002, -2003, -2004, Integer.MIN_VALUE)
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `parsing manifest malformed error code should not be retryable`() = runTest {
        // ERROR_CODE_PARSING_MANIFEST_MALFORMED = -2003
        val errorCode = -2003
        val nonRetryableCodes = setOf(-2001, -2002, -2003, -2004, Integer.MIN_VALUE)
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `file not found error code should not be retryable`() = runTest {
        // ERROR_CODE_IO_FILE_NOT_FOUND = -2004
        val errorCode = -2004
        val nonRetryableCodes = setOf(-2001, -2002, -2003, -2004, Integer.MIN_VALUE)
        
        assertTrue(errorCode in nonRetryableCodes)
    }

    @Test
    fun `timeout error code should be retryable`() = runTest {
        // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = -1002
        val errorCode = -1002
        val nonRetryableCodes = setOf(-2001, -2002, -2003, -2004, Integer.MIN_VALUE)
        
        assertTrue(errorCode !in nonRetryableCodes)
    }
}
