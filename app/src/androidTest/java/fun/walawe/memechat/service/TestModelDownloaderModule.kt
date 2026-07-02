package `fun`.walawe.memechat.service

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import `fun`.walawe.modelpull.WorkerModule
import `fun`.walawe.modelpull.service.ModelDownloader
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkerModule::class])
object TestModelDownloaderModule {
    @Provides
    @Singleton
    fun provideModelDownloader(
        @ApplicationContext context: Context
    ): ModelDownloader = FakeModelDownloader(context)
}
