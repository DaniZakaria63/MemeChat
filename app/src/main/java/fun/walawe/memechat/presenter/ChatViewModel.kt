package `fun`.walawe.memechat.presenter

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.constant.DEFAULT_MODEL_SYSTEM_PROMPT
import `fun`.walawe.memechat.data.ImageDecoder
import `fun`.walawe.memechat.data.ModelRepository
import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole
import `fun`.walawe.memechat.model.ChatUiState
import `fun`.walawe.memelm.inference.InferenceEngine
import `fun`.walawe.memelm.inference.InferenceParams
import `fun`.walawe.memelm.inference.isUninterruptible
import `fun`.walawe.modelpull.model.CacheKey
import `fun`.walawe.modelpull.model.ModelCache
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

    init {
        safeViewModelScope.launch {
            prepareModel()
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return
        if (_modelState.value !is InferenceEngine.State.ModelReady) {
            postError("Model is not ready yet")
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.User,
            text = message,
            timestamp = currentTime(),
            imageUri = null,
        )

        val assistantId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            text = "",
            timestamp = currentTime(),
            isStreaming = true,
        )

        _messages.update {  it + listOf(assistantMessage, userMessage)}
        _uiState.update { it.copy(isNewConversation = false) }

        viewModelScope.launch {
            try {
                inferenceEngine.sendUserPrompt(message).collect { token ->
                    if (token.isNotEmpty()) {
                        appendToAssistant(assistantId, token)
                    }
                }
                finishAssistantStream(assistantId)
            } catch (e: CancellationException) {
                finishAssistantStream(assistantId)
                throw e
            } catch (e: Exception) {
                finishAssistantStream(assistantId)
                postError(e.message ?: "Generation failed")
            }
        }
    }

    fun sendImageMessage(message: String, imageUri: String) {
        if (message.isBlank()) return
        if (_modelState.value !is InferenceEngine.State.ModelReady) {
            postError("Model is not ready yet")
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.User,
            text = message,
            timestamp = currentTime(),
            imageUri = imageUri,
        )

        val assistantId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            text = "",
            timestamp = currentTime(),
            isStreaming = true,
        )

        _messages.update { listOf(assistantMessage, userMessage) + it }
        _uiState.update { it.copy(isNewConversation = false) }

        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    imageDecoder.decode(imageUri.toUri())
                }
                inferenceEngine.sendUserPromptWithImage(bitmap, message).collect { token ->
                    if (token.isNotEmpty()) {
                        appendToAssistant(assistantId, token)
                    }
                }
                finishAssistantStream(assistantId)
            } catch (e: CancellationException) {
                finishAssistantStream(assistantId)
                throw e
            } catch (e: Exception) {
                finishAssistantStream(assistantId)
                postError(e.message ?: "Image generation failed")
            }
        }
    }

    override fun onCleared() {
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

    private fun appendToAssistant(messageId: String, token: String) {
        _messages.update { messages ->
            messages.map { message ->
                if (message.id == messageId) {
                    message.copy(text = message.text + token, isStreaming = true)
                } else {
                    message
                }
            }
        }
    }

    private fun finishAssistantStream(messageId: String) {
        _messages.update { messages ->
            messages.map { message ->
                if (message.id == messageId) {
                    message.copy(isStreaming = false)
                } else {
                    message
                }
            }
        }
    }

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