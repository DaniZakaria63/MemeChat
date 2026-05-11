package `fun`.walawe.modelpull

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.modelpull.api.WalaweClientAPI
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import retrofit2.Response

@Module
@InstallIn(SingletonComponent::class)
object TestNetworkModule {
    @Provides
    fun providesWalaweClientAPI(): WalaweClientAPI = object : WalaweClientAPI {
        override suspend fun getModel(url: String): Response<ResponseBody> {
            val body = ResponseBody.create("text/plain".toMediaTypeOrNull(), "")
            return Response.error(500, body)
        }
    }
}