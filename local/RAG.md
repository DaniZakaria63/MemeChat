# Naive RAG + Conversation Memory Implementation Plan

## Architecture Overview

```
ChatViewModel ──> MemoryService (Naive RAG) ──> MessageDao (Room, :local)
                │
                └──> InferenceEngine ──> JNI ──> LLMInference (C++, memelm)
                     │                      │
                     │  sendConversation()  │  processConversation(chatML, resetFirst)
                     │                      │
                     └── with image ────────┘  processImageAndText(bitmap, prompt, resetFirst, forReasoning)
```

| Module | What changes |
|--------|-------------|
| `:local` | New: Room entities, DAOs, database, DI module, `MemoryService` |
| `:memelm` | New C++ methods, new JNI bridge, new Kotlin API |
| `:app` | `ChatViewModel` builds ChatML, integrates `MemoryService`, reasoning toggle |

---

## Core Insight: KV Cache Persistence

The llama.cpp KV cache **holds the entire conversation** once context reset stops happening. This means:

| Scenario | resetFirst | What to send to C++ |
|----------|-----------|-------------------|
| **New conversation** | `true` | Full ChatML: system + all messages → assistant turn |
| **Continuing existing** | `false` | Short turn snippet only: user message → assistant turn |
| **Reload saved from Room** | `true` | Full ChatML: system + all history from Room → assistant turn |

The KV cache already has everything before the current turn — no need to re-send history.

---

## Phase 1: Room Database (`:local` module)

### 1.1 Entity: `ConversationEntity`

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val createdAt: Long,
)
```

### 1.2 Entity: `MessageEntity`

```kotlin
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val reasoning: String,
    val timestamp: Long,
    val imageUri: String? = null,
)
```

### 1.3 DAOs

```kotlin
@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}
```

```kotlin
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE role = 'User' ORDER BY timestamp ASC")
    suspend fun getAllUserMessages(): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
```

### 1.4 Database Class

```kotlin
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
```

### 1.5 Hilt DI Module

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "memechat.db")
            .build()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
}
```

---

## Phase 2: Naive RAG (MemoryService)

### 2.1 Keyword Search + Cosine Similarity

```kotlin
@Singleton
class MemoryService @Inject constructor(
    private val messageDao: MessageDao,
) {
    data class KeywordMatchResult(
        val text: String,
        val keywordScore: Int,
        val cosineSimilarity: Float,
    )

    companion object {
        const val MIN_KEYWORD_MATCH = 2
    }

    suspend fun findBestMatch(query: String): KeywordMatchResult? {
        val queryKeywords = tokenize(query)
        val records = messageDao.getAllUserMessages()
        if (records.isEmpty()) return null

        var bestScore = 0
        var bestRecord: MessageEntity? = null

        for (record in records) {
            val recordKeywords = tokenize(record.text)
            val keywordScore = queryKeywords.keys.intersect(recordKeywords.keys).size

            if (keywordScore >= MIN_KEYWORD_MATCH && keywordScore > bestScore) {
                bestScore = keywordScore
                bestRecord = record
            }
        }

        return bestRecord?.let {
            val cosSim = cosineSimilarity(tokenize(query), tokenize(it.text))
            KeywordMatchResult(text = it.text, keywordScore = bestScore, cosineSimilarity = cosSim)
        }
    }

    /**
     * Equivalent to sklearn's CountVectorizer.
     * Tokenizes text and computes term frequencies.
     */
    private fun tokenize(text: String): Map<String, Int> {
        return text.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
    }

    /**
     * Equivalent to sklearn's cosine_similarity.
     * cos(θ) = (A · B) / (||A|| * ||B||)
     */
    private fun cosineSimilarity(tf1: Map<String, Int>, tf2: Map<String, Int>): Float {
        val allWords = tf1.keys + tf2.keys
        var dotProduct = 0f
        var magnitudeA = 0f
        var magnitudeB = 0f

        for (word in allWords) {
            val f1 = (tf1[word] ?: 0).toFloat()
            val f2 = (tf2[word] ?: 0).toFloat()
            dotProduct += f1 * f2
            magnitudeA += f1 * f1
            magnitudeB += f2 * f2
        }

        val denom = sqrt(magnitudeA) * sqrt(magnitudeB)
        return if (denom > 0f) dotProduct / denom else 0f
    }

    /**
     * Augmented input = query + ": " + best matching record.
     * Falls back to original query if quality gate fails.
     */
    suspend fun augmentQuery(query: String): String {
        val result = findBestMatch(query)
        return if (result != null && result.keywordScore >= MIN_KEYWORD_MATCH) {
            Timber.d("NaiveRAG: match score=%d cosSim=%.4f".format(result.keywordScore, result.cosineSimilarity))
            "$query: ${result.text}"
        } else {
            query
        }
    }
}
```

### sklearn equivalence

```
CountVectorizer().fit_transform   tokenize() -> Map<word, freq>
cosine_similarity(vec1, vec2)     cosineSimilarity(tf1, tf2)
set intersection of keywords      queryKeywords.keys.intersect(recordKeywords.keys)
```

---

## Phase 3: KV Cache Persistence Fix (`:memelm`)

### Problem

`nativeResetContext()` is called before every prompt — clears KV cache. `buildPrompt()` only includes system + single user message. No history.

### Solution

Key design: **Kotlin owns prompt building for text** — C++ just receives the final ChatML string. **C++ owns prompt building for images** — only it can call `mtmd_default_marker()`.

So `forReasoning` is:
- **Not needed** in `processConversation()` — `<think>` is baked into ChatML by Kotlin's `ChatMLBuilder`
- **Needed** in `processImageAndText()` — C++ constructs the prompt string and must know whether to insert `<think>`

### 3.1 C++: `LLMInference.h`

```cpp
class LLMInference {
public:
    // Process pre-formatted ChatML string from Kotlin.
    // resetFirst=true  → clears KV cache, full ChatML from Kotlin (system + all messages)
    // resetFirst=false → appends to KV cache, only user turn from Kotlin
    // Kotlin already baked <think> into the ChatML if reasoning is enabled.
    std::string processConversation(const char* chatML, bool resetFirst, const TokenCallback* cb = nullptr);

    // Image+text inference with KV cache persistence.
    // C++ builds the prompt because only it can call mtmd_default_marker().
    // forReasoning is needed here because C++ constructs the prompt string.
    std::string processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt,
                                     bool resetFirst, bool forReasoning,
                                     const TokenCallback* cb = nullptr);
};
```

### 3.2 C++: `LLMInference.cpp`

#### `processConversation()` — text path (no forReasoning needed)

```cpp
string LLMInference::processConversation(const char* chatML, bool resetFirst, const TokenCallback* cb) {
    if (!m_ctx) {
        LOGe("processConversation: engine not initialized");
        return "";
    }

    if (resetFirst) {
        resetContext();
        LOGi("processConversation: context reset for new conversation");
    }

    string full_prompt(chatML);
    // No prompt building here — Kotlin's ChatMLBuilder already formatted everything,
    // including <think> if reasoning is enabled. Just tokenize and evaluate.

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    int res = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, nullptr, 0);
    if (res != 0) {
        LOGe("processConversation: mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (!hasContextHeadroom(256)) {
        LOGw("processConversation: not enough context");
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, m_n_past, 0, 512, true, &m_n_past) != 0) {
        LOGe("processConversation: mtmd_helper_eval_chunks failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    LOGi("processConversation: evaluated, n_past=%d, generating...", (int)m_n_past);
    return generateTokens(512, cb);
}
```

#### `processImageAndText()` — image path (keeps forReasoning)

```cpp
string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt,
                                          bool resetFirst, bool forReasoning,
                                          const TokenCallback* cb) {
    if (!m_mtmd_ctx || !m_ctx) {
        LOGe("processImageAndText: engine not initialized");
        return "";
    }

    if (resetFirst) {
        resetContext();
        LOGi("processImageAndText: context reset for new conversation");
    }

    std::vector<uint8_t> rgb = bitmapToRGB(env, bitmap);
    if (rgb.empty()) return "";

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);

    mtmd_bitmap* bmp = mtmd_bitmap_init(
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            rgb.data()
    );
    if (!bmp) {
        LOGe("processImageAndText: mtmd_bitmap_init failed");
        return "";
    }

    // C++ builds the prompt because only it can call mtmd_default_marker()
    const string full_prompt = resetFirst
        ? buildImagePrompt(m_mtmd_ctx, m_systemPrompt, prompt, forReasoning)
        : buildImageTurnPrompt(prompt, forReasoning);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks*    chunks     = mtmd_input_chunks_init();
    const mtmd_bitmap*    bitmaps[]  = { bmp };
    const int             res        = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bmp);

    if (res != 0) {
        LOGe("processImageAndText: mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (!hasContextHeadroom(512)) {
        LOGw("processImageAndText: not enough context");
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, m_n_past, 0, 512, true, &m_n_past) != 0) {
        LOGe("processImageAndText: mtmd_helper_eval_chunks failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    LOGi("processImageAndText: evaluated, n_past=%d, generating...", (int)m_n_past);
    return generateTokens(512, cb);
}
```

#### New helper: `buildImageTurnPrompt()` — continuation only

```cpp
string LLMInference::buildImageTurnPrompt(const string& userPrompt, bool forReasoning) {
    const char* marker = mtmd_default_marker();
    string p;
    p += TOK_IM_START; p += TOK_USER; p += "\n";
    p += marker;
    p += "\n";
    p += userPrompt;
    p += TOK_IM_END;   p += "\n";
    p += TOK_IM_START; p += TOK_ASSISTANT;
    if (forReasoning) p += TOK_THINK_START;  p += "\n";
    return p;
}
```

### 3.3 JNI Bridge: `memelm.cpp`

```cpp
// Text path — no forReasoning (baked into ChatML by Kotlin)
JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessConversation(
    JNIEnv *env, jobject, jstring chatML, jboolean resetFirst, jobject tokenCallback) {

    TokenCallback cb{};
    jclass cls      = env->GetObjectClass(tokenCallback);
    cb.env          = env;
    cb.obj          = tokenCallback;
    cb.onToken      = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cls, "onComplete", "()V");

    if (cb.onToken == nullptr) {
        LOGe("GetMethodID failed");
        env->ExceptionClear();
        return;
    }
    env->DeleteLocalRef(cls);

    const char *promptStr = env->GetStringUTFChars(chatML, nullptr);
    g_inference.processConversation(promptStr, resetFirst, &cb);
    env->ReleaseStringUTFChars(chatML, promptStr);
    if (onComplete) env->CallVoidMethod(tokenCallback, onComplete);
}

// Image path — needs forReasoning (C++ builds prompt with mtmd_default_marker)
JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessImageAndTextV2(
    JNIEnv *env, jobject, jobject bitmap,
    jstring prompt, jboolean resetFirst, jboolean forReasoning, jobject tokenCallback) {

    TokenCallback cb{};
    jclass cls      = env->GetObjectClass(tokenCallback);
    cb.env          = env;
    cb.obj          = tokenCallback;
    cb.onToken      = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cls, "onComplete", "()V");

    if (cb.onToken == nullptr) {
        LOGe("GetMethodID failed");
        env->ExceptionClear();
        return;
    }
    env->DeleteLocalRef(cls);

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    g_inference.processImageAndText(env, bitmap, promptStr, resetFirst, forReasoning, &cb);
    env->ReleaseStringUTFChars(prompt, promptStr);
    if (onComplete) env->CallVoidMethod(tokenCallback, onComplete);
}
```

The old `nativeProcessTextOnly` and `nativeProcessImageAndText` (old signatures) remain for backward compat but become unused after migration.

### 3.4 Kotlin: `InferenceEngine` Interface

```kotlin
interface InferenceEngine {
    // ... existing methods unchanged ...

    // NEW: Text-only with KV cache persistence.
    // forReasoning NOT needed — ChatMLBuilder bakes <think> into the string.
    fun sendConversation(chatML: String, resetFirst: Boolean): Flow<Pair<STATE, String>>

    // NEW: Image+text with KV cache persistence.
    // forReasoning IS needed — C++ builds the prompt with mtmd_default_marker().
    fun sendConversationWithImage(
        bitmap: Bitmap,
        message: String,
        resetFirst: Boolean,
        forReasoning: Boolean,
    ): Flow<Pair<STATE, String>>
}
```

### 3.5 Kotlin: `InferenceEngineImpl`

```kotlin
@FastNative
external fun nativeProcessConversation(
    chatML: String,
    resetFirst: Boolean,
    tokenCallback: StreamCallback,
)

@FastNative
external fun nativeProcessImageAndTextV2(
    bitmap: Bitmap,
    prompt: String,
    resetFirst: Boolean,
    forReasoning: Boolean,
    tokenCallback: StreamCallback,
)

override fun sendConversation(
    chatML: String,
    resetFirst: Boolean,
): Flow<Pair<STATE, String>> = callbackFlow {
    require(chatML.isNotEmpty()) { "ChatML cannot be empty" }
    check(state.value.isModelLoaded) { "Model not ready" }

    _state.value = InferenceEngine.State.Generating
    // nativeProcessConversation handles reset internally via resetFirst flag

    try {
        var inThinking = true
        val callback = object : StreamCallback {
            override fun onToken(token: String) {
                when {
                    inThinking && token.contains("</think>") -> inThinking = false
                    inThinking -> trySend(Pair(STATE.THINKING, token))
                    else -> trySend(Pair(STATE.ANSWER, token))
                }
            }
            override fun onComplete() { close() }
        }
        nativeProcessConversation(chatML, resetFirst, callback)
    } catch (e: CancellationException) {
        _state.value = InferenceEngine.State.ModelReady; close(); throw e
    } catch (e: Exception) {
        _state.value = InferenceEngine.State.Error(e); close(e)
    } finally {
        _state.value = InferenceEngine.State.ModelReady; close(); awaitClose()
    }
}.flowOn(llamaDispatcher)

override fun sendConversationWithImage(
    bitmap: Bitmap,
    message: String,
    resetFirst: Boolean,
    forReasoning: Boolean,
): Flow<Pair<STATE, String>> = callbackFlow {
    check(state.value.isModelLoaded) { "Model not ready" }

    _state.value = InferenceEngine.State.Generating
    val scaledBitmap = prepareImageForModel(bitmap)

    try {
        var inThinking = true
        val callback = object : StreamCallback {
            override fun onToken(token: String) {
                when {
                    inThinking && token.contains("</think>") -> inThinking = false
                    inThinking -> trySend(Pair(STATE.THINKING, token))
                    else -> trySend(Pair(STATE.ANSWER, token))
                }
            }
            override fun onComplete() { close() }
        }
        nativeProcessImageAndTextV2(scaledBitmap, message, resetFirst, forReasoning, callback)
    } catch (e: CancellationException) {
        _state.value = InferenceEngine.State.ModelReady; close(); throw e
    } catch (e: Exception) {
        _state.value = InferenceEngine.State.Error(e); close(e)
    } finally {
        _state.value = InferenceEngine.State.ModelReady; close(); awaitClose()
    }
}.flowOn(llamaDispatcher)


```

### 3.6 ChatML Builder Utility

```kotlin
object ChatMLBuilder {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    private const val THINK = "<think>\n"

    /**
     * Build full ChatML for a new or reloaded conversation.
     * Includes system prompt + full message history + assistant turn header.
     * resetFirst=true should be used with this.
     */
    fun buildFull(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        forReasoning: Boolean = false,
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("${IM_START}system\n")
            sb.append(systemPrompt)
            sb.append("$IM_END\n")
        }
        for ((role, content) in messages) {
            sb.append("$IM_START$role\n")
            sb.append(content)
            sb.append("$IM_END\n")
        }
        sb.append("${IM_START}assistant\n")
        if (forReasoning) sb.append(THINK)
        return sb.toString()
    }

    /**
     * Build continuation turn snippet for ongoing conversation.
     * Only the user turn + assistant header. NO system prompt, NO history.
     * resetFirst=false should be used with this — KV cache already holds history.
     */
    fun buildTurn(
        userMessage: String,
        forReasoning: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.append("${IM_START}user\n")
        sb.append(userMessage)
        sb.append("$IM_END\n")
        sb.append("${IM_START}assistant\n")
        if (forReasoning) sb.append(THINK)
        return sb.toString()
    }

    fun buildFullFromHistory(
        systemPrompt: String,
        history: List<ChatMessage>,
        forReasoning: Boolean = false,
    ): String {
        val messages = history.map { msg ->
            when (msg.role) {
                ChatRole.User -> "user" to msg.text
                ChatRole.Assistant -> "assistant" to msg.text
                ChatRole.System -> "system" to msg.text
            }
        }
        return buildFull(systemPrompt, messages, forReasoning)
    }
}
```

---

## Phase 4: ViewModel Integration (`ChatViewModel`)

### 4.1 Updated `ChatUiState` — add `isThinking`

```kotlin
data class ChatUiState(
    val isNewConversation: Boolean = true,
    val isProcessing: Boolean = false,
    val selectedImageUri: String? = null,
    val error: String? = null,
    val isRecording: Boolean = false,
    val isThinkingEnabled: Boolean = false,   // NEW: reasoning toggle
)
```

### 4.2 Injections

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val imageDecoder: ImageDecoder,
    private val inferenceEngine: InferenceEngine,
    private val memoryService: MemoryService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
) : BaseViewModel() {
```

### 4.3 Modified `sendMessage`

```kotlin
private var currentConversationId: String? = null
private var currentConversationCreatedAt: Long = 0L

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
        // 1. Naive RAG
        val augmentedInput = memoryService.augmentQuery(message)

        // 2. Save user message to Room
        val userMsgId = UUID.randomUUID().toString()
        messageDao.insert(MessageEntity(
            id = userMsgId,
            conversationId = conversationId,
            role = "User",
            text = message,
            reasoning = "",
            timestamp = System.currentTimeMillis(),
            imageUri = imageUri,
        ))

        // 3. Create in-memory messages for UI
        val userMessage = ChatMessage(
            id = userMsgId,
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
            timestamp = "",
            isStreaming = true,
        )
        _messages.update { listOf(assistantMessage, userMessage) + it }
        _uiState.update { it.copy(isNewConversation = false) }

        // 4. Send to inference engine
        var responseText = ""
        if (imageUri == null) {
            // Text-only: build ChatML based on conversation state
            val chatML = if (isNewConversation) {
                val history = _messages.value.reversed() // newest last for history
                ChatMLBuilder.buildFullFromHistory(
                    systemPrompt = DEFAULT_MODEL_SYSTEM_PROMPT,
                    history = history.map { it.toChatMessage() },
                    forReasoning = forReasoning,
                )
            } else {
                // Continuation: KV cache holds the rest
                ChatMLBuilder.buildTurn(
                    userMessage = augmentedInput,
                    forReasoning = forReasoning,
                )
            }
            inferenceEngine.sendConversation(chatML, resetFirst = isNewConversation)
                .collect { (state, token) -> handleToken(state, token, assistantId, responseText) }
        } else {
            // Image: always a new visual context
            val bitmap = withContext(Dispatchers.IO) {
                imageDecoder.decode(imageUri.toUri())
            }
            inferenceEngine.sendConversationWithImage(
                bitmap, augmentedInput,
                resetFirst = isNewConversation,
                forReasoning = forReasoning,
            ).collect { (state, token) -> handleToken(state, token, assistantId, responseText) }
        }

        // 5. Save assistant response to Room
        val finalMsg = _messages.value.find { it.id == assistantId }
        messageDao.insert(MessageEntity(
            id = assistantId,
            conversationId = conversationId,
            role = "Assistant",
            text = responseText,
            reasoning = finalMsg?.reasoning ?: "",
            timestamp = System.currentTimeMillis(),
        ))

        // 6. Update conversation preview
        conversationDao.insert(ConversationEntity(
            id = conversationId,
            title = message.take(50),
            preview = responseText.take(80),
            updatedAt = System.currentTimeMillis(),
            createdAt = if (isNewConversation) System.currentTimeMillis() else currentConversationCreatedAt,
        ))

        finishAssistantStream(assistantId)
    }
}

// Delegate token handling to avoid duplication
private fun handleToken(state: STATE, token: String, assistantId: String, responseText: String) {
    when (state) {
        STATE.THINKING -> appendReasoningToAssistant(assistantId, token)
        STATE.ANSWER -> {
            appendToAssistant(assistantId, token)
            responseText += token
        }
        STATE.FINISH -> { }
    }
}
```

### 4.4 Reasoning Toggle

```kotlin
// In ChatViewModel
fun toggleThinking() {
    _uiState.update { it.copy(isThinkingEnabled = !it.isThinkingEnabled) }
    Timber.d("Reasoning mode: ${_uiState.value.isThinkingEnabled}")
}
```

### 4.5 Wire reasoning toggle in UI (`ChatScreen.kt`)

In the `InputBar` or toolbar area, add a toggle button:

```kotlin
// Alongside the model name button
ModelButton(
    onClick = { viewModel.toggleThinking() },
    icon = if (uiState.isThinkingEnabled) Icons.Filled.Psychology else Icons.Outlined.Psychology,
    text = if (uiState.isThinkingEnabled) "Thinking ON" else "Thinking OFF",
    isHighlight = uiState.isThinkingEnabled,
)
```

Update `ChatUiState` consumption in `ChatScreen` to wire `isThinkingEnabled`.

### 4.6 Load conversation from Room

```kotlin
fun loadConversation(conversationId: String) {
    safeViewModelScope.launch {
        currentConversationId = conversationId
        val conv = conversationDao.getConversation(conversationId)
        if (conv == null) return@launch
        currentConversationCreatedAt = conv.createdAt
        _uiState.update { it.copy(isNewConversation = false) }

        val messages = messageDao.getMessages(conversationId)
        _messages.value = messages.reversed().map { it.toChatMessage() }
    }
}
```

### 4.7 Wire conversation list drawer

```kotlin
val conversations: StateFlow<List<DummyConversation>> =
    conversationDao.getAllConversations().map { entities ->
        entities.map { entity ->
            DummyConversation(
                id = entity.id,
                title = entity.title,
                preview = entity.preview,
                time = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(entity.updatedAt)),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Update `ChatScreen` to wire `viewModel.conversations` instead of `dummyConversations`.

### 4.8 `startNewConversation` updated

```kotlin
fun startNewConversation() {
    safeViewModelScope.launch {
        _messages.value = emptyList()
        _uiState.update { it.copy(isNewConversation = true, selectedImageUri = null) }
        inferenceEngine.cancelGeneration()
        currentConversationId = null
    }
}
```

---

## Phase 5: Migration Path

1. Add `processConversation()` + updated `processImageAndText()` to C++ (`LLMInference.h/.cpp`)
2. Add `nativeProcessConversation` + `nativeProcessImageAndTextV2` JNI methods in `memelm.cpp`
3. Add `sendConversation()` + `sendConversationWithImage()` to `InferenceEngine` interface
4. Implement both in `InferenceEngineImpl` with new JNI externals
5. Add `ChatMLBuilder` utility (in `:app` or `:memelm`)
6. Update `ChatViewModel.sendMessage()` to use new API
7. Add `isThinkingEnabled` to `ChatUiState`, wire toggle in `ChatScreen`
8. Update `ChatScreen` to wire conversation list from Room data

Old `sendUserPrompt()` and `sendUserPromptWithImage()` remain for backward compat but become unused after migration. Similarly `nativeProcessTextOnly`, `nativeProcessImageAndText` (old signatures) remain in JNI.

---

## Data Flow Examples

### New text conversation

```
User types "what is a meme?"  (thinking=OFF)
  │
  ├─ MemoryService.augmentQuery — finds no good match, returns original
  │
  ├─ ChatMLBuilder.buildFull()
  │   <|im_start|>system
  │   You are Aoi...
  │   <|im_end|>
  │   <|im_start|>user
  │   what is a meme?
  │   <|im_end|>
  │   <|im_start|>assistant
  │
  ├─ sendConversation(chatML, resetFirst=true)
  │   └─ C++: resetContext → tokenize → eval → generate → advance m_n_past


  └─ Save to Room
```

### Continuing same conversation

```
User types "explain further"  (thinking=ON)
  │
  ├─ MemoryService.augmentQuery — match found? augment.
  │
  ├─ ChatMLBuilder.buildTurn("explain further", forReasoning=true)
  │   <|im_start|>user
  │   explain further
  │   <|im_end|>
  │   <|im_start|>assistant
  │   <think>
  │
  ├─ sendConversation(turnSnippet, resetFirst=false)
  │   └─ C++: NO reset → tokenize → eval → generate (KV cache has prior turns)
  │
  └─ Save to Room
```

### New image conversation

```
User sends image + "what is this?"  (thinking=OFF)
  │
  ├─ sendConversationWithImage(bitmap, prompt, resetFirst=true, forReasoning=false)
  │   └─ C++: resetContext → buildImagePrompt(system + image + text)
  │         → mtmd_tokenize → eval → generate → advance m_n_past
  │
  └─ Save to Room
```

### Reloading saved conversation + continuing

```
User opens saved conversation from drawer
  │
  ├─ loadConversation("conv-123")
  │   ├─ messageDao.getMessages → reconstruct in-memory list
  │   └─ _messages.value = reversed list for UI
  │
User types "tell me more"  (thinking=ON)
  │
  ├─ isNewConversation = false, but this is a reload from DB
  │   → KV cache is empty → need full re-send = resetFirst=true
  │
  ├─ ChatMLBuilder.buildFullFromHistory(system, allMessages + newUserMsg, forReasoning=true)
  │
  ├─ sendConversation(fullChatML, resetFirst=true)
  │   └─ C++: resetContext → full re-tokenize → eval → generate
  │
  └─ Save to Room
```

---

## Summary of Changes by File

### New files

| File | Purpose |
|------|---------|
| `local/.../data/ConversationEntity.kt` | Room entity for conversations |
| `local/.../data/MessageEntity.kt` | Room entity for messages |
| `local/.../data/ConversationDao.kt` | DAO for conversations |
| `local/.../data/MessageDao.kt` | DAO for messages |
| `local/.../data/AppDatabase.kt` | Room database class |
| `local/.../di/DatabaseModule.kt` | Hilt module for Room |
| `local/.../service/MemoryService.kt` | Naive RAG (keyword + cosine similarity) |
| `app/.../ChatMLBuilder.kt` | ChatML formatting utility |

### Modified files

| File | Change |
|------|--------|
| `memelm/.../LLMInference.h` | Add `processConversation()`, update `processImageAndText()` signature |
| `memelm/.../LLMInference.cpp` | Add `processConversation()`, `buildImageTurnPrompt()`, update `processImageAndText()` |
| `memelm/.../memelm.cpp` | Add JNI for new native methods |
| `memelm/.../InferenceEngine.kt` | Add `sendConversation()`, `sendConversationWithImage()` |
| `memelm/.../InferenceEngineImpl.kt` | Implement new methods, add JNI externals |
| `app/.../ChatViewModel.kt` | Inject MemoryService + DAOs, rewrite sendMessage, add toggleThinking |
| `app/.../model/AppModels.kt` | Add `isThinkingEnabled` to `ChatUiState` |
| `app/.../ui/screen/ChatScreen.kt` | Wire conversation list, add reasoning toggle button |
