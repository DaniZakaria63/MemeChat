package `fun`.walawe.modelpull

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.constant.ModelUrlProvider
import `fun`.walawe.modelpull.api.WalaweClientAPI
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @HuggingFaceOkHttpClient
    fun providesHuggingFaceOkHttpClient(
        modelUrlProvider: ModelUrlProvider
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${modelUrlProvider.getHuggingFaceApiKey()}")
                        .build()
                chain.proceed(request)
            }
            .build()
    }

    @Provides
    @Singleton
    @KeenableWebSearchMCP
    fun providesKeenableWebSearch(
        modelUrlProvider: ModelUrlProvider
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-API-Key", modelUrlProvider.getMcpKeenableApiKey())
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    @Provides
    fun providesWalaweClientAPI(
        @HuggingFaceOkHttpClient okHttpClient: OkHttpClient
    ): WalaweClientAPI {
        return Retrofit.Builder()
            .baseUrl("http://mcp.org")
            .client(okHttpClient)
            .build()
            .create(WalaweClientAPI::class.java)
    }
}


@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HuggingFaceOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KeenableWebSearchMCP