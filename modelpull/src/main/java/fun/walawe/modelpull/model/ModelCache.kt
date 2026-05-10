package `fun`.walawe.modelpull.model

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global singleton to hold the downloaded model in memory.
 * This allows the model to be accessible throughout the entire app
 * without re-downloading from storage repeatedly.
 */
@Singleton
class ModelCache @Inject constructor() {
    @Volatile
    var cachedModel: CachePaligemmaModel? = null

    fun setModel(model: CachePaligemmaModel?) {
        cachedModel = model
    }

    fun getModel(): CachePaligemmaModel? = cachedModel

    fun clearModel() {
        cachedModel = null
    }
}