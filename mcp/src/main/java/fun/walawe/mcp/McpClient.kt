package `fun`.walawe.mcp

import `fun`.walawe.constant.KEENABLE_SERVER_URL
import `fun`.walawe.modelpull.KeenableWebSearchMCP
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class McpClient @Inject constructor(
    @KeenableWebSearchMCP private val keenableWebSearch: OkHttpClient,
) {
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun post(requestBody: String): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(KEENABLE_SERVER_URL)
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        keenableWebSearch.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body.string()
                    cont.resume(body)
                }
            }
        })
    }
}
