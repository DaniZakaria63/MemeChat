package `fun`.walawe.memelm

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `fun`.walawe.memelm.gguf.GGUFReader
import `fun`.walawe.memelm.inference.InferenceEngine
import `fun`.walawe.memelm.inference.InferenceEngineImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemeLMModule {
    @Provides
    @Singleton
    fun provideInferenceEngine(
        @ApplicationContext context: Context
    ): InferenceEngine = InferenceEngineImpl.getInstance(context)

    @Provides
    @Singleton
    fun provideGgufReader(): GGUFReader = GGUFReader()

    @Provides
    @Singleton
    fun provideHardwareAccelerationChecker(
        @ApplicationContext context: Context,
    ): HardwareAccelerationChecker = HardwareAccelerationChecker(context)
}