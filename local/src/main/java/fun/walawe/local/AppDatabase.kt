package `fun`.walawe.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import `fun`.walawe.local.dao.ChunkDao
import `fun`.walawe.local.data.ChunkEntity
import `fun`.walawe.local.dao.ConversationDao
import `fun`.walawe.local.data.ConversationEntity
import `fun`.walawe.local.dao.MessageDao
import `fun`.walawe.local.data.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ChunkEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun chunkDao(): ChunkDao

    companion object {
        private const val CREATE_CHUNKS_TABLE = """
            CREATE TABLE IF NOT EXISTS `chunks` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `messageId` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `faissId` INTEGER NOT NULL,
                `sequence` INTEGER NOT NULL,
                FOREIGN KEY (`messageId`) REFERENCES `messages`(`id`) ON DELETE CASCADE
            )
        """

        val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL(CREATE_CHUNKS_TABLE)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_messageId` ON `chunks`(`messageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_faissId` ON `chunks`(`faissId`)")
        }

        val MIGRATION_2_4 = Migration(2, 3) { db ->
            db.execSQL("ALTER TABLE `chunks` ADD COLUMN `role` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `chunks` ADD COLUMN `conversationId` TEXT NOT NULL DEFAULT ``")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_conversationId` ON `chunks`(`conversationId`)")
        }
    }
}