package `fun`.walawe.local.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["messageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("messageId"),
        Index("faissId"),
        Index("conversationId"),
    ],
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val faissId: Long,
    val sequence: Int,
)
