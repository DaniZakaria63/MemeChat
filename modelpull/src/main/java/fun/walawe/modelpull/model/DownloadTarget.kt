package `fun`.walawe.modelpull.model


data class DownloadTarget(
    val uri: String,
    val fileName: String,
    val cacheModel: Boolean,
    val keyCacheModel: CacheKey,
)