package `fun`.walawe.modelpull

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.constant.BASE_URL_CALL
import `fun`.walawe.modelpull.api.WalaweClientAPI
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @DefaultPoligemmaDownloadClassModel
    fun providesDefaultModelOkHttpClient(): OkHttpClient {
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
            .build()
    }

    @Provides
    fun providesWalaweClientAPI(
        @DefaultPoligemmaDownloadClassModel okHttpClient: OkHttpClient
    ): WalaweClientAPI {
        return Retrofit.Builder()
            .baseUrl(BASE_URL_CALL)
            .client(okHttpClient)
            .build()
            .create(WalaweClientAPI::class.java)
    }
}


@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultPoligemmaDownloadClassModel