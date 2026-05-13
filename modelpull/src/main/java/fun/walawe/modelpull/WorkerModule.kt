package `fun`.walawe.modelpull

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.modelpull.service.LocalModelDownloader
import `fun`.walawe.modelpull.service.ModelDownloader
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {

    @Singleton
    @Binds
    abstract fun bindModelDownloader(
        localModelDownloader: LocalModelDownloader
    ): ModelDownloader
}