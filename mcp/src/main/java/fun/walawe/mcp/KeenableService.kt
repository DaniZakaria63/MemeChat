package `fun`.walawe.mcp

import `fun`.walawe.modelpull.KeenableWebSearchMCP
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class KeenableService @Inject constructor(
    @KeenableWebSearchMCP private val httpClient: OkHttpClient,
) {
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun searchWebPages(query: String, site: String? = null): String {
        val body = JSONObject().apply {
            put("query", query)
            site?.let { put("site", it) }
        }
        val request = Request.Builder()
            .url("https://api.keenable.ai/v1/search")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return execute(request)
    }

    suspend fun fetchPageContent(url: String, maxChars: Int = 50000): String {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("https://api.keenable.ai/v1/fetch?url=$encodedUrl&max_chars=$maxChars")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()
                    if (body != null) cont.resume(body)
                    else cont.resumeWithException(IOException("Empty response body"))
                }
            }
        })
    }
}
