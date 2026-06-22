package `fun`.walawe.mcp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.modelpull.KeenableWebSearchMCP
import okhttp3.OkHttpClient
import `fun`.walawe.mcp.McpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    @Provides
    @Singleton
    fun provideMcpClient(
        @KeenableWebSearchMCP httpClient: OkHttpClient,
    ): McpClient {
        return McpClient(httpClient)
    }
}
