package `fun`.walawe.memechat.presenter

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.constant.DEFAULT_MODEL_SYSTEM_PROMPT
import `fun`.walawe.local.data.ChunkEntity
import `fun`.walawe.local.data.ConversationEntity
import `fun`.walawe.local.data.MessageEntity
import `fun`.walawe.local.service.ChunkHandlerService
import `fun`.walawe.local.service.LocalDatabaseService
import `fun`.walawe.memechat.data.ChatEmbedBuilder
import `fun`.walawe.memechat.data.ModelRepository
import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole
import `fun`.walawe.memechat.model.ChatUiState
import `fun`.walawe.memechat.model.ConversationHistory
import `fun`.walawe.memelm.inference.InferenceEngine
import `fun`.walawe.memelm.inference.InferenceParams
import `fun`.walawe.memelm.inference.STATE
import `fun`.walawe.memelm.inference.isUninterruptible
import `fun`.walawe.modelpull.model.CacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import timber.log.Timber
import `fun`.walawe.memechat.data.ImageManipulation
import `fun`.walawe.memechat.model.getChatRole
import `fun`.walawe.memelm.inference.EmbeddingEngine
import javax.inject.Inject
import kotlin.collections.indexOfFirst

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val inferenceEngine: InferenceEngine,
    private val imageManipulation: ImageManipulation,
    private val localDBService: LocalDatabaseService,
    private val embeddingEngine: EmbeddingEngine,
    private val chunkHandlerService: ChunkHandlerService,
) : BaseViewModel() {

    private val _modelState = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized).also { flow ->
        safeViewModelScope.launch {
            inferenceEngine.state.collect{flow.emit(it)}
        }
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = combine(_uiState, _modelState, errorState){ ui, model, error ->
        ui.copy(isProcessing = model.isUninterruptible , error = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    val conversations: StateFlow<List<ConversationHistory>> =
        localDBService.getAllConversations().map { entities ->
            entities.map { entity ->
                ConversationHistory(
                    id = entity.id,
                    title = entity.title,
                    preview = entity.preview,
                    time = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(entity.updatedAt)),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        safeViewModelScope.launch {
            prepareModel()
            runCatching {
                val validIds = localDBService.getAllConversationIds().toSet()
                imageManipulation.sweepOrphans(validIds)
            }
        }
    }

    fun startNewConversation() {
        safeViewModelScope.launch {
            _messages.value = emptyList()
            _currentConversationId.value = null
            _uiState.update { it.copy(isNewConversation = true, selectedImageUri = null) }
            inferenceEngine.cancelGeneration()
        }
    }

    fun loadConversation(conversationId: String) {
        Timber.d("loadConversation: start id=$conversationId")
        safeViewModelScope.launch {
            val conv = localDBService.getConversation(conversationId)
            if (conv == null) {
                Timber.w("loadConversation: conversation not found $conversationId")
                return@launch
            }

            _currentConversationId.value = conversationId
            _uiState.update { it.copy(isNewConversation = false) }

            val messages = localDBService.getMessages(conversationId)
            val loaded = messages.reversed().map { entity ->
                val safeImage = entity.imageUri?.takeIf { File(it).exists() }
                ChatMessage(
                    id = entity.id,
                    role = entity.role.getChatRole(),
                    text = entity.text,
                    timestamp = currentTime(entity.timestamp),
                    imageUri = safeImage,
                    reasoning = entity.reasoning,
                )
            }
            Timber.d("loadConversation: mapped ${loaded.size} messages, setting _messages")
            _messages.update { loaded }
        }
    }

    fun deleteConversation(conversationId: String) {
        safeViewModelScope.launch {
            localDBService.deleteConversation(conversationId)
            imageManipulation.deleteConversationFolder(conversationId)
            if (_currentConversationId.value == conversationId) {
                startNewConversation()
            }
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return
        if (_modelState.value !is InferenceEngine.State.ModelReady) {
            postError("Model is not ready yet")
            return
        }

        val transientImageUri = _uiState.value.selectedImageUri
        val forReasoning = _uiState.value.isThinkingEnabled
        val isNewConversation = _currentConversationId.value == null
        val conversationId = _currentConversationId.value ?: UUID.randomUUID().toString()
        val currentTimeMilis = System.currentTimeMillis()

        safeViewModelScope.launch {
            val userMsgId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()
            val userMessage = ChatMessage(
                id = userMsgId,
                role = ChatRole.User,
                text = message,
                timestamp = currentTime(currentTimeMilis),
                imageUri = transientImageUri
            )
            val assistantMessage = ChatMessage(
                id = assistantId,
                role = ChatRole.Assistant,
                text = "",
                timestamp = "",
                isStreaming = true
            )

            /**
             * Preprocess Image
             * This will return clean chunk
             */
            val (chunkList, imageBitmap) = preprocessTextAndImage(
                transientImageUri = transientImageUri,
                conversationId = conversationId,
                messageId = userMsgId,
                message = message
            )

            val chunkTextOnlyList = chunkList
                .sortedBy { it.sequence }
                .map { it.text }

            if (isNewConversation) {
                localDBService.insertConversation(ConversationEntity(
                    id = conversationId,
                    title = chunkTextOnlyList.first().take(50),
                    preview = chunkTextOnlyList.first().take(80),
                    updatedAt = currentTimeMilis,
                    createdAt = currentTimeMilis,
                ))
            }
            _messages.update { listOf(assistantMessage, userMessage) + it }
            _uiState.update { it.copy(isNewConversation = false) }

            /**
             * Embedding Vector Search
             * Process embedding using embedding model
             * Search vector in local vector db
             */
            val chunkEmbedBuffer = mutableListOf<ChunkEntity>()
            val embeddingVectorBuffer = mutableListOf<FloatArray>()
            chunkList.forEach {
                val embeddingVector = withContext(Dispatchers.IO){
                    embeddingEngine.embed(it.text)
                }
                embeddingVectorBuffer += embeddingVector

                val chunkSimilarity = chunkHandlerService.searchChunks( embeddingVector)
                chunkEmbedBuffer += chunkSimilarity
            }

            val constructiveContextMessages = chunkEmbedBuffer
                .distinctBy { it.id }
                .sortedBy { it.sequence }
                .map { it.text }

            val constructedPrompt = ChatEmbedBuilder.buildWithContext(
                systemPrompt = DEFAULT_MODEL_SYSTEM_PROMPT,
                contextHistory = constructiveContextMessages,
                currentMessage = message,
                forReasoning = forReasoning,
            )

            /**
             * LLM Process Prompt
             */
            val inferenceOutputFlow = if (imageBitmap == null) {
                inferenceEngine.sendConversation(constructedPrompt, forReasoning)
            } else {
                inferenceEngine.sendConversationWithImage(imageBitmap, constructedPrompt, forReasoning)
            }

            inferenceOutputFlow.collect { (state, token) ->
                when (state) {
                    STATE.THINKING -> appendReasoningToAssistant(assistantId, token)
                    STATE.ANSWER -> appendToAssistant(assistantId, token)
                }
            }

            /**
             * Room and Vector Persistence
             */
            localDBService.insertMessage(MessageEntity(
                id = userMsgId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = message,
                reasoning = "",
                timestamp = currentTimeMilis,
                imageUri = transientImageUri,
            ))

            chunkList.zip(embeddingVectorBuffer).forEach { (chunk, vector) ->
                chunkHandlerService.storeChunk(chunk, vector)
            }
            chunkHandlerService.saveFileChunk()

            /**
             * Message update and retrieval
             */
            //Because inference response is asynchronous
            val responseAsistantMessage = _messages.value.find { it.id == assistantId }
            localDBService.insertMessage(MessageEntity(
                id = assistantId,
                conversationId = conversationId,
                role = ChatRole.Assistant.name,
                text = "",
                reasoning = responseAsistantMessage?.reasoning.orEmpty(),
                timestamp = currentTimeMilis,
            ))

            localDBService.updateConversation(
                id = conversationId,
                title = message.take(50),
                preview = responseAsistantMessage?.text.orEmpty().take(80),
                updatedAt = currentTimeMilis,
            )
            Timber.d("sendMessage: saved assistant msg + updated conversation $conversationId")

            finishAssistantStream(assistantId)
        }
    }

    override fun onCleared() {
        inferenceEngine.cancelGeneration()
        inferenceEngine.destroy()
        embeddingEngine.release()
        chunkHandlerService.releaseVectorStore()
        super.onCleared()
    }

    private suspend fun preprocessTextAndImage(
        transientImageUri: String?,
        conversationId: String,
        messageId: String,
        message: String,
    ): Pair<List<ChunkEntity>, Bitmap?>{

        val bitmap = transientImageUri?.let {
            imageManipulation.copyToInternal(
                src = it.toUri(),
                conversationId = conversationId,
                messageId = messageId,
            )

            withContext(Dispatchers.IO) {
                imageManipulation.decode(Uri.fromFile(File(it)))
            }
        }

        val preprocessChunk = chunkHandlerService.preprocessAndChunk(messageId, message)

        return Pair(preprocessChunk, bitmap)
    }

    private suspend fun prepareModel() {
        val modelAbsolutePath = modelRepository.getCachedModel(CacheKey.Model).getOrElse { error ->
            postError(error.message ?: "Model is not downloaded yet")
            return
        }

        val mmprojAbsolutePath = modelRepository.getCachedModel(CacheKey.MMPROJ).getOrElse { error ->
            postError(error.message ?: "MMProj is not downloaded yet")
            return
        }

        val embeddingAbsolutePath = modelRepository.getCachedModel(CacheKey.Embedding).getOrElse { error ->
            postError(error.message ?: "Embedding model is not downloaded yet")
            return
        }

        val vectorDBAbsolutePath = modelRepository.getVectorDBPath()

        _uiState.update { it.copy(isNewConversation = true) }
        loadModel(modelAbsolutePath, mmprojAbsolutePath, embeddingAbsolutePath, vectorDBAbsolutePath)
    }

    private fun loadModel(model: String, mmproj: String, embedding: String, vectorDB: String) {
        safeViewModelScope.launch(Dispatchers.IO) {
            inferenceEngine.loadModel(
                pathToModel = model,
                pathToMMProj = mmproj,
                params = InferenceParams.getDefault()
            )
            inferenceEngine.setSystemPrompt(DEFAULT_MODEL_SYSTEM_PROMPT)
            embeddingEngine.init(embedding)
            chunkHandlerService.initVectorStore(vectorDB)
        }
    }

    private fun updateAssistantMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { messages ->
            val index = messages.indexOfFirst { it.id == messageId }
            if (index == -1) return@update messages
            messages.toMutableList().also { it[index] = transform(it[index]) }
        }
    }
    private fun appendToAssistant(id: String, token: String) =
        updateAssistantMessage(id) { it.copy(text = it.text + token, isStreaming = true) }

    private fun appendReasoningToAssistant(id: String, token: String) =
        updateAssistantMessage(id) { it.copy(reasoning = it.reasoning + token) }

    fun finishAssistantStream(id: String) =
        updateAssistantMessage(id) {
            it.copy(isStreaming = false, timestamp = currentTime(System.currentTimeMillis()))
        }

    fun setSelectedImageUri(uri: String?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun toggleThinking() {
        _uiState.update { it.copy(isThinkingEnabled = !it.isThinkingEnabled) }
        Timber.d("Reasoning mode: ${_uiState.value.isThinkingEnabled}")
    }

    private fun postError(message: String) {
        errorState.update { message }
    }

    private fun currentTime(timeMilis: Long): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(timeMilis)
    }
}
