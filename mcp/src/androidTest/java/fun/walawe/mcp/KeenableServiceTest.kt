package `fun`.walawe.mcp

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.modelpull.KeenableWebSearchMCP
import `fun`.walawe.modelpull.NetworkModule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidTest
@UninstallModules(NetworkModule::class)
class KeenableServiceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var keenableService: KeenableService

    @Module
    @InstallIn(SingletonComponent::class)
    object TestModule {
        @Provides
        @Singleton
        @KeenableWebSearchMCP
        fun provideMockHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor(MockInterceptor)
                .build()
        }
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        MockInterceptor.reset()
    }

    @After
    fun tearDown() {
        MockInterceptor.reset()
    }

    @Test
    fun searchWebPages_postsToCorrectUrl() = runBlocking {
        MockInterceptor.responseBody = """{"results": []}"""
        keenableService.searchWebPages("hello")

        val request = MockInterceptor.lastRequest
        assertNotNull("Request should have been made", request)
        assertEquals("POST", request!!.method)
        assertTrue(request.url.encodedPath.contains("search"))
    }

    @Test
    fun searchWebPages_returnsResponseBody() = runBlocking {
        val expected = """{"results": [{"title": "Hello World"}]}"""
        MockInterceptor.responseBody = expected

        val result = keenableService.searchWebPages("hello")
        assertEquals(expected, result)
    }

    @Test
    fun searchWebPages_withSiteFilter_includesInBody() = runBlocking {
        MockInterceptor.responseBody = """{"results": []}"""
        keenableService.searchWebPages("hello", site = "example.com")

        val body = MockInterceptor.lastRequestBody ?: ""
        assertTrue(body.contains("example.com"))
    }

    @Test
    fun fetchPageContent_sendsGetRequest() = runBlocking {
        MockInterceptor.responseBody = """{"content": "test"}"""
        keenableService.fetchPageContent("https://example.com")

        val request = MockInterceptor.lastRequest
        assertNotNull("Request should have been made", request)
        assertEquals("GET", request!!.method)
        assertTrue(request.url.encodedPath.contains("fetch"))
    }

    @Test
    fun fetchPageContent_encodesUrlParameter() = runBlocking {
        MockInterceptor.responseBody = """{"content": "test"}"""
        keenableService.fetchPageContent("https://example.com/path?q=hello")

        val queryUrl = MockInterceptor.lastRequest?.url?.queryParameter("url")
        assertNotNull("url query param should exist", queryUrl)
        assertTrue(queryUrl!!.contains("example.com"))
    }

    @Test
    fun fetchPageContent_returnsResponseBody() = runBlocking {
        val expected = """{"content": "Page content here"}"""
        MockInterceptor.responseBody = expected

        val result = keenableService.fetchPageContent("https://example.com")
        assertEquals(expected, result)
    }

    @Test
    fun networkError_propagatesException() {
        MockInterceptor.failure = IOException("Connection refused")

        val exception = assertThrows(IOException::class.java) {
            runBlocking { keenableService.searchWebPages("hello") }
        }
        assertEquals("Connection refused", exception.message)
    }
}
