package `fun`.walawe.modelpull

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.constant.HUGGINGFACE_API_KEY
import `fun`.walawe.constant.KEENABLE_API_KEY
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
    fun providesHuggingFaceOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $HUGGINGFACE_API_KEY")
                        .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
    }

    @Provides
    @Singleton
    @KeenableWebSearchMCP
    fun providesKeenableWebSearch(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-API-Key", KEENABLE_API_KEY)
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