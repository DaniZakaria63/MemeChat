package `fun`.walawe.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import `fun`.walawe.local.data.ChunkEntity

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE faissId IN (:faissIds) AND conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun getChunksByFaissIds(faissIds: List<Long>, conversationId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId) ORDER BY sequence ASC")
    suspend fun getChunksByConversation(conversationId: String): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: ChunkEntity)
}
