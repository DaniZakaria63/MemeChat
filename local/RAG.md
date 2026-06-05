# Naive RAG + Conversation Memory Implementation Plan

## Architecture Overview

```
ChatViewModel ──> MemoryService (Naive RAG) ──> MessageDao (Room, :local)
                │
                └──> InferenceEngine ──> JNI ──> LLMInference (C++, memelm)
                     │
                     new: processConversation(promptChatML, resetFirst)
```

| Module | What changes |
|--------|-------------|
| `:local` | New: Room entities, DAOs, database, DI module, `MemoryService` |
| `:memelm` | New C++ `processConversation()`, new JNI bridge, new Kotlin API |
| `:app` | `ChatViewModel` builds ChatML, calls `sendConversation()`, integrates `MemoryService` |

---

## Phase 1: Room Database (`:local` module)

### 1.1 Entity: `ConversationEntity`

```kotlin
// local/src/main/java/fun/walawe/local/data/ConversationEntity.kt
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,    // epoch millis for sorting
    val createdAt: Long,
)
```

### 1.2 Entity: `MessageEntity`

```kotlin
// local/src/main/java/fun/walawe/local/data/MessageEntity.kt
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,              // "User", "Assistant", "System"
    val text: String,
    val reasoning: String,
    val timestamp: Long,
    val imageUri: String? = null,
)
```

### 1.3 DAOs

```kotlin
// local/src/main/java/fun/walawe/local/data/ConversationDao.kt
@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)
}
```

```kotlin
// local/src/main/java/fun/walawe/local/data/MessageDao.kt
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE role = 'User' ORDER BY timestamp ASC")
    suspend fun getAllUserMessages(): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
```

### 1.4 Database Class

```kotlin
// local/src/main/java/fun/walawe/local/data/AppDatabase.kt
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
```

### 1.5 Hilt DI Module

```kotlin
// local/src/main/java/fun/walawe/local/di/DatabaseModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "memechat.db")
            .build()

    @Provides fun provideConversationDao(db: AppDatabase) = db.conversationDao()
    @Provides fun provideMessageDao(db: AppDatabase) = db.messageDao()
}
```

---

## Phase 2: Naive RAG (MemoryService)

### 2.1 Keyword Search + Cosine Similarity

```kotlin
// local/src/main/java/fun/walawe/local/service/MemoryService.kt
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
        const val MIN_KEYWORD_MATCH = 2   // quality gate threshold
    }

    /**
     * Find the best matching record from all user messages using
     * keyword set intersection (primary) + cosine similarity (metric).
     *
     * Equivalent to: sklearn.feature_extraction.text.CountVectorizer
     *                + sklearn.metrics.pairwise.cosine_similarity
     */
    suspend fun findBestMatch(query: String): KeywordMatchResult? {
        val queryKeywords = tokenize(query)
        val records = messageDao.getAllUserMessages()
        if (records.isEmpty()) return null

        var bestScore = 0
        var bestRecord: MessageEntity? = null

        for (record in records) {
            val recordKeywords = tokenize(record.text)
            val keywordScore = queryKeywords.intersect(recordKeywords).size

            // Only consider records that pass the quality gate
            if (keywordScore >= MIN_KEYWORD_MATCH && keywordScore > bestScore) {
                bestScore = keywordScore
                bestRecord = record
            }
        }

        // Among best keyword match, compute cosine similarity as metric
        return bestRecord?.let {
            val cosSim = cosineSimilarity(tokenize(query), tokenize(it.text))
            KeywordMatchResult(text = it.text, keywordScore = bestScore, cosineSimilarity = cosSim)
        }
    }

    /**
     * CountVectorizer equivalent: tokenize -> count term frequencies
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
     * Cosine similarity between two term frequency vectors.
     * Equivalent to sklearn's cosine_similarity(CountVectorizer output).
     *
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

        val denom = kotlin.math.sqrt(magnitudeA) * kotlin.math.sqrt(magnitudeB)
        return if (denom > 0f) dotProduct / denom else 0f
    }

    /**
     * Augmented input = query + ": " + best matching record
     * Only returns augmented text if quality gate passes.
     */
    suspend fun augmentQuery(query: String): String {
        val result = findBestMatch(query)
        return if (result != null && result.keywordScore >= MIN_KEYWORD_MATCH) {
            Timber.d("NaiveRAG: match score=${result.keywordScore} cosSim=%.4f".format(result.cosineSimilarity))
            "$query: ${result.text}"
        } else {
            query  // fallback to original query
        }
    }
}
```

### How this maps to sklearn

```
sklearn                          Kotlin equivalent
────────────────────────────────────────────────────
CountVectorizer().fit_transform   tokenize() -> Map<word, freq>
cosine_similarity(vec1, vec2)     cosineSimilarity(tf1, tf2)
set intersection of keywords      queryKeywords.intersect(recordKeywords)
```

---

## Phase 3: KV Cache Persistence Fix (`:memelm`)

### Problem

`nativeResetContext()` is called before every prompt in `InferenceEngineImpl.sendUserPrompt()`, clearing the KV cache entirely. `buildPrompt()` only wraps system + single user message — no history.

### Solution: `processConversation()` in C++

New API accepts **pre-formatted ChatML** (built in Kotlin from conversation history) with a `resetFirst` flag.

### 3.1 C++: `LLMInference.h`

```cpp
class LLMInference {
public:
    // NEW: Process a fully-formed ChatML prompt string.
    // - If resetFirst=true, clears KV cache before processing (new conversation).
    // - If resetFirst=false, appends to existing KV cache (continuing conversation).
    std::string processConversation(const char* chatML, bool resetFirst, const TokenCallback* cb = nullptr);
};
```

### 3.2 C++: `LLMInference.cpp`

```cpp
string LLMInference::processConversation(const char* chatML, bool resetFirst, const TokenCallback* cb) {
    if (!m_ctx) {
        LOGe("processConversation: engine not initialized");
        return "";
    }

    // Optionally reset context for new conversation
    if (resetFirst) {
        resetContext();
        LOGi("processConversation: context reset for new conversation");
    }

    string full_prompt(chatML);

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

### 3.3 JNI Bridge: `memelm.cpp`

```cpp
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
        LOGe("GetMethodID failed — onToken not found on callback object");
        env->ExceptionClear();
        return;
    }
    env->DeleteLocalRef(cls);

    const char *promptStr = env->GetStringUTFChars(chatML, nullptr);
    g_inference.processConversation(promptStr, resetFirst, &cb);
    env->ReleaseStringUTFChars(chatML, promptStr);
    if (onComplete) {
        env->CallVoidMethod(tokenCallback, onComplete);
    }
}
```

### 3.4 Kotlin: `InferenceEngine` Interface

```kotlin
interface InferenceEngine {
    // ... existing methods ...

    // NEW: Process a pre-formatted ChatML string.
    // resetFirst=true for new conversations (clears KV cache).
    // resetFirst=false for continuing existing conversation.
    fun sendConversation(chatML: String, resetFirst: Boolean): Flow<Pair<STATE, String>>
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

override fun sendConversation(chatML: String, resetFirst: Boolean): Flow<Pair<STATE, String>> =
    callbackFlow {
        require(chatML.isNotEmpty()) { "ChatML cannot be empty" }
        check(state.value.isModelLoaded) { "Model not ready" }

        // Do NOT call nativeResetContext() here!
        // resetFirst is handled inside native processConversation
        _state.value = InferenceEngine.State.Generating

        try {
            var inThinking = true
            val callback = object : StreamCallback {
                override fun onToken(token: String) {
                    when {
                        inThinking && token.contains("</think>") -> {
                            inThinking = false
                        }
                        inThinking -> trySend(Pair(STATE.THINKING, token))
                        else -> trySend(Pair(STATE.ANSWER, token))
                    }
                }
                override fun onComplete() {
                    close()
                }
            }
            nativeProcessConversation(chatML, resetFirst, callback)
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            close()
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            close(e)
        } finally {
            _state.value = InferenceEngine.State.ModelReady
            close()
            awaitClose()
        }
    }.flowOn(llamaDispatcher)
```

### 3.6 ChatML Builder Utility

```kotlin
// In :app or :memelm module
object ChatMLBuilder {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    fun build(
        systemPrompt: String,
        messages: List<Pair<String, String>>,  // (role, content)
        forReasoning: Boolean = false,
    ): String {
        val sb = StringBuilder()

        // System prompt
        if (systemPrompt.isNotBlank()) {
            sb.append("$IM_START"system"\n")
            sb.append(systemPrompt)
            sb.append("$IM_END\n")
        }

        // All messages
        for ((role, content) in messages) {
            sb.append("$IM_START$role\n")
            sb.append(content)
            sb.append("$IM_END\n")
        }

        // Assistant turn (with optional think tag)
        sb.append("$IM_START"assistant"\n")
        if (forReasoning) sb.append("<think>\n")

        return sb.toString()
    }

    fun buildFromHistory(
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
        return build(systemPrompt, messages, forReasoning)
    }
}
```

---

## Phase 4: ViewModel Integration (`ChatViewModel`)

### 4.1 Inject `MemoryService`

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val imageDecoder: ImageDecoder,
    private val inferenceEngine: InferenceEngine,
    private val memoryService: MemoryService,        // NEW
    private val messageDao: MessageDao,              // NEW
    private val conversationDao: ConversationDao,    // NEW
) : BaseViewModel() {
```

### 4.2 Modified `sendMessage`

```kotlin
private var currentConversationId: String? = null

fun sendMessage(message: String) {
    if (message.isBlank()) return
    if (_modelState.value !is InferenceEngine.State.ModelReady) {
        postError("Model is not ready yet")
        return
    }

    // 1. Get or create conversation
    val conversationId = currentConversationId ?: createNewConversation()

    // 2. Apply Naive RAG
    safeViewModelScope.launch {
        val augmentedInput = memoryService.augmentQuery(message)
        Timber.d("NaiveRAG: input='$message' -> augmented='$augmentedInput'")

        // 3. Build full ChatML from history + new input
        val currentMessages = _messages.value.reversed() // list is reversed in UI
        val chatML = ChatMLBuilder.buildFromHistory(
            systemPrompt = DEFAULT_MODEL_SYSTEM_PROMPT,
            history = currentMessages + ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatRole.User,
                text = augmentedInput,
                timestamp = currentTime(),
            ),
            forReasoning = false,
        )
        val isNewConversation = currentConversationId == null
        val resetFirst = isNewConversation

        // 4. Save user message to Room
        val userMsgId = UUID.randomUUID().toString()
        messageDao.insert(MessageEntity(
            id = userMsgId,
            conversationId = conversationId,
            role = "User",
            text = message,               // save original, not augmented
            reasoning = "",
            timestamp = System.currentTimeMillis(),
            imageUri = _uiState.value.selectedImageUri,
        ))

        // 5. Create in-memory messages for UI
        val userMessage = ChatMessage(
            id = userMsgId,
            role = ChatRole.User,
            text = message,
            timestamp = currentTime(),
            imageUri = _uiState.value.selectedImageUri,
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

        // 6. Send to inference engine (with KV cache persistence)
        var responseText = ""
        inferenceEngine.sendConversation(chatML, resetFirst).collect { (state, token) ->
            when (state) {
                STATE.THINKING -> appendReasoningToAssistant(assistantId, token)
                STATE.ANSWER -> {
                    appendToAssistant(assistantId, token)
                    responseText += token
                }
                STATE.FINISH -> { /* handled in finally */ }
            }
        }

        // 7. Save assistant response to Room
        val finalMsg = _messages.value.find { it.id == assistantId }
        messageDao.insert(MessageEntity(
            id = assistantId,
            conversationId = conversationId,
            role = "Assistant",
            text = responseText,
            reasoning = finalMsg?.reasoning ?: "",
            timestamp = System.currentTimeMillis(),
        ))

        // 8. Update conversation preview
        conversationDao.upsert(ConversationEntity(
            id = conversationId,
            title = message.take(50),
            preview = responseText.take(80),
            updatedAt = System.currentTimeMillis(),
            createdAt = if (isNewConversation) System.currentTimeMillis() else currentConversationCreatedAt,
        ))

        finishAssistantStream(assistantId)
    }
}

private var currentConversationCreatedAt: Long = 0L

private fun createNewConversation(): String {
    val id = UUID.randomUUID().toString()
    currentConversationId = id
    currentConversationCreatedAt = System.currentTimeMillis()
    return id
}
```

### 4.3 Load conversation from Room

```kotlin
fun loadConversation(conversationId: String) {
    safeViewModelScope.launch {
        currentConversationId = conversationId
        _uiState.update { it.copy(isNewConversation = false) }

        val messages = messageDao.getMessagesOnce(conversationId)
        // Reverse for UI order (newest first)
        _messages.value = messages.reversed().map { it.toChatMessage() }
    }
}

// Extension to convert MessageEntity -> ChatMessage
fun MessageEntity.toChatMessage() = ChatMessage(
    id = id,
    role = ChatRole.valueOf(role),
    text = text,
    timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)),
    imageUri = imageUri,
    reasoning = reasoning,
)
```

### 4.4 Wire conversation list drawer

```kotlin
// In ChatViewModel
val conversations: StateFlow<List<DummyConversation>> =
    conversationDao.getAllConversations().map { entities ->
        entities.map { entity ->
            DummyConversation(
                id = entity.id,
                title = entity.title,
                preview = entity.preview,
                time = formatTime(entity.updatedAt),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Update `ChatScreen` to pass `viewModel.conversations` instead of the empty `dummyConversations`.

### 4.5 `startNewConversation` updated

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

## Phase 5: Remove `nativeResetContext()` from `sendUserPrompt`

The existing `sendUserPrompt` (used for image+text) still calls `nativeResetContext()`. That's acceptable for the image case since multimodal conversations are single-turn. But for the text-only path, `sendConversation` must be used instead, and the old `sendUserPrompt` must NOT be called for text messages.

### Migration path

1. Add `sendConversation()` to `InferenceEngine` interface + impl
2. Add `nativeProcessConversation()` to JNI + C++
3. Update `ChatViewModel.sendMessage()` to use `sendConversation()` instead of `sendUserPrompt()`
4. Keep `sendUserPrompt` / `sendUserPromptWithImage` for backward compatibility (image use case)
5. Remove the `nativeResetContext()` call from `sendUserPrompt` in `InferenceEngineImpl` (or keep it — only used for images now)

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
| `memelm/../inference/ChatMLBuilder.kt` | ChatML formatting utility |

### Modified files

| File | Change |
|------|--------|
| `memelm/.../LLMInference.h` | Add `processConversation()` declaration |
| `memelm/.../LLMInference.cpp` | Add `processConversation()` implementation |
| `memelm/.../memelm.cpp` | Add `nativeProcessConversation` JNI bridge |
| `memelm/.../InferenceEngine.kt` | Add `sendConversation()` to interface |
| `memelm/.../InferenceEngineImpl.kt` | Implement `sendConversation()`, add JNI external |
| `app/.../ChatViewModel.kt` | Inject `MemoryService`, Room DAOs; rewrite `sendMessage` |
| `app/.../ChatScreen.kt` | Wire conversation list from ViewModel flow |

---

## Data Flow (after changes)

```
User types "what is a meme?"
  │
  ├─ 1. MemoryService.augmentQuery("what is a meme?")
  │     ├─ tokenize -> {what:1, is:1, a:1, meme:1}
  │     ├─ intersect with all user messages in Room
  │     ├─ best match: "memes are funny" (keywords: meme, match=1)
  │     └─ quality gate: 1 < MIN_KEYWORD_MATCH=2 → fallback
  │
  ├─ 2. augmentedInput = "what is a meme?"  (unchanged)
  │
  ├─ 3. Build ChatML:
  │     <|im_start|>system
  │     You are Aoi...
  │     <|im_end|>
  │     <|im_start|>assistant
  │     Hi there!<|im_end|>
  │     <|im_start|>user
  │     what is a meme?<|im_end|>
  │     <|im_start|>assistant
  │
  ├─ 4. sendConversation(chatML, resetFirst=false)
  │     └─ C++: append to KV cache (m_n_past continues)
  │           └─ generate tokens
  │
  ├─ 5. Save user + assistant messages to Room
  │
  └─ 6. Update conversation preview in Room
```
