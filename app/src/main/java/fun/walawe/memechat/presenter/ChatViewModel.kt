package `fun`.walawe.memechat.presenter

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.constant.DEFAULT_MODEL_SYSTEM_PROMPT
import `fun`.walawe.local.data.ConversationDao
import `fun`.walawe.local.data.ConversationEntity
import `fun`.walawe.local.data.MessageDao
import `fun`.walawe.local.data.MessageEntity
import `fun`.walawe.local.service.MemoryService
import `fun`.walawe.memechat.data.ChatMLBuilder
import `fun`.walawe.memechat.data.ImageDecoder
import `fun`.walawe.memechat.data.ModelRepository
import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole
import `fun`.walawe.memechat.model.ChatUiState
import `fun`.walawe.memelm.inference.InferenceEngine
import `fun`.walawe.memelm.inference.InferenceParams
import `fun`.walawe.memelm.inference.STATE
import `fun`.walawe.memelm.inference.isUninterruptible
import `fun`.walawe.modelpull.model.CacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val imageDecoder: ImageDecoder,
    private val inferenceEngine: InferenceEngine,
    private val memoryService: MemoryService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
) : BaseViewModel() {

    private val _modelState = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.UnloadingModel).also { flow ->
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

    val dummyConversations =
        listOf<DummyConversation>()

    private var currentConversationId: String? = null
    private var currentConversationCreatedAt: Long = 0L

    init {
        safeViewModelScope.launch {
            prepareModel()
        }
    }

    fun startNewConversation() {
        safeViewModelScope.launch {
            _messages.value = emptyList()
            _uiState.update { it.copy(isNewConversation = true, selectedImageUri = null) }
            inferenceEngine.cancelGeneration()
            currentConversationId = null
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return
        if (_modelState.value !is InferenceEngine.State.ModelReady) {
            postError("Model is not ready yet")
            return
        }

        val imageUri = _uiState.value.selectedImageUri
        val forReasoning = _uiState.value.isThinkingEnabled
        val isNewConversation = currentConversationId == null
        val conversationId = currentConversationId ?: createNewConversation()

        safeViewModelScope.launch {
            val augmentedInput = memoryService.augmentQuery(message)
            val userMsgId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()

            messageDao.insert(MessageEntity(
                id = userMsgId, conversationId = conversationId,
                role = "User", text = message, reasoning = "",
                timestamp = System.currentTimeMillis(), imageUri = imageUri,
            ))

            val userMessage = ChatMessage(id = userMsgId, role = ChatRole.User,
                text = message, timestamp = currentTime(), imageUri = imageUri)
            val assistantMessage = ChatMessage(id = assistantId, role = ChatRole.Assistant,
                text = "", timestamp = "", isStreaming = true)

            val existingHistory = _messages.value.toList()
            _messages.update { listOf(assistantMessage, userMessage) + it }
            _uiState.update { it.copy(isNewConversation = false) }

            var responseText = ""
            val imageBitmap = imageUri?.let { withContext(Dispatchers.IO) { imageDecoder.decode(it.toUri()) } }

            val chatML = when {
                imageBitmap == null && isNewConversation -> ChatMLBuilder.buildFullFromHistory(
                    DEFAULT_MODEL_SYSTEM_PROMPT,
                    existingHistory.reversed() + userMessage,
                    forReasoning)
                imageBitmap == null -> ChatMLBuilder.buildTurn(augmentedInput, forReasoning)
                else -> ""
            }

            val flow = if (imageBitmap != null) {
                inferenceEngine.sendConversationWithImage(imageBitmap, augmentedInput, isNewConversation, forReasoning)
            } else {
                inferenceEngine.sendConversation(chatML, isNewConversation)
            }
            flow.collect { (state, token) ->
                when (state) {
                    STATE.THINKING -> appendReasoningToAssistant(assistantId, token)
                    STATE.ANSWER -> { appendToAssistant(assistantId, token); responseText += token }
                    STATE.FINISH -> { }
                }
            }

            messageDao.insert(MessageEntity(
                id = assistantId, conversationId = conversationId,
                role = "Assistant", text = responseText,
                reasoning = _messages.value.find { it.id == assistantId }?.reasoning ?: "",
                timestamp = System.currentTimeMillis(),
            ))

            conversationDao.insert(ConversationEntity(
                id = conversationId, title = message.take(50),
                preview = responseText.take(80),
                updatedAt = System.currentTimeMillis(),
                createdAt = if (isNewConversation) System.currentTimeMillis() else currentConversationCreatedAt,
            ))

            finishAssistantStream(assistantId)
        }
    }

    private fun createNewConversation(): String {
        val id = UUID.randomUUID().toString()
        currentConversationCreatedAt = System.currentTimeMillis()
        currentConversationId = id
        return id
    }

    override fun onCleared() {
        inferenceEngine.cancelGeneration()
        inferenceEngine.destroy()
        super.onCleared()
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

        _uiState.update { it.copy(isNewConversation = true) }
        loadModel(modelAbsolutePath, mmprojAbsolutePath)
    }

    private fun loadModel(model: String, mmproj: String) {
        safeViewModelScope.launch {
            inferenceEngine.loadModel(
                pathToModel = model,
                pathToMMProj = mmproj,
                params = InferenceParams.getDefault()
            ).also {
                inferenceEngine.setSystemPrompt(DEFAULT_MODEL_SYSTEM_PROMPT)
            }
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
        updateAssistantMessage(id) { it.copy(isStreaming = false, timestamp = currentTime()) }

    fun setSelectedImageUri(uri: String?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    private fun postError(message: String) {
        errorState.update { message }
    }

    private fun currentTime(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(System.currentTimeMillis())
    }
}

data class DummyConversation(
    val id: String,
    val title: String,
    val preview: String,
    val time: String
)
