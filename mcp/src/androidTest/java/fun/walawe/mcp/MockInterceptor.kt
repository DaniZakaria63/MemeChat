package `fun`.walawe.mcp

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

object MockInterceptor : Interceptor {
    var responseCode = 200
    var responseBody = ""
    var failure: IOException? = null
    var lastRequest: Request? = null
    var lastRequestBody: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        lastRequest = request
        lastRequestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }
        failure?.let { throw it }
        return Response.Builder()
            .code(responseCode)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .build()
    }

    fun reset() {
        responseCode = 200
        responseBody = ""
        failure = null
        lastRequest = null
        lastRequestBody = null
    }
}
