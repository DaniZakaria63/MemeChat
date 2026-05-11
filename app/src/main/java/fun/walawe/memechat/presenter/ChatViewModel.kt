package `fun`.walawe.memechat.presenter

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.memechat.BuildConfig
import `fun`.walawe.memechat.data.ImageDecoder
import `fun`.walawe.memechat.data.InferenceRepository
import `fun`.walawe.memechat.data.ModelRepository
import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole
import `fun`.walawe.memechat.model.ChatUiState
import `fun`.walawe.memechat.model.ModelDescriptor
import `fun`.walawe.memechat.model.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val inferenceRepository: InferenceRepository,
    private val imageDecoder: ImageDecoder,
) : BaseViewModel() {
    private val _modelState: MutableStateFlow<ModelState> = MutableStateFlow(ModelState.Initializing)
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = combine(_uiState, _modelState, errorState){ ui, model, error ->
        ui.copy(isProcessing = model !is ModelState.Generating, error = error)
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
        if (_modelState.value !is ModelState.ModelReady) {
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

        _messages.update { listOf(assistantMessage, userMessage) + it }
        _uiState.update { it.copy(isNewConversation = false) }
        _modelState.update { ModelState.Generating }

        viewModelScope.launch {
            try {
                inferenceRepository.sendMessage(message).collect { token ->
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
        if (_modelState.value !is ModelState.ModelReady) {
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
        _modelState.update { ModelState.Generating }

        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    imageDecoder.decode(imageUri.toUri())
                }
                inferenceRepository.sendImageMessage(message, bitmap).collect { token ->
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
        inferenceRepository.destroy()
        super.onCleared()
    }

    private suspend fun prepareModel() {
        val modelDescriptor = modelRepository.getCachedModelDescriptor().getOrElse { error ->
            postError(error.message ?: "Model is not downloaded yet")
            return
        }

        _uiState.update { it.copy(modelDescriptor = modelDescriptor) }
        loadModel(modelDescriptor)
    }

    private suspend fun loadModel(descriptor: ModelDescriptor) {
        _modelState.update { ModelState.LoadingModel }
        try {
            inferenceRepository.loadModel(descriptor.path, BuildConfig.DEFAULT_SYSTEM_PROMPT)
            val mmprojFile = modelRepository.findMmprojFile(descriptor.path)
            if (mmprojFile != null) {
                inferenceRepository.initVision(
                    mmprojPath = mmprojFile.absolutePath,
                    mediaMarker = BuildConfig.DEFAULT_MEDIA_MARKER,
                    useGpu = true,
                    warmup = true,
                )
            }
            _modelState.update { ModelState.ModelReady }
        } catch (e: Exception) {
            postError(e.message ?: "Failed to initialize model")
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
        _modelState.update { ModelState.Generating }
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