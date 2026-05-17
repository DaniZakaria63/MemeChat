# Arm AI Chat Library - Comprehensive Documentation

**Created**: December 2025
**Library Purpose**: Android JNI wrapper for llama.cpp enabling large language model (LLM) inference on ARM-based Android devices.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Core Components](#core-components)
   - [Kotlin/Java Layer](#kotlinjava-layer)
   - [C++ JNI Layer](#c-jni-layer)
   - [GGUF Metadata Reader](#gguf-metadata-reader)
3. [Detailed Component Analysis](#detailed-component-analysis)
4. [Design Patterns and Reasoning](#design-patterns-and-reasoning)
5. [State Management](#state-management)
6. [Integration with llama.cpp](#integration-with-llamacpp)

---

## Architecture Overview

This library follows a **layered architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────────────┐
│  Android Application Layer                      │
│  (Uses AiChat.getInferenceEngine())             │
└────────────────┬────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────┐
│  Kotlin/Java API Layer                          │
│  InferenceEngine (Interface) & InferenceEngineImpl
│  State Management (StateFlow)                   │
└────────────────┬────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────┐
│  JNI Boundary                                   │
│  External native methods                        │
└────────────────┬────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────┐
│  C++ Native Layer (ai_chat.cpp)                 │
│  llama.cpp integration & token generation       │
│  Chat template management                       │
└────────────────┬────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────┐
│  GGML Backend Layer                             │
│  ARM CPU acceleration, OpenMP threading         │
│  Memory management, KV-cache                    │
└─────────────────────────────────────────────────┘
```

---

## Core Components

### Kotlin/Java Layer

#### 1. **AiChat.kt** - Public API Entry Point

**Purpose**: Provides a singleton object serving as the main entry point for the library.

```kotlin
object AiChat {
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
```

**Variables**:
- None (stateless object)

**Functions**:
- `getInferenceEngine(context: Context)`: Returns singleton instance of InferenceEngineImpl
  - **Why**: Singleton pattern ensures only one LLM is loaded, as loading multiple models would waste memory and create confusion about which model is active
  - **Android Context**: Needed to access native library path via `context.applicationInfo.nativeLibraryDir`

**Design Reasoning**:
- Uses Kotlin `object` (not a class) for guaranteed thread-safe singleton
- Mimics Android SDK patterns (e.g., `Toast`, `SharedPreferences`)

---

#### 2. **InferenceEngine.kt** - Public Interface Contract

**Purpose**: Defines the contract for LLM inference operations that applications must implement.

**State Enum Hierarchy**:
```
State (sealed class)
├── Uninitialized    (initial state)
├── Initializing     (loading native library)
├── Initialized      (library loaded, ready for model)
├── LoadingModel     (actively loading GGUF model file)
├── UnloadingModel   (freeing model resources)
├── ModelReady       (model loaded, awaiting prompts)
├── Benchmarking     (running performance test)
├── ProcessingSystemPrompt  (formatting & tokenizing system prompt)
├── ProcessingUserPrompt    (formatting & tokenizing user prompt)
├── Generating       (streaming token generation)
└── Error(exception) (error occurred)
```

**Why Multiple States?**:
- Each state precisely describes the engine's readiness for different operations
- Prevents race conditions: Operations check state before executing
- Enables UI feedback: App can show "Loading model...", "Generating...", or "Error" messages

**Variables**:
```kotlin
// Expose read-only state stream to UI
val state: StateFlow<State>

// Default token prediction length
companion object {
    const val DEFAULT_PREDICT_LENGTH = 1024
}
```

**Extension Functions**:
```kotlin
// Determines if current operation can be interrupted
val State.isUninterruptible: Boolean
    get() = this is State.Initializing || 
            this is State.LoadingModel ||
            this is State.UnloadingModel ||
            this is State.Benchmarking ||
            this is State.ProcessingSystemPrompt ||
            this is State.ProcessingUserPrompt

// Checks if a model is currently available for inference
val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
            // ... other states where model is ready
```

**Why StateFlow?**:
- Coroutine-based reactive stream from kotlinx.coroutines library
- Auto-updates UI when state changes
- Survives configuration changes (rotation)
- Thread-safe multi-threaded access

**Functions**:

1. **loadModel(pathToModel: String)** — `suspend` function
   - Loads GGUF model file from filesystem path
   - Can throw `UnsupportedArchitectureException` if model format incompatible
   - Must be called from `Initialized` state

2. **setSystemPrompt(systemPrompt: String)** — `suspend` function
   - Processes system prompt (e.g., "You are a helpful assistant")
   - Must be called immediately after loadModel, before any user prompts
   - Formats prompt using chat template and tokenizes for model

3. **sendUserPrompt(message: String, predictLength: Int)** — returns `Flow<String>`
   - Returns reactive stream of generated tokens as strings
   - `predictLength` controls max generated tokens (default 1024)
   - Never blocks: Each token emitted as it's generated
   - UI can .collect() to progressively display output

4. **bench(pp: Int, tg: Int, pl: Int, nr: Int)** — `suspend` function, returns `String`
   - Performance testing function
   - Parameters:
     - `pp`: Prompt processing tokens count
     - `tg`: Token generation count
     - `pl`: Parallel token generation length
     - `nr`: Number of runs to average
   - Returns markdown table with benchmark statistics

5. **cleanUp()** — synchronous function
   - Unloads current model, frees GGML resources
   - Used between model loads or to reset error states
   - Blocks until completion (uses `runBlocking`)

6. **destroy()** — synchronous function
   - Final cleanup when engine is no longer needed
   - Shuts down entire native backend
   - Cancels all coroutines
   - After calling, engine cannot be reused

**Design Reasoning**:
- Using `suspend` functions for long operations (JNI calls) allows caller coroutines to yield thread
- `Flow` for token generation enables backpressure: UI can slow down consumption if overloaded
- Sealed class for State ensures compile-time exhaustiveness checking

---

#### 3. **InferenceEngineImpl.kt** - Implementation with JNI Bridge

**Singleton Pattern Implementation**:
```kotlin
companion object {
    @Volatile
    private var instance: InferenceEngine? = null
    
    internal fun getInstance(context: Context) =
        instance ?: synchronized(this) {  // Double-checked locking
            // Create singleton on first call
        }
}
```

**Why Double-Checked Locking?**:
- First `instance ?:` check is unsynchronized (fast path for already-initialized case)
- Only synchronizes if instance is null (one-time initialization)
- `@Volatile` ensures all threads see final initialization
- Prevents 100s of threads all waiting on synchronized lock

**Variables**:

```kotlin
// State tracking
private val _state = MutableStateFlow<State>(State.Uninitialized)
override val state: StateFlow<State> = _state.asStateFlow()

// System prompt readiness flag
private var _readyForSystemPrompt = false

// Cancellation flag for generation loop
@Volatile
private var _cancelGeneration = false

// Single-threaded coroutine dispatcher for thread-safety with C++
@OptIn(ExperimentalCoroutinesApi::class)
private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())
```

**Why Single-Threaded Dispatcher?**
- C++ native code maintains state (loaded model, KV-cache, tokens)
- Multiple threads calling JNI simultaneously = race conditions
- `limitedParallelism(1)` guarantees sequential execution
- Alternative would be manual synchronization (error-prone)

**External JNI Functions** (marked with @FastNative):

```kotlin
@FastNative
private external fun init(nativeLibDir: String)        // Initialize backends

@FastNative
private external fun load(modelPath: String): Int      // Load GGUF model

@FastNative
private external fun prepare(): Int                    // Create context & sampler

@FastNative
private external fun systemInfo(): String              // Query system capabilities

@FastNative
private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

@FastNative
private external fun processSystemPrompt(systemPrompt: String): Int

@FastNative
private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

@FastNative
private external fun generateNextToken(): String?      // Returns UTF-8 token string

@FastNative
private external fun unload()                          // Unload model

@FastNative
private external fun shutdown()                        // Shutdown backends
```

**@FastNative Annotation**:
- Tells JVM this is frequently-called performance-critical JNI code
- Skips some safety checks for faster invocation
- Not recommended for all JNI, but safe here since we control C++ side

**Return Value Conventions** in JNI functions:
- `0` = success
- Non-zero = specific error code
- `null` from `generateNextToken()` = end-of-generation

**Initialization Block** (runs when InferenceEngineImpl is instantiated):
```kotlin
init {
    llamaScope.launch {
        try {
            _state.value = State.Initializing
            System.loadLibrary("ai-chat")  // Loads libaichat.so
            init(nativeLibDir)
            _state.value = State.Initialized
            Log.i(TAG, "System info: \n${systemInfo()}")
        } catch (e: Exception) {
            throw e
        }
    }
}
```

**Why async init?**
- Loading native library can be slow (up to 1+ second)
- Launching in coroutine prevents blocking main thread
- setState to "Initializing" tells UI something is happening

**Key Implementation Details**:

1. **loadModel(pathToModel: String)**:
   ```kotlin
   override suspend fun loadModel(pathToModel: String) =
       withContext(llamaDispatcher) {  // Ensure single-threaded execution
           check(_state.value is State.Initialized) { ... }
           
           // Validate file permissions before JNI call
           File(pathToModel).let {
               require(it.exists()) { "File not found" }
               require(it.isFile) { "Not a valid file" }
               require(it.canRead()) { "Cannot read file" }
           }
           
           try {
               _state.value = State.LoadingModel
               load(modelPath).let {
                   if (it != 0) throw UnsupportedArchitectureException()
               }
               prepare().let {
                   if (it != 0) throw IOException("Failed to prepare resources")
               }
               _state.value = State.ModelReady
           } catch (e: Exception) {
               _state.value = State.Error(e)
               throw e  // Propagate to caller
           }
       }
   ```
   **Why this approach?**:
   - Pre-validates file before expensive JNI call
   - Catches JNI errors and converts to meaningful exceptions
   - State transitions allow app to show progress

2. **setSystemPrompt(prompt: String)**:
   ```kotlin
   override suspend fun setSystemPrompt(prompt: String) =
       withContext(llamaDispatcher) {
           require(prompt.isNotBlank()) { ... }
           check(_readyForSystemPrompt) { 
               "System prompt must be set ** RIGHT AFTER ** model loaded!"
           }
           
           _state.value = State.ProcessingSystemPrompt
           processSystemPrompt(prompt).let { result ->
               if (result != 0) {
                   RuntimeException("Failed to process system prompt: $result").also {
                       _state.value = State.Error(it)
                       throw it
                   }
               }
           }
           _state.value = State.ModelReady
       }
   ```
   **Why strict ordering?**:
   - System prompt must be processed first to establish context
   - If user prompt sent first, model lacks instructions
   - Check prevents hard-to-debug LLM output issues

3. **sendUserPrompt() — The Generation Loop**:
   ```kotlin
   override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = 
       flow {
           // ... validation checks ...
           
           try {
               _state.value = State.ProcessingUserPrompt
               processUserPrompt(message, predictLength).let { result ->
                   if (result != 0) return@flow
               }
               
               _state.value = State.Generating
               while (!_cancelGeneration) {
                   generateNextToken()?.let { utf8token ->
                       if (utf8token.isNotEmpty()) emit(utf8token)
                   } ?: break  // null = generation complete
               }
               _state.value = State.ModelReady
               
           } catch (e: CancellationException) {
               _state.value = State.ModelReady
               throw e  // Propagate cancellation
           } catch (e: Exception) {
               _state.value = State.Error(e)
               throw e
           }
       }.flowOn(llamaDispatcher)
   ```
   **Design Highlights**:
   - Returns `Flow` instead of blocking: caller can cancel anytime
   - Calls `generateNextToken()` in tight loop until null
   - Each token is a UTF-8 string (not individual chars)
   - `.flowOn(llamaDispatcher)` runs loop on single-threaded dispatcher
   - Empty strings from `generateNextToken()` are skipped (they represent UTF-8 fragment buffering)

4. **cleanUp() — Safe Unloading**:
   ```kotlin
   override fun cleanUp() {
       _cancelGeneration = true  // Stop any ongoing generation
       runBlocking(llamaDispatcher) {  // Block until complete
           when (val state = _state.value) {
               is State.ModelReady -> {
                   _state.value = State.UnloadingModel
                   unload()
                   _state.value = State.Initialized
               }
               is State.Error -> {
                   _state.value = State.Initialized
               }
               else -> throw IllegalStateException(...)
           }
       }
   }
   ```
   **Why runBlocking?**:
   - Guarantees unload completes before function returns
   - Caller won't accidentally call loadModel before previous model unloads
   - runBlocking + single-threaded dispatcher = safe native cleanup

5. **destroy() — Final Shutdown**:
   ```kotlin
   override fun destroy() {
       _cancelGeneration = true
       runBlocking(llamaDispatcher) {
           when(_state.value) {
               is State.Initialized -> shutdown()
               else -> { unload(); shutdown() }
           }
       }
       llamaScope.cancel()  // Cancel all remaining coroutines
   }
   ```

---

#### 4. **GgufMetadata.kt** - Metadata Data Classes

**Purpose**: Type-safe representation of GGUF file metadata.

**GGUF Format Version Enum**:
```kotlin
enum class GgufVersion(val code: Int, val label: String) {
    LEGACY_V1(1, "Legacy v1"),         // First draft; little-endian only
    EXTENDED_V2(2, "Extended v2"),     // Split-file support
    VALIDATED_V3(3, "Validated v3")    // Current: endian-aware, validated
    
    companion object {
        fun fromCode(code: Int): GgufVersion =
            entries.firstOrNull { it.code == code } ?: throw IOException(...)
    }
}
```

**Why versioning?**:
- Different GGUF versions have different specifications
- v3 is most robust, validates file integrity
- Reader must handle legacy v1 for backward compatibility

**Nested Data Classes** (each represents a logical section):

1. **BasicInfo** — General model identification
   ```kotlin
   data class BasicInfo(
       val uuid: String? = null,           // Unique model identifier
       val name: String? = null,           // Model basename
       val nameLabel: String? = null,      // Display name (e.g., "Llama 2")
       val sizeLabel: String? = null       // Parameter count label (e.g., "7B", "13B")
   )
   ```

2. **AuthorInfo** — Copyright and attribution
   ```kotlin
   data class AuthorInfo(
       val organization: String? = null,   // Creator organization
       val author: String? = null,         // Model author
       val doi: String? = null,            // Digital Object Identifier (academic)
       val url: String? = null,            // Creator URL
       val repoUrl: String? = null,        // Repository URL
       val license: String? = null,        // License type (MIT, Apache, etc.)
       val licenseLink: String? = null     // License URL
   )
   ```

3. **AdditionalInfo** — Descriptive metadata
   ```kotlin
   data class AdditionalInfo(
       val type: String? = null,           // Model type (e.g., "chat", "instruct")
       val description: String? = null,    // Model description
       val tags: List<String>? = null,     // Tags (e.g., ["fast", "instruction-tuned"])
       val languages: List<String>? = null // Supported languages (e.g., ["en", "es"])
   )
   ```

4. **ArchitectureInfo** — Model architecture parameters
   ```kotlin
   data class ArchitectureInfo(
       val architecture: String? = null,       // Model type (e.g., "llama")
       val fileType: Int? = null,              // Quantization type (code)
       val vocabSize: Int? = null,             // Token vocabulary size
       val finetune: String? = null,           // Fine-tuning description
       val quantizationVersion: Int? = null    // Quantization algorithm version
   )
   ```

5. **BaseModelInfo** — Parent model information (for fine-tunes)
   ```kotlin
   data class BaseModelInfo(
       val name: String? = null,           // Parent model name
       val author: String? = null,         // Parent creator
       val version: String? = null,        // Parent version
       val organization: String? = null,   // Parent organization
       val url: String? = null,            // Parent URL
       val doi: String? = null,            // Parent DOI
       val uuid: String? = null,           // Parent UUID
       val repoUrl: String? = null         // Parent repository
   )
   ```

6. **TokenizerInfo** — Tokenization configuration
   ```kotlin
   data class TokenizerInfo(
       val model: String? = null,          // Tokenizer model name
       val bosTokenId: Int? = null,        // Beginning-of-sequence token ID
       val eosTokenId: Int? = null,        // End-of-sequence token ID
       val unknownTokenId: Int? = null,    // Unknown token fallback ID
       val paddingTokenId: Int? = null,    // Padding token ID
       val addBosToken: Boolean? = null,   // Prepend BOS token?
       val addEosToken: Boolean? = null,   // Append EOS token?
       val chatTemplate: String? = null    // Jinja2 template for chat formatting
   )
   ```

7. **DimensionsInfo** — Model size parameters
   ```kotlin
   data class DimensionsInfo(
       val contextLength: Int? = null,     // Max sequence length
       val embeddingSize: Int? = null,     // Hidden dimension
       val blockCount: Int? = null,        // Number of transformer blocks
       val feedForwardSize: Int? = null    // FFN intermediate size
   )
   ```

8. **AttentionInfo** — Attention mechanism parameters
   ```kotlin
   data class AttentionInfo(
       val headCount: Int? = null,                 // Number of attention heads
       val headCountKv: Int? = null,               // KV heads (for GQA/MQA)
       val keyLength: Int? = null,                 // Key vector dimension
       val valueLength: Int? = null,               // Value vector dimension
       val layerNormEpsilon: Float? = null,        // Layer norm epsilon
       val layerNormRmsEpsilon: Float? = null      // RMSNorm epsilon
   )
   ```

9. **RopeInfo** — Rotary Position Embedding (RoPE) parameters
   ```kotlin
   data class RopeInfo(
       val frequencyBase: Float? = null,               // freq_base for position encoding
       val dimensionCount: Int? = null,                // RoPE dimension
       val scalingType: String? = null,                // Scaling method
       val scalingFactor: Float? = null,               // Context extension factor
       val attnFactor: Float? = null,                  // Attention scaling factor
       val originalContextLength: Int? = null,         // Original pre-scaling context
       val finetuned: Boolean? = null                  // Fine-tuned position embeddings?
   )
   ```

10. **ExpertsInfo** — Mixture-of-Experts parameters
    ```kotlin
    data class ExpertsInfo(
        val count: Int? = null,         // Total expert count
        val usedCount: Int? = null      // Experts used per token
    )
    ```

**Main GgufMetadata Data Class**:
```kotlin
data class GgufMetadata(
    val version: GgufVersion,
    val tensorCount: Long,              // Number of weight tensors
    val kvCount: Long,                  // Number of metadata key-value pairs
    
    // Mandatory section
    val basic: BasicInfo,
    
    // Optional sections
    val author: AuthorInfo? = null,
    val additional: AdditionalInfo? = null,
    val architecture: ArchitectureInfo? = null,
    val baseModels: List<BaseModelInfo>? = null,
    val tokenizer: TokenizerInfo? = null,
    
    // Derivative/computed sections
    val dimensions: DimensionsInfo? = null,
    val attention: AttentionInfo? = null,
    val rope: RopeInfo? = null,
    val experts: ExpertsInfo? = null
)
```

**Design Reasoning**:
- Separates metadata into semantic sections for clarity
- Uses nullable types: Some metadata keys may not be present
- Immutable data class ensures thread safety
- Strongly typed: Type error caught at compile time, not runtime

---

#### 5. **FileType.kt** - Quantization Type Enumeration

**Purpose**: Maps GGUF file type codes to human-readable quantization format names.

**Standard Quantization Types**:
```kotlin
enum class FileType(val code: Int, val label: String) {
    ALL_F32(0, "all F32"),                          // No quantization (32-bit float)
    MOSTLY_F16(1, "F16"),                           // Half-precision (16-bit float)
    
    // Legacy Q-quants
    MOSTLY_Q4_0(2, "Q4_0"),                         // 4-bit, oldest format
    MOSTLY_Q4_1(3, "Q4_1"),                         // 4-bit with delta scaling
    MOSTLY_Q8_0(7, "Q8_0"),                         // 8-bit quantization
    MOSTLY_Q5_0(8, "Q5_0"),                         // 5-bit quantization
    MOSTLY_Q5_1(9, "Q5_1"),                         // 5-bit with scaling
    
    // K-quants (improved quantization formulas)
    MOSTLY_Q2_K(10, "Q2_K - Medium"),               // 2-bit (bits per weight)
    MOSTLY_Q3_K_S(11, "Q3_K - Small"),              // 3-bit, small variant
    MOSTLY_Q3_K_M(12, "Q3_K - Medium"),             // 3-bit, medium variant
    MOSTLY_Q3_K_L(13, "Q3_K - Large"),              // 3-bit, large variant
    MOSTLY_Q4_K_S(14, "Q4_K - Small"),              // 4-bit K-quant small
    MOSTLY_Q4_K_M(15, "Q4_K - Medium"),             // 4-bit K-quant medium
    MOSTLY_Q5_K_S(16, "Q5_K - Small"),              // 5-bit K-quant small
    MOSTLY_Q5_K_M(17, "Q5_K - Medium"),             // 5-bit K-quant medium
    MOSTLY_Q6_K(18, "Q6_K"),                        // 6-bit K-quant
    
    // IQ-quants (improved, newer formats)
    MOSTLY_IQ2_XXS(19, "IQ2_XXS - 2.06 bpw"),       // Extreme low-bit (2.06 bits/weight)
    MOSTLY_IQ2_XS(20, "IQ2_XS - 2.31 bpw"),         // Very low-bit
    MOSTLY_Q2_K_S(21, "Q2_K - Small"),              // 2-bit K-quant small
    MOSTLY_IQ3_XS(22, "IQ3_XS - 3.30 bpw"),         // 3-bit extreme
    MOSTLY_IQ3_XXS(23, "IQ3_XXS - 3.06 bpw"),       // 3-bit variant
    MOSTLY_IQ1_S(24, "IQ1_S - 1.56 bpw"),           // Ultra-low 1-bit
    MOSTLY_IQ4_NL(25, "IQ4_NL - 4.5 bpw"),          // 4-bit non-linear
    MOSTLY_IQ3_S(26, "IQ3_S - 3.44 bpw"),           // 3-bit small
    MOSTLY_IQ3_M(27, "IQ3_M - 3.66 bpw"),           // 3-bit medium
    MOSTLY_IQ2_S(28, "IQ2_S - 2.50 bpw"),           // 2-bit small
    MOSTLY_IQ2_M(29, "IQ2_M - 2.70 bpw"),           // 2-bit medium
    MOSTLY_IQ4_XS(30, "IQ4_XS - 4.25 bpw"),         // 4-bit extreme small
    MOSTLY_IQ1_M(31, "IQ1_M - 1.75 bpw"),           // 1-bit medium
    
    // Bfloat16 & Ternary
    MOSTLY_BF16(32, "BF16"),                        // Brain float 16-bit
    MOSTLY_TQ1_0(36, "TQ1_0 - 1.69 bpw ternary"),   // Ternary quantization
    MOSTLY_TQ2_0(37, "TQ2_0 - 2.06 bpw ternary"),   // Ternary 2-bit
    
    // Special
    GUESSED(1024, "(guessed)"),                     // Auto-detected
    UNKNOWN(-1, "unknown");                         // Unrecognized
    
    companion object {
        private val map = entries.associateBy(FileType::code)
        fun fromCode(code: Int?): FileType = map[code] ?: UNKNOWN
    }
}
```

**Quantization Format Explanation**:
- **bpw** = bits per weight
- **F32/FP32**: 32-bit IEEE 754 floats. Full precision, ~4x larger file
- **F16/BF16**: 16-bit formats. ~2x smaller, minor accuracy loss
- **Q4_0/Q4_1**: 4-bit quantization. ~8x smaller, good speed/quality tradeoff
- **Q8_0**: 8-bit, less compression but better accuracy
- **K-quants**: Improved quantization using knowledge of weight distributions
- **IQ-quants**: Integer quantization, more aggressive compression
- **Ternary**: Weights only have 3 possible values (-1, 0, +1)

**Why This Matters for Android**:
- Quantized models fit in device storage (8GB models → 1GB)
- Lower quantization = faster inference on ARM
- Trade-off: Accuracy loss, but often imperceptible

**Design Reasoning**:
- Enum pattern enforces compile-time type safety
- Companion object provides reverse lookup: code → FileType
- Maps llama.cpp constants to readable labels

---

### C++ JNI Layer

#### 6. **ai_chat.cpp** - Native LLM Implementation

**Architecture**: This is the bridge between Kotlin and llama.cpp, managing the inference loop and state.

**Global State Variables**:

```cpp
static llama_model * g_model;                   // Loaded model weights
static llama_context * g_context;               // Inference context (KV cache, etc)
static llama_batch g_batch;                     // Token batch for decoding
static common_chat_templates_ptr g_chat_templates;  // Chat template formatter
static common_sampler * g_sampler;              // Token sampler
```

**Why Global Static Variables?**:
- These represent the "singleton" LLM state
- Accessible across multiple JNI function calls
- Simplified JNI interface (no pointer passing to Java)
- Single-threaded dispatcher in Kotlin ensures no race conditions

**Configuration Constants**:
```cpp
constexpr int N_THREADS_MIN           = 2;      // Min threads for inference
constexpr int N_THREADS_MAX           = 4;      // Max threads to avoid oversubscription
constexpr int N_THREADS_HEADROOM      = 2;      // Threads reserved for OS/other tasks

constexpr int DEFAULT_CONTEXT_SIZE    = 8192;   // Default context window
constexpr int OVERFLOW_HEADROOM       = 4;      // Safety margin before context full
constexpr int BATCH_SIZE              = 512;    // Tokens per batch for processing
constexpr float DEFAULT_SAMPLER_TEMP  = 0.3f;   // Temperature (0.3 = deterministic)
```

**Why These Values?**
- **N_THREADS**: ARM phones have 4-8 physical cores; reserving 2-4 for inference leaves OS responsive
- **DEFAULT_CONTEXT_SIZE (8192)**: Standard transformer models trained on 4k-8k context
- **BATCH_SIZE (512)**: Sweet spot between memory and compute utilization
- **OVERFLOW_HEADROOM**: KV cache grows during generation; buffer prevents OOM crashes
- **Temperature (0.3f)**: Low value = determinstic/focused output; high would be creative

**Thread Role Constants**:
```cpp
constexpr const char *ROLE_SYSTEM    = "system";      // System instruction
constexpr const char *ROLE_USER      = "user";        // User query
constexpr const char *ROLE_ASSISTANT = "assistant";   // Model generation
```

**Long-term State Variables** (survive across prompts):
```cpp
static std::vector<common_chat_msg> chat_msgs;      // Conversation history
static llama_pos system_prompt_position;            // Position of system prompt end
static llama_pos current_position;                  // Current token position in context
```

**Short-term State Variables** (reset per prompt):
```cpp
static llama_pos stop_generation_position;         // When to stop generating
static std::string cached_token_chars;             // UTF-8 fragment buffer
static std::ostringstream assistant_ss;            // Accumulator for assistant response
```

**Key JNI Functions**:

---

##### **init(JNIEnv, nativeLibDir)**

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(
    JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    
    // Redirect llama.cpp logging to Android logcat
    llama_log_set(aichat_android_log_callback, nullptr);
    
    // Load all available backends (ARM Neon, etc.) from compiled .so files
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);
    
    // Initialize backend system
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}
```

**Purpose**: One-time initialization of llama.cpp infrastructure.

**Steps**:
1. Redirect GGML logs to Android logcat so debugging visible in `logcat`
2. Load backend implementations (.so files) from app's native lib directory
3. Call `llama_backend_init()` to initialize threading, CPU detection, etc.

**Why Separate Backends?**
- Different Android devices have different CPU types (ARM Neon, ARM SVE, x86, x86_64)
- Each compiled as separate .so file
- `ggml_backend_load_all_from_path` auto-detects and loads compatible ones

---

##### **load(modelPath)**

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(
    JNIEnv *env, jobject, jstring jmodel_path) {
    
    llama_model_params model_params = llama_model_default_params();
    
    // Get string from Java
    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);
    
    // Load model file
    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    
    if (!model) {
        return 1;  // Error: model not loaded
    }
    g_model = model;
    return 0;  // Success
}
```

**Purpose**: Load GGUF model file into memory.

**What llama_model_load_from_file Does**:
1. Reads GGUF header and metadata
2. Memory-maps or reads weight tensors
3. Allocates necessary structures
4. Returns pointer, or nullptr on failure

**Why Return Code?**
- JNI isn't ideal for exceptions
- Return 0=success, 1=failure simpler from C++
- Kotlin side converts to proper exceptions

---

##### **prepare()**

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(
    JNIEnv */*env*/, jobject /*unused*/) {
    
    // Create inference context from loaded model
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    
    // Create batch for token processing
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    
    // Load chat template (e.g., Llama 2 chat format)
    g_chat_templates = common_chat_templates_init(g_model, "");
    
    // Create sampler for token generation
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}
```

**Purpose**: Prepare inference engine after model loaded.

**init_context() Helper Function** (lines 76-105):
```cpp
static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    // Determine thread count (max available - headroom)
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
        (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);
    
    // Configure context parameters
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    
    // Set parameters
    ctx_params.n_ctx = n_ctx;              // Context window size
    ctx_params.n_batch = BATCH_SIZE;       // Batch size
    ctx_params.n_ubatch = BATCH_SIZE;      // Micro-batch size
    ctx_params.n_threads = n_threads;      // Inference threads
    ctx_params.n_threads_batch = n_threads; // Batch processing threads
    
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}
```

**What Context Holds**:
- KV cache: Pre-computed key/value vectors for previous tokens
- Temporary computation buffers
- Graph execution state

**new_sampler() Helper** (lines 107-111):
```cpp
static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;              // Temperature controls randomness
    return common_sampler_init(g_model, sparams);
}
```

**Temperature Explained**:
- 0.0 = argmax (always pick highest probability token)
- 0.3 = low (mostly deterministic, rare variation)
- 1.0 = normal (balanced)
- 2.0+ = high (creative, sometimes gibberish)

---

##### **systemInfo()**

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(
    JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}
```

**Purpose**: Return system information for debugging/logging.

**Output Example**:
```
System Info:
Arch: ARM
CPU: Cortex-A78
Threads: 4
...
```

---

##### **benchModel(pp, tg, pl, nr)**

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(
    JNIEnv *env, jobject /*unused*/, jint pp, jint tg, jint pl, jint nr) {
    // pp = Prompt Processing token count
    // tg = Token Generation iteration count
    // pl = Parallel token generation length
    // nr = Number of runs (for averaging)
    
    // Create temporary context for benchmarking
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }
    
    // Stats accumulation
    auto pp_avg = 0.0, pp_std = 0.0;
    auto tg_avg = 0.0, tg_std = 0.0;
    
    for (nri = 0; nri < nr; nri++) {
        // ─── Phase 1: Prompt Processing ───
        LOGi("Benchmark prompt processing (pp = %d)", pp);
        common_batch_clear(g_batch);
        
        // Add pp tokens to batch
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }
        g_batch.logits[g_batch.n_tokens - 1] = true;  // Request logits on last token
        
        const auto t_pp_start = ggml_time_us();
        llama_decode(context, g_batch);
        const auto t_pp_end = ggml_time_us();
        
        // ─── Phase 2: Token Generation ───
        LOGi("Benchmark text generation (tg = %d)", tg);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }
            llama_decode(context, g_batch);
        }
        const auto t_tg_end = ggml_time_us();
        
        // Compute speeds
        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;
        
        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;
        
        pp_avg += speed_pp;
        tg_avg += speed_tg;
        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;
        
        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }
    
    // ─── Compute Statistics ───
    pp_avg /= double(nr);
    tg_avg /= double(nr);
    
    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }
    
    // ─── Format Results ───
    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));
    
    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;
    
    const auto backend = get_backend();
    std::stringstream result;
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}
```

**Benchmark Metrics Explained**:
- **Prompt Processing (pp)**: Speed of processing input tokens (tokens/sec)
  - Measures throughput for processing entire prompts
  - Can be batched efficiently
- **Token Generation (tg)**: Speed of generating new tokens (tokens/sec)
  - Measures latency-sensitive generation
  - Limited by KV cache size and memory bandwidth
- **Result**: Returns markdown table suitable for display/logging

---

##### **reset_long_term_states()**

```cpp
static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();                          // Clear conversation history
    system_prompt_position = 0;
    current_position = 0;
    
    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}
```

**Purpose**: Prepare for new conversation.

---

##### **shift_context()**

```cpp
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    
    llama_memory_seq_rm(llama_get_memory(g_context), 0, 
        system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, 
        system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}
```

**Purpose**: Enable infinite generation by removing older tokens from context.

**How It Works**:
1. Discard oldest half of tokens (after system prompt)
2. Shift remaining tokens to lower positions in KV cache
3. Adjust position counter

**Why Needed?**
- Context window is fixed (8192 tokens)
- After ~4k+ tokens, model would hit limit
- Removing old tokens frees space for new ones
- System prompt kept (to maintain instructions)

**Trade-off**: Longer conversations lose very old context (designed behavior).

---

##### **chat_add_and_format()**

```cpp
static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    
    auto formatted = common_chat_format_single(
        g_chat_templates.get(), chat_msgs, new_msg, 
        role == ROLE_USER, /* use_jinja */ false);
    
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}
```

**Purpose**: Format message according to chat template, add to history.

**Example Output Llama 2 Chat**:
```
Input: role="user", content="What is AI?"
Output: "[INST] What is AI? [/INST]"
```

---

##### **decode_tokens_in_batches()**

```cpp
static int decode_tokens_in_batches(
    llama_context *context, llama_batch &batch,
    const llama_tokens &tokens,
    const llama_pos start_pos,
    const bool compute_last_logit = false) {
    
    LOGd("%s: Decode %d tokens starting at position %d", 
         __func__, (int) tokens.size(), start_pos);
    
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", 
             __func__, cur_batch_size, i);
        
        // Shift context if batch won't fit
        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }
        
        // Add tokens to batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }
        
        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}
```

**Purpose**: Process variable-length token sequences in fixed-size batches.

**Why Batching?**
- Processes multiple tokens in single GPU computation
- Much faster than token-by-token (can parallelize)
- Respects context window and memory limits

---

##### **processSystemPrompt(jsystem_prompt)**

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
    JNIEnv *env, jobject /*unused*/, jstring jsystem_prompt) {
    
    // Reset conversation state
    reset_long_term_states();
    reset_short_term_states();
    
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);
    
    // Format prompt if chat template exists
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
    
    // Tokenize formatted system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                              has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }
    
    // Check if fits in context
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }
    
    // Decode and cache in KV cache
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }
    
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}
```

**Purpose**: Process system prompt and compute KV cache.

**Steps**:
1. Clear previous conversation
2. Format system prompt using chat template
3. Tokenize to token IDs
4. Decode tokens (compute and cache KV values)
5. Save position as system_prompt_position

**Why KV Cache?**
- KV values computed during decoding are expensive
- Caching them means next prompt doesn't recompute
- Like memoization for transformer computations

---

##### **processUserPrompt(juser_prompt, n_predict)**

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
    JNIEnv *env, jobject /*unused*/, 
    jstring juser_prompt, jint n_predict) {
    
    reset_short_term_states();
    
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);
    
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);
    
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, 
                                      has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }
    
    // Truncate if necessary
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }
    
    // Decode user tokens and request logits on last
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }
    
    current_position += user_prompt_size;
    stop_generation_position = current_position + user_prompt_size + n_predict;
    return 0;
}
```

**Purpose**: Process user prompt and prepare for generation.

**Key Points**:
- `true` in `decode_tokens_in_batches` requests logits on last token (needed for sampling)
- `stop_generation_position`: Limits generation to `n_predict` new tokens
- If prompt too long, silently truncates (doesn't fail, just drops early tokens)

---

##### **generateNextToken()**

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
    JNIEnv *env, jobject /*unused*/) {
    
    // Context full? Shift to make room
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }
    
    // Reached generation limit?
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }
    
    // Sample next token from model output
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);
    
    // Create batch with single new token
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }
    
    current_position++;
    
    // Check if end-of-generation token
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }
    
    // Convert token to UTF-8 string
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;
    
    // Ensure valid UTF-8 before returning to Java
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", 
             new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());
        
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");  // Return empty string, buffer incomplete UTF-8
    }
    return result;
}
```

**Purpose**: Generate one token and stream it.

**Token-by-Token Generation Loop**:
1. Sample next token ID from model logits
2. Create batch with single token
3. Decode to compute next logits
4. Convert to UTF-8
5. Return, or buffer incomplete UTF-8 sequences

**UTF-8 Buffering**:
- Tokens don't align with UTF-8 character boundaries
- Example: Chinese character tokenizes as multiple subword tokens
- Each token_to_piece might return partial UTF-8 byte sequence
- `cached_token_chars` accumulates until complete sequence
- Only returns complete UTF-8 strings to Java

**is_valid_utf8() Function** (lines 452-484):
Validates UTF-8 byte sequence using UTF-8 encoding rules:
- 1-byte: 0xxxxxxx (ASCII)
- 2-byte: 110xxxxx 10xxxxxx
- 3-byte: 1110xxxx 10xxxxxx 10xxxxxx
- 4-byte: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx

---

##### **unload()**

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(
    JNIEnv * /*unused*/, jobject /*unused*/) {
    
    reset_long_term_states();
    reset_short_term_states();
    
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}
```

**Purpose**: Free resources when unloading model.

**Order Matters**:
1. Sampler → Context and Batch first (references them)
2. Batch → needs context to free
3. Context → needs model to free
4. Model → last

---

##### **shutdown()**

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(
    JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
```

**Purpose**: Clean up global backend state.

---

#### 7. **logging.h** - Android Logging Utilities

**Purpose**: Provide macros for llama.cpp logging output to Android logcat.

**Key Components**:

```cpp
// Tag for all logs
#ifndef LOG_TAG
#define LOG_TAG "ai-chat"
#endif

// Minimum log level (VERBOSE in debug, INFO in release)
#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

// Runtime check: should log at this priority?
static inline int ai_should_log(int prio) {
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
}
```

**Logging Macros**:
```cpp
#if LOG_MIN_LEVEL <= ANDROID_LOG_VERBOSE
#define LOGv(...)  // Verbose (lowest priority)
#else
#define LOGv(...) ((void)0)  // Compiled out
#endif

#define LOGd(...)  // Debug
#define LOGi(...)  // Info
#define LOGw(...)  // Warning
#define LOGe(...)  // Error (highest priority shown)
```

**Why Conditional Compilation?**
- Debug builds include verbose logs
- Release builds only show info+ (less spam)
- Macros compiled to no-ops when disabled (zero runtime cost)

**GGML Log Handler**:
```cpp
static inline void aichat_android_log_callback(enum ggml_log_level level,
                                               const char* text,
                                               void* /*user*/) {
    const int prio = android_log_prio_from_ggml(level);
    if (!ai_should_log(prio)) return;
    __android_log_write(prio, LOG_TAG, text);
}
```

**Purpose**: Redirect GGML library logs to Android logcat.

---

#### 8. **CMakeLists.txt** - Build Configuration

```cmake
cmake_minimum_required(VERSION 3.31.6)
project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

# C/C++ Standard Requirements
set(CMAKE_C_STANDARD 11)      // C11 for modern features
set(CMAKE_CXX_STANDARD 17)    // C++17 for std::optional, structured bindings
```

**Architecture-Specific Build Options**:
```cmake
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)  // Arm CPU acceleration library
        set(GGML_OPENMP ON)         // Multi-threading
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()
```

**Why Conditional Compilation?**
- ARM64: Has CPU-specific optimizations (Neon, SVE); OpenMP threading
- x86_64: CPU intrinsics different; OpenMP less beneficial
- Compile for each ABI separately in app build

**Library Linking**:
```cmake
target_link_libraries(${CMAKE_PROJECT_NAME}
    llama               // Core llama.cpp
    llama-common        // Common utilities (tokenization, chat templates)
    android             // Android NDK (for JNI, etc.)
    log                 // Android logging library
)
```

---

### GGUF Metadata Reader

#### 9. **GgufMetadataReader.kt** - Public Interface

**Purpose**: Interface for reading and validating GGUF files.

```kotlin
interface GgufMetadataReader {
    // Validate file format
    suspend fun ensureSourceFileFormat(file: File): Boolean
    suspend fun ensureSourceFileFormat(context: Context, uri: Uri): Boolean
    
    // Read and parse metadata
    suspend fun readStructuredMetadata(input: InputStream): GgufMetadata
    
    companion object {
        private val DEFAULT_SKIP_KEYS = setOf(
            "tokenizer.chat_template",
            "tokenizer.ggml.scores",
            "tokenizer.ggml.tokens",        // Large arrays: skip by default
            "tokenizer.ggml.token_type"
        )
        
        fun create(): GgufMetadataReader
        fun create(skipKeys: Set<String>, arraySummariseThreshold: Int): GgufMetadataReader
    }
}

class InvalidFileFormatException : IOException()
```

**Why skip certain keys?**
- Some keys have enormous arrays (100k+ elements)
- Tokenizer word list: 50,000+ strings
- Scores: unnecessary for most use cases
- Skipping saves memory and parsing time

---

#### 10. **GgufMetadataReaderImpl.kt** - Implementation

**Metadata Type Enum** (lines 26-35):
Maps GGUF type codes to Kotlin types:
```kotlin
enum class MetadataType(val code: Int) {
    UINT8(0), INT8(1), UINT16(2), INT16(3),
    UINT32(4), INT32(5), FLOAT32(6), BOOL(7),
    STRING(8), ARRAY(9), UINT64(10), INT64(11), FLOAT64(12)
}
```

**MetadataValue Sealed Class Hierarchy** (lines 38-52):
Type-safe wrapper for each metadata type:
```kotlin
sealed class MetadataValue {
    data class UInt8(val value: UByte) : MetadataValue()
    data class Int8(val value: Byte) : MetadataValue()
    data class UInt16(val value: UShort) : MetadataValue()
    // ... (all 13 types)
    data class StringVal(val value: String) : MetadataValue()
    data class ArrayVal(val elementType: MetadataType, 
                        val elements: List<MetadataValue>) : MetadataValue()
}
```

**Why Sealed Class?**
- `when` expression must be exhaustive
- Compiler prevents handling missing types
- Guarantees type safety

**GGUF File Format** (from llama.cpp spec):
```
┌─────────────────────────────────────┐
│ Magic: "GGUF" (4 bytes)             │
├─────────────────────────────────────┤
│ Version: uint32_t (little-endian)   │
├─────────────────────────────────────┤
│ Tensor Count: uint64_t              │
├─────────────────────────────────────┤
│ KV Pair Count: uint64_t             │
├─────────────────────────────────────┤
│ Key-Value Pairs (variable length)   │
│  ├─ String key (length + data)      │
│  ├─ Type code: uint32_t             │
│  └─ Value (type-dependent length)   │
├─────────────────────────────────────┤
│ Alignment Padding (to 32-byte)      │
├─────────────────────────────────────┤
│ Tensor Data (not parsed)            │
└─────────────────────────────────────┘
```

**readStructuredMetadata() Function** (lines 119-131):
```kotlin
override suspend fun readStructuredMetadata(input: InputStream): GgufMetadata {
    // 1. Read header (magic, version, counts)
    val version = ensureMagicAndVersion(input)
    val tensorCount = readLittleLong(input)
    val kvCount = readLittleLong(input)
    
    // 2. Parse key-value pairs
    val meta = readMetaMap(input, kvCount)
    
    // 3. Convert to structured types
    return buildStructured(meta, version, tensorCount, kvCount)
}
```

**readMetaMap() Function** (lines 164-175):
```kotlin
private fun readMetaMap(input: InputStream, kvCnt: Long): 
    Map<String, MetadataValue> =
    mutableMapOf<String, MetadataValue>().apply {
        repeat(kvCnt.toInt()) {
            val key = readString(input)
            val valueT = MetadataType.fromCode(littleEndianBytesToInt(input.readNBytesExact(4)))
            
            if (key in skipKeys) {
                skipValue(input, valueT)  // Fast-forward without reading
            } else {
                this[key] = parseValue(input, valueT)
            }
        }
    }
```

**parseValue() Function** (lines 338-476):
Recursively parses each metadata value type:

- **Integers**: Read bytes in little-endian order, combine into single value
- **Floats**: Read 4/8 bytes, interpret as IEEE 754
- **Strings**: Read 8-byte length, then UTF-8 bytes
- **Arrays**: Read element type and count, recursively parse each element
- **Special Handling**: Arrays exceeding threshold are summarized instead of materialized

**Example Int32 Parsing** (lines 378-388):
```kotlin
val bytes = ByteArray(4)
if (input.read(bytes) != 4) throw IOException("Unexpected EOF")
val i32 = (bytes[3].toInt() and 0xFF shl 24) or
    (bytes[2].toInt() and 0xFF shl 16) or
    (bytes[1].toInt() and 0xFF shl 8) or
    (bytes[0].toInt() and 0xFF)
MetadataValue.Int32(i32)
```

**Little-Endian Byte Order**:
- ARM/x86 processors use little‑endian
- Least significant byte stored first in memory
- Example: 0x12345678 stored as: 78 56 34 12

**buildStructured() Function** (lines 189-331):
Converts flat `Map<String, MetadataValue>` to typed `GgufMetadata` tree:

```kotlin
private fun buildStructured(...): GgufMetadata {
    // Helper extractors (safe downcast with null coalescing)
    fun String.str() = (m[this] as? MetadataValue.StringVal)?.value
    fun String.i32() = (m[this] as? MetadataValue.Int32)?.value
    fun String.f32() = (m[this] as? MetadataValue.Float32)?.value
    
    // Extract architecture (default "llama")
    val arch = "general.architecture".str() ?: ARCH_LLAMA
    
    // Build sections, conditionally include non-empty ones
    val basic = GgufMetadata.BasicInfo(
        uuid = "general.uuid".str(),
        name = "general.basename".str(),
        // ...
    )
    
    val author = GgufMetadata.AuthorInfo(
        organization = "general.organization".str(),
        // ...
    ).takeUnless { allFieldsNull() }  // Omit if all fields null
    
    // ... build other sections ...
    
    return GgufMetadata(
        version = version,
        tensorCount = tensorCnt,
        kvCount = kvCnt,
        basic = basic,
        author = author,
        // ...
    )
}
```

**Why .takeUnless()?**
Omits sections where all fields are null:
- Reduces object nesting
- Cleaner API for callers
- Saves memory

**Helper Functions for Streaming**:

1. **skipValue()** (lines 483-498): Fast-forward past values without reading
   ```kotlin
   private fun skipValue(input: InputStream, type: MetadataType) {
       when (type) {
           MetadataType.UINT8, MetadataType.INT8, MetadataType.BOOL -> input.skipFully(1)
           MetadataType.UINT16, MetadataType.INT16 -> input.skipFully(2)
           MetadataType.ARRAY -> {
               val elemType = ...
               val len = readLittleLong(input)
               repeat(len.toInt()) { skipValue(input, elemType) }  // Recursive
           }
           // ...
       }
   }
   ```

2. **readFully()** (lines 572-579): Robust read ensuring exact byte count
   ```kotlin
   private fun InputStream.readFully(buf: ByteArray, len: Int = buf.size) {
       var off = 0
       while (off < len) {
           val n = read(buf, off, len - off)
           if (n == -1) throw IOException("EOF after $off of $len bytes")
           off += n
       }
   }
   ```
   **Why?** Some Android streams don't read full requested amount in one call.

3. **skipFully()** (lines 545-561): Robust skip with fallback
   ```kotlin
   private fun InputStream.skipFully(n: Long) {
       var remaining = n
       val scratch = ByteArray(8192)
       while (remaining > 0) {
           val skipped = skip(remaining)
           when {
               skipped > 0 -> remaining -= skipped
               skipped == 0L -> {
                   // skip() not supported; read and discard instead
                   val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                   if (read == -1) throw IOException("EOF while skipping $n bytes")
                   remaining -= read
               }
               else -> throw IOException("Skip returned negative value")
           }
       }
   }
   ```
   **Why?** Android's stream implementations are inconsistent; fallback to read/discard.

---

## Design Patterns and Reasoning

### 1. **Singleton Pattern (InferenceEngineImpl)**

**Why Used**:
- Only one LLM should be loaded at a time
- Models are typically 1-7GB
- Multiple models would exhaust device memory
- Maintains consistent inference state

**Implementation**:
- Kotlin `object` keyword guarantees thread-safe singleton
- Double-checked locking in `getInstance()`
- `@Volatile` instance flag ensures visibility across threads

### 2. **State Machine Pattern (InferenceEngine.State)**

**Why Used**:
- Prevents operations in invalid states
- Example: Can't send user prompt before loading model
- UI can react to state changes (show "Loading...", etc.)
- Compiler-enforced correctness via sealed classes

**State Transitions**:
```
Uninitialized → Initializing → Initialized
                                     ↓
                            LoadingModel → ModelReady
                                             ↓
                     ProcessingSystemPrompt (stay ModelReady)
                            ProcessingUserPrompt → Generating
                                                        ↓
                                                   ModelReady
```

### 3. **Reactive Streams (Flow<String>)**

**Why Used**:
- Token generation can't all fit in memory at once
- Each token emitted as soon as available
- UI updates progressively (streaming effect)
- Backpressure: UI can slow consumption

**Alternative (not used)**:
- Returning `List<String>` would require generating entire response in memory
- Blocking until complete (unresponsive UI)

### 4. **Coroutines for Long Operations**

**Why Used**:
- JNI calls to C++ can take 100ms+ (loading models)
- `suspend fun` allows thread to process other coroutines
- Single-threaded dispatcher ensures JNI thread safety

**Single-Threaded Dispatcher**:
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
```

**Why Not Manual Locks?**:
- Error-prone (deadlocks, race conditions)
- Coroutines handle it elegantly

### 5. **JNI for Performance-Critical Code**

**Why Used**:
- LLM inference requires 1000x speedup from Java
- C++ with SIMD gives 10-100x improvement
- llama.cpp is heavily optimized C++ library
- Android NDK provides standard JNI interface

**Why Not Full Kotlin Native?**:
- Kotlin Native compilation slow for production
- llama.cpp already proven, optimized
- Smaller binary size via native library linked once

### 6. **Batch Processing in Generation**

**Why Used**:
- Processing 500 tokens at once 10x faster than one-by-one
- Batching amortizes overhead
- GPU/CPU parallelism utilized

### 7. **KV Cache for Context Reuse**

**Why Used**:
- Recomputing key/value vectors exp expensive
- Caching saves 90% of compute for next prompt
- System prompt computed once, reused for all user prompts

### 8. **Chat Template Formatting**

**Why Used**:
- Different models expect different prompt formats
- Llama 2: `[INST] user message [/INST]`
- Mistral: Different format
- Templating abstracts this

### 9. **Context Shifting for Infinite Generation**

**Why Used**:
- Context window fixed (8192 tokens)
- Without shifting: Hit limit after ~4k tokens generated
- Shifting discards old tokens, keeps system prompt
- Enables longer conversations

**Trade-off**: Very old context lost (acceptable for chat)

---

## State Management

### Kotlin State Flow Architecture

**MutableStateFlow** for reactive updates:
```kotlin
private val _state = MutableStateFlow<State>(State.Uninitialized)
override val state: StateFlow<State> = _state.asStateFlow()
```

**Why?**
- `_state`: Internal mutable, only impl can change
- `state`: Public immutable, app can observe only
- `asStateFlow()`: Prevents external modification

**State Observation Pattern** (in app code):
```kotlin
lifecycleScope.launch {
    inferenceEngine.state.collect { state ->
        when (state) {
            is InferenceEngine.State.Initializing -> showProgress("Loading...")
            is InferenceEngine.State.ModelReady -> enableSendButton()
            is InferenceEngine.State.Generating -> disableSendButton()
            is InferenceEngine.State.Error -> showError(state.exception)
            // ...
        }
    }
}
```

### Variable State Organization

**Long-Term State** (persists across operations):
- `g_model`: Model weights
- `g_context`: Inference state, KV cache
- `chat_msgs`: Conversation history
- `system_prompt_position`: For context shifting

**Short-Term State** (reset after each user prompt):
- `cached_token_chars`: UTF-8 fragment buffer
- `assistant_ss`: Accumulated response text
- `stop_generation_position`: Generation limit

**Single-Threaded Access**:
- `llamaDispatcher`: Ensures no concurrent access to C++ state
- `runBlocking()` in `cleanUp()`/`destroy()`: Guarantees completion before return

---

## Integration with llama.cpp

### What is llama.cpp?

**Open-source project** implementing LLM inference:
- Originally for LLaMA (Meta's model), now supports many
- C++ with CPU/GPU backends (currently CPU on Android)
- ~10-50KB lines of code
- Heavily optimized with SIMD (Neon for ARM)

### Key llama.cpp Concepts Used

1. **llama_model**: Loaded model weights
   - Memory-mapped or loaded into RAM
   - Read-only after load

2. **llama_context**: Inference state machine
   - Maintains KV cache
   - Execution graph
   - Token positions

3. **llama_batch**: Token batches for processing
   - Input: Token IDs to process
   - Output: Logits for next token selection

4. **common_sampler**: Token selection
   - Given logits, samples next token
   - Respects temperature and sampling parameters

5. **common_chat_templates**: Format strings
   - Jinja2-based templates
   - Formats messages for input to model

6. **common_tokenize()**: Text → Token IDs
   ```cpp
   auto tokens = common_tokenize(context, text, true, true);
   // tokens = [1234, 5678, 910, ...]
   ```

7. **common_token_to_piece()**: Token ID → Text
   ```cpp
   auto text = common_token_to_piece(context, token_id);
   // token_id = 1234 → text = "Hello"
   ```

### llama.cpp Documentation References

For deeper understanding, refer to:
- **Main Repo**: https://github.com/ggerganov/llama.cpp
- **Model Format**: `GGUF_FILE_FORMAT.md` in repo
- **API Reference**: `include/llama.h` (main header)
- **Common Utils**: `common/common.h`
- **Sampling**: `common/sampling.h`

### Bridging with JNI

The library uses **standard JNI conventions**:

1. **Function Naming**: 
   ```
   Java_<package>_<class>_<method>
   Java_com_arm_aichat_internal_InferenceEngineImpl_load
   ```

2. **Type Mapping**:
   ```
   Kotlin           C++
   String  ←→  jstring
   Int     ←→  jint
   Long    ←→  jlong
   Unit    ←→  void
   ```

3. **Exception Handling**:
   - C++: Return error codes
   - Kotlin: Convert to exceptions
   - App: Observes via State.Error

---

## Performance Considerations

### Memory Usage

**Model Storage**:
- Q4_0 (4-bit): ~2 GB for 13B model
- F16 (16-bit): ~26 GB for 13B model (too large for Android!)
- Q2_K: ~3 GB for 13B model (more accurate than Q4_0)

**Runtime Memory**:
- Context buffer: ~8192 tokens × hidden_size × 2 (KV) × bytes_per_element
- Batch buffer: BATCH_SIZE × hidden_size × 4 bytes
- Temporary: ~200MB for computation graphs

### Speed Optimization

**Token Generation Speed** (tokens/second):
- Llama 2 7B (Q4_0): ~2-5 t/s on Snapdragon 8 Gen 2
- Llama 2 13B: ~1-2 t/s
- Q2_K (smaller): ~5-10 t/s

**Prompt Processing Speed** (tokens/second):
- Can process 100+ t/s (faster than generation due to batching)
- Prompt "Your name is Assistant." (~5 tokens) takes ~1-10ms

### ARM Optimization

**CPU Backend Features**:
- **ARM Neon**: SIMD instruction set (128-bit vectors)
- **OpenMP**: Multi-threading via pragmas
- **Kleidi AI**: Arm-developed optimized kernels
- **CPU affinity**: Pin threads to performance cores

**No GPU on Android**:
- GPUs (Mali, Adreno) require vendor-specific drivers
- Current implementation CPU-only
- Future: Maybe Vulkan compute shader support

---

## Architecture Summary

```
User App Request
        ↓
    AiChat
    (public API)
        ↓
InferenceEngine Interface
    (contract)
        ↓
InferenceEngineImpl
    (orchestration, state)
        ↓ (coroutines + single-threaded dispatcher)
    JNI Boundary
        ↓
ai_chat.cpp (C++ implementation)
    ├─ init()              → Backend init
    ├─ load()              → Load model
    ├─ prepare()           → Create context, sampler
    ├─ processSystemPrompt() → Tokenize & cache
    ├─ processUserPrompt()   → Tokenize & cache
    ├─ generateNextToken()   → Stream generation
    ├─ bench()              → Performance testing
    └─ unload() / shutdown() → Resource cleanup
        ↓
llama.cpp C++ (inference engine)
    ├─ Token processing
    ├─ Matrix multiplication
    ├─ Attention blocks
    └─ KV cache management
        ↓
GGML Backend
    ├─ ARM CPU (Neon)
    ├─ OpenMP threading
    └─ Memory management
```

---

## Files Reference Map

| File | Purpose | Language | Key Classes/Functions |
|------|---------|----------|----------------------|
| AiChat.kt | Public entry point | Kotlin | `object AiChat`, `getInferenceEngine()` |
| InferenceEngine.kt | API contract | Kotlin | `interface InferenceEngine`, `sealed class State` |
| InferenceEngineImpl.kt | Implementation | Kotlin | `class InferenceEngineImpl`, singleton pattern |
| GgufMetadata.kt | Metadata structure | Kotlin | Data classes for metadata |
| GgufMetadataReader.kt | Reader interface | Kotlin | `interface GgufMetadataReader` |
| GgufMetadataReaderImpl.kt | GGUF parser | Kotlin | `class GgufMetadataReader Impl`, GGUF format parsing |
| FileType.kt | Quantization types | Kotlin | `enum class FileType` |
| ai_chat.cpp | JNI implementation | C++ | JNI functions, inference loop |
| CMakeLists.txt | Build config | CMake | Architecture-specific settings |
| logging.h | Android logging | C++ Header | Macros `LOGi()`, `LOGd()`, etc. |

---

## Key Insights

### Design Decisions Rationale

| Decision | Why | Trade-off |
|----------|-----|-----------|
| Single-threaded dispatcher | JNI thread safety | Potential sequencing issues if not careful |
| Singleton pattern | Only one model at once | Limited to single model |
| StateFlow for state | Reactive UI updates | Learning curve for coroutines |
| Batch processing | Performance | Latency (must wait for batch) |
| Context shifting | Infinite generation | Oldest tokens lost |
| Kotlin/C++ split | Safety + Performance | Binary size, complexity |
| GGUF metadata reader | Model introspection | Extra parsing overhead |
| Sealed State classes | Type safety | Exhaustiveness checks required |

### Common Issues & Solutions

1. **"System prompt must be set RIGHT AFTER model loaded"**
   - Ensures system context is first in KV cache
   - Without: Model unaware of instructions

2. **UTF-8 Buffering in generateNextToken()**
   - Tokens don't align with UTF-8 boundaries
   - Empty strings returned while buffering incomplete sequences
   - App must accumulate until non-empty

3. **Context Shifting and Position Tracking**
   - Discarding old tokens doesn't update conversation ID map
   - Position counter adjusted instead
   - Edge case: Very long conversations lose oldest context

4. **Temperature = 0.3f Default**
   - Deterministic (reproducible responses)
   - Low variability better for assistants
   - User can request higher temperature for creativity

---

## Future Improvements

1. **GPU Support**: Vulkan compute shaders for Mali/Adreno
2. **Speculative Decoding**: 2-3x faster generation
3. **Streaming Model Loading**: Start inference while loading
4. **Quantization Options**: User-selectable Q2_K, Q4_K, etc.
5. **Multi-model Support**: Load multiple models sequentially
6. **Advanced Sampling**: Nucleus sampling, beam search

---

## Conclusion

This library is a **production-grade LLM inference engine** optimized for Android. It combines:

- **Kotlin Coroutines** for clean async/reactive patterns
- **JNI Bridge** to high-performance C++ llama.cpp
- **GGUF Format Support** for model metadata inspection
- **Single-threaded Dispatcher** for safe concurrent access
- **State Management** for predictable operation
- **Batch Processing** for efficient inference
- **ARM Optimization** for mobile CPU acceleration

The architecture exemplifies best practices for mobile native development, balancing safety, performance, and maintainability.

---

**Document Version**: 1.0  
**Generated**: December 2025  
**Library Version**: 1.0.0

