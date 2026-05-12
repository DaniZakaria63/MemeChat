package `fun`.walawe.modelpull.model

import kotlinx.serialization.Serializable
import java.io.File
import javax.annotation.concurrent.Immutable

@Immutable
@Serializable
data class CacheModel(
    val modelId: String = "",
    val displayName: String = "",
    val dimension: Pair<Int, Int> = Pair(256, 256),
    val localFileName: String = "",
    val localFileDir: String = "",
    val fileCache: File? = null,
    val downloadedAt: Long? = null,
    val lastSyncAt: Long? = null,
)