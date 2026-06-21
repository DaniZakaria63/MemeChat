package `fun`.walawe.local.service

import `fun`.walawe.local.dao.ConversationDao
import `fun`.walawe.local.dao.MessageDao
import `fun`.walawe.local.data.ConversationEntity
import `fun`.walawe.local.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDatabaseService @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun getAllConversationIds(): List<String> =
        conversationDao.getAllConversationIds()

    suspend fun getConversation(id: String): ConversationEntity? =
        conversationDao.getConversation(id)

    suspend fun insertConversation(conversation: ConversationEntity) =
        conversationDao.insert(conversation)

    suspend fun updateConversation(id: String, title: String, preview: String, updatedAt: Long) =
        conversationDao.update(id, title, preview, updatedAt)

    suspend fun deleteConversation(id: String) =
        conversationDao.delete(id)

    suspend fun getMessages(conversationId: String): List<MessageEntity> =
        messageDao.getMessages(conversationId)

    suspend fun getAllUserMessages(): List<MessageEntity> =
        messageDao.getAllUserMessages()

    suspend fun insertMessage(message: MessageEntity) =
        messageDao.insert(message)
}
