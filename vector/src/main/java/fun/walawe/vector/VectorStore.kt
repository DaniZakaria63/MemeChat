package `fun`.walawe.vector

import java.io.FileNotFoundException

object VectorStore {
    data class SearchResult(val id: Long, val score: Float)
    var localFilePath: String? = null

    fun init(checkpointPath: String? = null): Boolean {
        localFilePath = checkpointPath
        return nativeInit(checkpointPath)
    }

    fun add(id: Long, embedding: FloatArray) =
        nativeAdd(id, embedding)

    fun search(queryEmbedding: FloatArray, topK: Int = 5): List<SearchResult> =
        nativeSearch(queryEmbedding, topK).toList()

    fun remove(id: Long) = nativeRemove(id)
    fun save() {
        val path = localFilePath.orEmpty().ifEmpty {
            throw IllegalAccessException("File path is unknown or empty")
        }
        nativeSave(path)
    }
    fun release() = nativeRelease()
    fun size(): Int = nativeSize()
    fun dimension(): Int = nativeDimension()

    private external fun nativeInit(checkpointPath: String?): Boolean
    private external fun nativeAdd(id: Long, embedding: FloatArray)
    private external fun nativeSearch(queryEmbedding: FloatArray, topK: Int): Array<SearchResult>
    private external fun nativeRemove(id: Long)
    private external fun nativeSave(path: String)
    private external fun nativeRelease()
    private external fun nativeSize(): Int
    private external fun nativeDimension(): Int

    init {
        System.loadLibrary("vector")
    }
}
