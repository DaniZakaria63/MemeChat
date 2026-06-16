# Advanced RAG + Vector Search Implementation Plan

> Replaces the naive keyword `MemoryService` with dense vector retrieval. **FAISS** is a git submodule at `faiss/`, prebuilt into a static library. **`:vector`** is the dedicated JNI bridge module wrapping FAISS operations. **OpenNLP** handles text preprocessing (sentence detection, tokenization). **`:memelm`** provides embedding via `llama_encode`.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            ANDROID APPLICATION                                   │
│                                                                                  │
│  ┌─────────────┐  ┌──────────────────────────┐  ┌────────────────────────────┐  │
│  │ :app         │  │ :local                   │  │ :memelm                   │  │
│  │ ChatViewModel│──► MemoryService (orchestr.)│  │ ┌──────────────────────┐ │  │
│  │ (injects all)│  │ │                        │  │ │ EmbeddingEngine     │ │  │
│  │              │  │ ├─ PreprocessingService  │  │ │ (JNI → llama_encode) │ │  │
│  │ sendMessage()│  │ │   (OpenNLP)            │  │ └──────────┬───────────┘ │  │
│  │ → retrieve() │  │ ├─ ChunkDao (Room)       │  │            │              │  │
│  │ → embed()    │  │ ├─ FaissMappingDao       │  │   ┌────────▼───────────┐  │  │
│  │ → search()   │  │ └─ Convers./Msg DAOs     │  │   │ LLMInference       │  │  │
│  │ → generate() │  │                          │  │   │ (existing gen.)    │  │  │
│  └──────┬───────┘  └──────────┬───────────────┘  │   └────────────────────┘  │  │
│         │                     │                   └────────────────────────────┘  │
│         │             ┌───────▼────────┐                                         │
│         │             │ :vector        │  ←── JNI bridge module                  │
│         │             │ VectorStore.kt │                                         │
│         │             │  (Kotlin API)  │                                         │
│         │             └───────┬────────┘                                         │
│         │                     │ JNI                                               │
│         │             ┌───────▼──────────────────────────────┐                   │
│         │             │ libvector.so (C++ FAISS wrapper)     │                   │
│         │             │                                      │                   │
│         │             │  VectorStore.h/.cpp                   │                   │
│         │             │    ┌──────────────────────┐          │                   │
│         │             │    │ libfaiss.a (prebuilt) │◄── static link              │
│         │             │    │ IndexFlatIP          │          │                   │
│         │             │    │ IndexIDMap           │          │                   │
│         │             │    └──────────────────────┘          │                   │
│         │             └──────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Module Responsibilities

| Module | Layer | Responsibility |
|--------|-------|---------------|
| `:vector` | JNI bridge (Kotlin + C++) | FAISS vector store operations — `add`, `search`, `remove`, `save`, `load`. Links against **prebuilt `libfaiss.a`**. A single `System.loadLibrary("vector")` loads everything. |
| `:memelm` | JNI bridge (Kotlin + C++) | `EmbeddingEngine` (new): `llama_encode` with mean pooling → 2048-dim float vector. `LLMInference` (existing): text/image generation. |
| `:local` | Kotlin services | `PreprocessingService` (OpenNLP): sentence detection, tokenization, noise reduction. `MemoryService`: RAG orchestrator. Room: `ChunkEntity`, `FaissMappingEntity`, existing entities. |
| `:app` | UI + ViewModel | `ChatViewModel`: wires everything, injects RAG context into ChatML. |

### Dependency Graph

```
:app ──► :local ──► :memelm  (EmbeddingEngine interface)
                  ──► :vector  (VectorStore interface)
      ──► :memelm (InferenceEngine interface)
      ──► :modelpull
      ──► :constant

:vector → no module deps (pure JNI, links libfaiss.a)
:memelm → :constant only
:local  → :memelm (interface types), :vector (interface types)
```

---

## FAISS Build Strategy: Prebuilt Static Library vs Submodule

### Current Setup

```
faiss/                    (git submodule — Facebook FAISS v1.14.3 source)
vector/libs/arm64-v8a/    (prebuilt libfaiss.a — built once from faiss/)
```

The plan: build FAISS **once** as a static library using the NDK toolchain, commit the `.a` binary, then link it into `libvector.so`. No `add_subdirectory(faiss)` in CMake — just `target_link_libraries`.

### Comparison

| Aspect | Prebuilt Static Library (`libfaiss.a`) | CMake Submodule (`add_subdirectory`) |
|--------|--------------------------------------|--------------------------------------|
| **Build time** | FAISS built once, commit .a — subsequent project builds are fast | Every clean build recompiles all FAISS sources (~200+ .cpp files) |
| **Complexity** | Simple CMake: `target_link_libraries(vector .../libfaiss.a)` | Complex: must cherry-pick source files, handle missing symbols, manage SIMD flags per ABI |
| **ABI flexibility** | Need to rebuild `.a` per ABI (arm64-v8a only here) | Same — still needs per-ABI compilation, just automated |
| **FAISS updates** | Manual: rebuild .a, replace file, commit | Automatic: pull submodule, rebuild picks up changes |
| **Compiler flags** | Decoupled — FAISS can use `-O3 -march=armv8-a+fp16` independently | Flags must be compatible across the whole CMake tree |
| **Debugging** | Harder — no source stepping into FAISS | Easier — FAISS sources are in the IDE project |
| **APK size** | FAISS code is inside `libvector.so` (6–8 MB) | Same — FAISS is still compiled into the same .so |
| **NDK toolchain compat** | Must match NDK version exactly (build .a with same NDK as project) | Handled automatically |

**Recommendation:** Prebuilt static library is the right choice here because:
1. FAISS is stable (no frequent updates needed for a vector index)
2. The project only targets `arm64-v8a` — one ABI, one `.a` file
3. Build time savings for the developer iterating on the app
4. CMake stays simple — no complex FAISS CMake integration

### How to Build libfaiss.a for Android

```bash
# One-time build from project root
cd faiss
mkdir -p build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DFAISS_OPT_LEVEL=generic \
  -DBUILD_TESTING=OFF \
  -DBUILD_SHARED_LIBS=OFF \
  -DFAISS_ENABLE_GPU=OFF \
  -DFAISS_ENABLE_PYTHON=OFF \
  -DCMAKE_BUILD_TYPE=Release

cmake --build . -j$(nproc)

# Copy the static lib to :vector module
cp faiss/libfaiss.a ../vector/libs/arm64-v8a/
```

---

## Phase 1: Preprocessing with OpenNLP (`:local`)

### 1.1 Add OpenNLP Dependency

**Why OpenNLP?** Equivalent to Python's spaCy + NLTK on Android. Pure Java, runs on the JVM with no native code.

| Python | Android (OpenNLP) |
|--------|------------------|
| `spaCy nlp(text)` | `SentenceDetectorME.sentDetect()` |
| `nltk.word_tokenize` | `TokenizerME.tokenize()` |
| spaCy built-in normalizers | Uses same approach — OpenNLP native `EmojiCharSequenceNormalizer`, `UrlCharSequenceNormalizer`, `NumberCharSequenceNormalizer` (manual §3, Table 3.1) |

> **Note:** No synonym/WordNet expansion needed here because the embedding model (`llama_encode`) inherently maps semantically similar words to nearby vectors. Synonym expansion was a keyword-RAG technique that's redundant with dense retrieval.

**Gradle:**

```kotlin
// local/build.gradle.kts
dependencies {
    implementation("org.apache.opennlp:opennlp-runtime:3.0.0-M3")
    // ... existing: Room, Hilt, Timber
}
```

**Version catalog** (`gradle/libs.versions.toml`):

```toml
[versions]
opennlp = "3.0.0-M3"

[libraries]
opennlp-runtime = { group = "org.apache.opennlp", name = "opennlp-runtime", version.ref = "opennlp" }
```

### 1.2 PreprocessingService

```kotlin
// local/src/main/java/fun/walawe/local/service/PreprocessingService.kt
package fun.walawe.local.service

import android.content.Context
import opennlp.tools.sentdetect.SentenceDetectorME
import opennlp.tools.sentdetect.SentenceModel
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.tokenize.TokenizerModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreprocessingService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Lazy-load OpenNLP models from assets (extract on first use).
    // Both classes are thread-safe as of OpenNLP 3.0.0 — single instance can be shared.
    private val sentenceDetector: SentenceDetectorME by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SentenceDetectorME(loadModel("opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin") { SentenceModel(it) })
    }
    private val tokenizer: TokenizerME by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TokenizerME(loadModel("opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin") { TokenizerModel(it) })
    }

    data class PreprocessedResult(
        val original: String,
        val normalized: String,
        val sentences: List<String>,
        val tokens: List<String>,
    )

    /**
     * Full preprocessing pipeline:
     * 1. Noise reduction (URLs, emails, emoji, whitespace)
     * 2. Sentence detection (OpenNLP)
     * 3. Tokenization (OpenNLP)
     * Note: No synonym expansion needed — the embedding model (llama_encode)
     * already maps semantically similar words to nearby vectors.
     */
    suspend fun preprocess(text: String): PreprocessedResult {
        val normalized = normalizeText(text)
        val sentences = detectSentences(normalized)
        val tokens = tokenizeText(normalized)
        return PreprocessedResult(
            original = text,
            normalized = normalized,
            sentences = sentences.toList(),
            tokens = tokens.toList(),
        )
    }

    private fun normalizeText(text: String): String {
        return text
            .trim()
            .replace(Regex("https?://\\S+"), "")          // remove URLs
            .replace(Regex("[\\w.+-]+@[\\w.-]+"), "")      // remove emails
            .replace(Regex("&#\\d+;|#\\w+"), "")           // HTML entities, hashtags
            .replace(Regex("[\\u{1F600}-\\u{1F64F}\\u{1F300}-\\u{1F5FF}" +
                "\\u{1F680}-\\u{1F6FF}\\u{1F1E0}-\\u{1F1FF}" +
                "\\u{2600}-\\u{26FF}\\u{2700}-\\u{27BF}]"), " ") // emoji → space
            .replace(Regex("\\s+"), " ")                    // collapse whitespace
            .let { if (it.length > 2000) it.take(2000) else it }
    }

    /**
     * OpenNLP sentence detection.
     * Equivalent to: nltk.sent_tokenize() / spaCy doc.sents
     */
    private fun detectSentences(text: String): Array<String> {
        return try {
            sentenceDetector.sentDetect(text)
        } catch (e: Exception) {
            // Fallback: simple regex split
            text.split(Regex("(?<=[.!?])\\s+(?=[A-Z\"'(\\[{]))"))
                .filter { it.isNotBlank() }
                .toTypedArray()
        }
    }

    /**
     * OpenNLP tokenization.
     * Equivalent to: nltk.word_tokenize() / spaCy token.text
     */
    private fun tokenizeText(text: String): Array<String> {
        return try {
            tokenizer.tokenize(text.lowercase())
        } catch (e: Exception) {
            text.lowercase()
                .replace(Regex("[^a-zA-Z0-9\\s'-]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 1 || it == "a" || it == "I" }
                .toTypedArray()
        }
    }

    private fun <T> loadModel(filename: String, factory: (InputStream) -> T): T {
        val file = File(context.filesDir, "opennlp/$filename")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            context.assets.open("models/$filename").use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
        return file.inputStream().use { factory(it) }
    }
}
```

### 1.3 OpenNLP Models

Bundle these in `local/src/main/assets/models/`:

```
local/src/main/assets/models/
├── opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin   (— sentence detection)
└── opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin     (— tokenization)
```

Download from: https://opennlp.apache.org/models.html

> **Note:** Both `SentenceDetectorME` and `TokenizerME` are **thread-safe** as of OpenNLP 3.0.0 — a single instance can be shared across coroutine threads without synchronization wrappers.

---

## Phase 2: Embedding Engine (`:memelm`)

### 2.1 C++ EmbeddingEngine

```cpp
// memelm/src/main/cpp/EmbeddingEngine.h
#pragma once
#include <string>
#include <vector>
#include "llama.h"

class EmbeddingEngine {
public:
    bool init(llama_model* model, int contextSize = 512);

    // Returns L2-normalized embedding vector (dim = llama_model_n_embd(model))
    std::vector<float> embed(const std::string& text);

    std::vector<std::vector<float>> embedBatch(
        const std::vector<std::string>& texts);

    int dimension() const;
    void release();

private:
    llama_model*   m_model = nullptr;
    llama_context* m_ctx   = nullptr;
    int n_embd  = 0;
    int n_batch = 512;
};
```

Key implementation notes:
- Uses the **same `llama_model*`** as `LLMInference` (loaded once from the same GGUF)
- Creates a **separate `llama_context*`** with `params.embedding = true` and `LLAMA_POOLING_TYPE_MEAN`
- `llama_encode()` is used instead of `llama_decode()`
- Vectors are L2-normalized so FAISS inner product = cosine similarity

### 2.2 JNI Bridge (added to `memelm.cpp`)

```cpp
JNIEXPORT jfloatArray JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeEmbed(
    JNIEnv* env, jobject /* this */, jstring text);
```

### 2.3 Kotlin Interface + Impl

```kotlin
// memelm/src/main/java/fun/walawe/memelm/inference/EmbeddingEngine.kt
package fun.walawe.memelm.inference

interface EmbeddingEngine {
    suspend fun init(modelPath: String): Boolean
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
    fun dimension(): Int
    fun release()
}
```

`EmbeddingEngineImpl` follows the same `@FastNative` + `callbackFlow` pattern as `InferenceEngineImpl`, using the shared `llamaDispatcher`.

---

## Phase 3: Vector Store (`:vector` module — FAISS JNI bridge)

### 3.1 Module Structure

```
vector/
├── build.gradle.kts
├── libs/
│   └── arm64-v8a/
│       └── libfaiss.a                    (PREBUILT — commit this binary)
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt            (updated to link libfaiss.a)
│   │   │   ├── VectorStore.h             (FAISS wrapper class)
│   │   │   └── vector.cpp                (JNI bridge methods)
│   │   └── java/io/github/antinormies/vector/
│   │       └── VectorStore.kt            (Kotlin API)
│   └── test/
```

### 3.2 C++ VectorStore

```cpp
// vector/src/main/cpp/VectorStore.h
#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include "faiss/IndexFlat.h"
#include "faiss/IndexIDMap.h"

class VectorStore {
public:
    VectorStore(int dimension)
        : dim(dimension) {
        auto* inner = new faiss::IndexFlatIP(dim);
        index = new faiss::IndexIDMap(inner);
    }

    ~VectorStore() { delete index; }

    void add(int64_t id, const float* embedding) {
        index->add_with_ids(1, embedding, (faiss::idx_t*)&id);
    }

    struct SearchResult {
        int64_t id;
        float score;
    };

    std::vector<SearchResult> search(const float* query, int topK) {
        std::vector<faiss::idx_t> labels(topK);
        std::vector<float> distances(topK);
        index->search(1, query, topK, distances.data(), labels.data());

        std::vector<SearchResult> results;
        for (int i = 0; i < topK && labels[i] != -1; i++) {
            results.push_back({static_cast<int64_t>(labels[i]), distances[i]});
        }
        return results;
    }

    void remove(int64_t id) {
        index->remove_ids(faiss::IDSelectorSingle(id));
    }

    void save(const std::string& path) {
        faiss::write_index(index, path.c_str());
    }

    void load(const std::string& path) {
        FILE* f = fopen(path.c_str(), "rb");
        if (f) {
            fclose(f);
            auto* loaded = faiss::read_index(path.c_str());
            delete index;
            index = dynamic_cast<faiss::IndexIDMap*>(loaded);
        }
    }

    int size() const { return index->ntotal(); }
    int dimension() const { return dim; }

private:
    faiss::IndexIDMap* index;
    int dim;
};
```

### 3.3 CMakeLists.txt (Link Prebuilt libfaiss.a)

```cmake
# vector/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("vector")

# Prebuilt FAISS static library
add_library(faiss STATIC IMPORTED)
set_target_properties(faiss PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libfaiss.a
)

# Our JNI library
add_library(${CMAKE_PROJECT_NAME} SHARED
    vector.cpp
    VectorStore.h
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_SOURCE_DIR}/../../faiss  # FAISS headers from submodule
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    faiss          # static libfaiss.a
    android
    log
)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3 -march=armv8-a+fp16)
```

### 3.4 JNI Bridge (`vector.cpp`)

```cpp
// vector/src/main/cpp/vector.cpp
#include <jni.h>
#include <string>
#include "VectorStore.h"

static VectorStore* g_store = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_antinormies_vector_VectorStore_nativeInit(
    JNIEnv* env, jobject /* this */, jint dimension, jstring checkpointPath) {

    delete g_store;
    g_store = new VectorStore(dimension);

    if (checkpointPath != nullptr) {
        const char* path = env->GetStringUTFChars(checkpointPath, nullptr);
        g_store->load(path);
        env->ReleaseStringUTFChars(checkpointPath, path);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_antinormies_vector_VectorStore_nativeAdd(
    JNIEnv* env, jobject /* this */, jlong id, jfloatArray embedding) {

    if (!g_store) return;
    jfloat* vec = env->GetFloatArrayElements(embedding, nullptr);
    g_store->add(id, vec);
    env->ReleaseFloatArrayElements(embedding, vec, JNI_ABORT);
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_antinormies_vector_VectorStore_nativeSearch(
    JNIEnv* env, jobject /* this */, jfloatArray queryEmbedding, jint topK) {

    if (!g_store) return nullptr;

    jfloat* query = env->GetFloatArrayElements(queryEmbedding, nullptr);
    auto results = g_store->search(query, topK);
    env->ReleaseFloatArrayElements(queryEmbedding, query, JNI_ABORT);

    // Return as array of [id, score] doublets
    jclass resultClass = env->FindClass("io/github/antinormies/vector/VectorStore$SearchResult");
    // ... (JNI object construction)
    return nullptr;  // placeholder — full impl builds SearchResult[]
}

JNIEXPORT void JNICALL
Java_io_github_antinormies_vector_VectorStore_nativeRemove(
    JNIEnv* env, jobject /* this */, jlong id) {
    if (g_store) g_store->remove(id);
}

JNIEXPORT void JNICALL
Java_io_github_antinormies_vector_VectorStore_nativeSave(
    JNIEnv* env, jobject /* this */, jstring path) {
    if (!g_store) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    g_store->save(p);
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT jint JNICALL
Java_io_github_antinormies_vector_VectorStore_nativeSize(
    JNIEnv* env, jobject /* this */) {
    return g_store ? g_store->size() : 0;
}

}  // extern "C"
```

### 3.5 Kotlin VectorStore API

```kotlin
// vector/src/main/java/io/github/antinormies/vector/VectorStore.kt
package io.github.antinormies.vector

class VectorStore {
    data class SearchResult(val id: Long, val score: Float)

    // Called once at app startup
    fun init(dimension: Int, checkpointPath: String? = null): Boolean =
        nativeInit(dimension, checkpointPath)

    fun add(id: Long, embedding: FloatArray) =
        nativeAdd(id, embedding)

    fun search(queryEmbedding: FloatArray, topK: Int = 5): List<SearchResult> =
        nativeSearch(queryEmbedding, topK)

    fun remove(id: Long) = nativeRemove(id)
    fun save(path: String) = nativeSave(path)
    fun size(): Int = nativeSize()

    // JNI externals
    private external fun nativeInit(dimension: Int, checkpointPath: String?): Boolean
    private external fun nativeAdd(id: Long, embedding: FloatArray)
    private external fun nativeSearch(queryEmbedding: FloatArray, topK: Int): List<SearchResult>
    private external fun nativeRemove(id: Long)
    private external fun nativeSave(path: String)
    private external fun nativeSize(): Int

    companion object {
        init {
            System.loadLibrary("vector")
        }
    }
}
```

---

## Phase 4: Room DB Extensions (`:local`)

### 4.1 ChunkEntity

```kotlin
// local/src/main/java/fun/walawe/local/data/ChunkEntity.kt
@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["messageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("messageId"), Index("faissId", unique = true)],
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val conversationId: String,
    val chunkIndex: Int,
    val text: String,
    val faissId: Long,
    val createdAt: Long,
)
```

### 4.2 FaissMappingEntity

```kotlin
// local/src/main/java/fun/walawe/local/data/FaissMappingEntity.kt
@Entity(
    tableName = "faiss_mappings",
    indices = [Index("chunkId", unique = true)],
)
data class FaissMappingEntity(
    @PrimaryKey val faissId: Long,
    val chunkId: String,
    val conversationId: String,
    val createdAt: Long,
)
```

### 4.3 ChunkDao

```kotlin
@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE faissId IN (:faissIds) ORDER BY chunkIndex ASC")
    suspend fun getChunksByFaissIds(faissIds: List<Long>): List<ChunkEntity>

    @Query("SELECT faissId FROM chunks WHERE conversationId = :conversationId")
    suspend fun getFaissIdsByConversation(conversationId: String): List<Long>

    @Insert
    suspend fun insert(chunk: ChunkEntity)

    @Query("DELETE FROM chunks WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
```

### 4.4 AppDatabase + Migration (v1 → v2)

```kotlin
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ChunkEntity::class, FaissMappingEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun chunkDao(): ChunkDao
    abstract fun faissMappingDao(): FaissMappingDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chunks (
                id TEXT NOT NULL PRIMARY KEY,
                messageId TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL,
                text TEXT NOT NULL,
                faissId INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY (messageId) REFERENCES messages(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX idx_chunks_messageId ON chunks(messageId)")
        db.execSQL("CREATE UNIQUE INDEX idx_chunks_faissId ON chunks(faissId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS faiss_mappings (
                faissId INTEGER NOT NULL PRIMARY KEY,
                chunkId TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX idx_faiss_mappings_chunkId ON faiss_mappings(chunkId)")
    }
}
```

---

## Phase 5: Advanced MemoryService (`:local` — RAG Orchestrator)

This replaces the naive keyword `MemoryService` with the full RAG pipeline.

```kotlin
// local/src/main/java/fun/walawe/local/service/MemoryService.kt
// REWRITE of existing file

@Singleton
class MemoryService @Inject constructor(
    private val preprocessingService: PreprocessingService,
    private val embeddingEngine: EmbeddingEngine,    // from :memelm
    private val vectorStore: VectorStore,            // from :vector (FAISS JNI)
    private val chunkDao: ChunkDao,
) {
    companion object {
        const val TOP_K = 5
        const val MIN_SCORE = 0.6f
    }

    data class RetrievalResult(
        val chunks: List<ChunkEntity>,
        val scores: List<Float>,
    )

    /**
     * Full RAG retrieval: preprocess → embed → FAISS search → fetch from Room
     */
    suspend fun retrieve(query: String): RetrievalResult? {
        val preprocessed = preprocessingService.preprocess(query)
        val queryVec = embeddingEngine.embed(preprocessed.normalized)
        if (queryVec.isEmpty()) return null

        val results = vectorStore.search(queryVec, TOP_K)
        val filtered = results.filter { it.score >= MIN_SCORE }
        if (filtered.isEmpty()) return null

        val chunks = chunkDao.getChunksByFaissIds(filtered.map { it.id })
        return RetrievalResult(
            chunks = chunks,
            scores = filtered.map { it.score },
        )
    }

    /**
     * Store a message as chunk(s) in FAISS + Room.
     * Called after assistant response.
     */
    suspend fun storeMessage(
        messageId: String,
        conversationId: String,
        text: String,
    ) {
        val preprocessed = preprocessingService.preprocess(text)
        val chunks = if (preprocessed.sentences.size <= 1 || text.length <= 1000) {
            listOf(text)
        } else {
            buildChunks(preprocessed.sentences, 1000)
        }

        for ((index, chunkText) in chunks.withIndex()) {
            val vec = embeddingEngine.embed(chunkText)
            if (vec.isEmpty()) continue
            val faissId = generateFaissId()
            vectorStore.add(faissId, vec)
            chunkDao.insert(ChunkEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                conversationId = conversationId,
                chunkIndex = index,
                text = chunkText,
                faissId = faissId,
                createdAt = System.currentTimeMillis(),
            ))
        }
    }

    /**
     * Remove all vectors for a conversation from FAISS + Room.
     */
    suspend fun deleteConversationMemory(conversationId: String) {
        val faissIds = chunkDao.getFaissIdsByConversation(conversationId)
        for (faissId in faissIds) vectorStore.remove(faissId)
        chunkDao.deleteByConversation(conversationId)
    }

    private fun buildChunks(sentences: List<String>, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.length + sentence.length > maxChars && current.isNotEmpty()) {
                result.add(current.toString()); current.clear()
            }
            current.append(sentence).append(" ")
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private val nextFaissId = AtomicLong(System.currentTimeMillis())
    private fun generateFaissId(): Long = nextFaissId.getAndIncrement()
}
```

### 5.1 :local build.gradle.kts — add module deps

```kotlin
dependencies {
    implementation(project(":memelm"))   // EmbeddingEngine interface
    implementation(project(":vector"))   // VectorStore class
    implementation("org.apache.opennlp:opennlp-runtime:3.0.0-M3")
    // ... existing ...
}
```

---

## Phase 6: Context Injection (`:app`)

### 6.1 ChatMLBuilder — buildWithContext()

```kotlin
// app/src/main/java/fun/walawe/memechat/data/ChatMLBuilder.kt
object ChatMLBuilder {
    // ... existing buildFull(), buildTurn(), buildFullFromHistory() ...

    fun buildWithContext(
        systemPrompt: String,
        contextChunks: List<String>,
        contextScores: List<Float>,
        userMessage: String,
        messages: List<Pair<String, String>>,
        forReasoning: Boolean = false,
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("${IM_START}system\n")
            sb.append(systemPrompt)
            sb.append("\n\nRelevant conversation history:\n")
            contextChunks.forEachIndexed { i, chunk ->
                sb.append("[Context ${i + 1} (relevance: ${"%.2f".format(contextScores[i])})]\n")
                sb.append(chunk).append("\n\n")
            }
            sb.append(IM_END).append('\n')
        }
        val recent = messages.takeLast(6)
        for ((role, content) in recent) {
            sb.append("$IM_START$role\n$content$IM_END\n")
        }
        sb.append("${IM_START}user\n$userMessage$IM_END\n")
        sb.append("${IM_START}assistant\n")
        if (forReasoning) sb.append(THINK)
        return sb.toString()
    }
}
```

---

## Phase 7: ViewModel Integration (`:app`)

### 7.1 ChatViewModel — Updated sendMessage()

```kotlin
// Key changes in ChatViewModel.sendMessage()

fun sendMessage(message: String) {
    // ... existing guards and setup ...

    safeViewModelScope.launch {
        val userMsgId = UUID.randomUUID().toString()
        val assistantId = UUID.randomUUID().toString()

        // RAG retrieval (text-only messages only — image IS the context)
        val retrieved = if (persistedImagePath == null) {
            memoryService.retrieve(message)
        } else null

        // ... save user message to Room ...

        val chatML = when {
            imageBitmap != null -> ""  // handled by sendConversationWithImage
            retrieved != null -> ChatMLBuilder.buildWithContext(
                systemPrompt = DEFAULT_MODEL_SYSTEM_PROMPT,
                contextChunks = retrieved.chunks.map { it.text },
                contextScores = retrieved.scores,
                userMessage = message,
                messages = existingHistory.reversed().map {
                    it.role.name.lowercase() to it.text
                } + ("user" to message),
                forReasoning = forReasoning,
            )
            resetFirst -> ChatMLBuilder.buildFullFromHistory(/*...existing...*/)
            else -> ChatMLBuilder.buildTurn(message, forReasoning)
        }

        // ... generate, collect tokens, save response ...

        // Fire-and-forget: store response as vector
        launch {
            memoryService.storeMessage(assistantId, conversationId, responseText)
            vectorStore.save(checkpointPath)
        }
        launch {
            memoryService.storeMessage(userMsgId, conversationId, message)
        }
    }
}
```

### 7.2 Init Sequence

```kotlin
init {
    safeViewModelScope.launch {
        prepareModel()              // existing

        // Init FAISS vector store
        val checkpointDir = File(context.filesDir, "rag").apply { mkdirs() }
        vectorStore.init(
            dimension = embeddingEngine.dimension(),  // 2048
            checkpointPath = File(checkpointDir, "faiss.index").absolutePath,
        )
        if (vectorStore.size() == 0) {
            // Rebuild index from Room (e.g., after first install or index corruption)
            rebuildIndexFromRoom()
        }
    }
}
```

---

## Data Flow

### Happy path (RAG hit)

```
User: "explain memes to me"
  │
  ├─ PreprocessingService.preprocess()
  │   ├─ OpenNLP sentDetect → ["explain memes to me"]
  │   ├─ OpenNLP tokenize → [explain, memes, me]
  │   └─ (embedding model handles semantics natively — no synonym expansion needed)
  │
  ├─ EmbeddingEngine.embed()          (:memelm JNI → llama_encode)
  │   └─ 2048-dim L2-normalized float vector
  │
  ├─ VectorStore.search()              (:vector JNI → FAISS IndexFlatIP)
  │   └─ Top-5: [(id=42, score=0.91), (id=17, score=0.85)]
  │
  ├─ ChunkDao.getChunksByFaissIds()    (Room)
  │   └─ [ChunkEntity("meme definition..."), ChunkEntity("meme history...")]
  │
  ├─ ChatMLBuilder.buildWithContext()  (:app)
  │   └─ Context injected into system prompt
  │
  ├─ InferenceEngine.sendConversation() (:memelm → llama.cpp)
  │   └─ LLM generates with context
  │
  └─ (after response)
      ├─ MemoryService.storeMessage()  (embed + FAISS add + Room insert)
      └─ VectorStore.save()            (FAISS checkpoint to disk)
```

### Cold start / no match

```
User: "hello" (first message, empty index)
  │
  ├─ PreprocessingService.preprocess() → "hello"
  ├─ EmbeddingEngine.embed() → valid vector
  ├─ VectorStore.search() → empty (index has 0 vectors)
  │
  └─ Falls through to existing: buildFullFromHistory() without RAG context
```

---

## Build System Changes

### `:vector/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.antinormies.vector"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Ensure FAISS prebuilt lib is packaged
android.sourceSets.main.jniLibs.srcDirs("libs")
```

### `:local/build.gradle.kts` — add deps

```kotlin
dependencies {
    implementation(project(":memelm"))
    implementation(project(":vector"))
    implementation(libs.opennlp.runtime)
    // ... existing: Room, Hilt, Timber ...
}
```

### `libs.versions.toml` additions

```toml
[versions]
opennlp = "3.0.0-M3"

[libraries]
opennlp-runtime = { group = "org.apache.opennlp", name = "opennlp-runtime", version.ref = "opennlp" }
```

### FAISS prebuilt static lib — one-time build

> **Prerequisites:** CMake 3.24+, Android NDK (tested on r28.2.13676358), and the following patch applied to the FAISS submodule's `faiss/faiss/CMakeLists.txt`:
> 1. **`blas_stubs.cpp`** added to `FAISS_SRC` — provides stub `sgemm_`/`dgemm_` (BLAS is not available on Android, but these symbols are never called by `IndexFlat` at runtime)
> 2. **`FAISS_ENABLE_BLAS` option** (default ON) wraps the BLAS/LAPACK `find_package` — set to `OFF` for Android

```bash
# Step 1: Configure
cd faiss
mkdir -p build-android && cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$HOME/Android/Sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DFAISS_OPT_LEVEL=generic \
  -DBUILD_TESTING=OFF \
  -DBUILD_SHARED_LIBS=OFF \
  -DFAISS_ENABLE_GPU=OFF \
  -DFAISS_ENABLE_PYTHON=OFF \
  -DFAISS_ENABLE_C_API=OFF \
  -DFAISS_ENABLE_EXTRAS=OFF \
  -DFAISS_ENABLE_BLAS=OFF \
  -DCMAKE_BUILD_TYPE=Release

# Step 2: Build
cmake --build . -j$(nproc)

# Step 3: Copy into :vector module
cp faiss/libfaiss.a ../vector/libs/arm64-v8a/
```

---

## Migration Path

| Step | What | Files Affected | Module |
|------|------|---------------|--------|
| 1 | Build FAISS static lib, copy to `vector/libs/` | `vector/libs/arm64-v8a/libfaiss.a` (new) | root |
| 2 | Add OpenNLP models to assets | `local/src/main/assets/models/opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin`, `opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin` | `:local` |
| 3 | Update `:vector` CMakeLists.txt to link `libfaiss.a` | `vector/src/main/cpp/CMakeLists.txt` | `:vector` |
| 4 | Add `VectorStore.h` + JNI bridge in `vector.cpp` | `vector/src/main/cpp/VectorStore.h`, `vector.cpp` | `:vector` |
| 5 | Add `VectorStore.kt` Kotlin API | `vector/src/main/java/.../VectorStore.kt` | `:vector` |
| 6 | Add `EmbeddingEngine` C++ class + JNI | `memelm/src/main/cpp/EmbeddingEngine.h/.cpp`, `memelm.cpp` | `:memelm` |
| 7 | Add `EmbeddingEngine.kt` + impl | `memelm/.../inference/EmbeddingEngine.kt` | `:memelm` |
| 8 | Add `PreprocessingService.kt` (OpenNLP) | `local/.../service/PreprocessingService.kt` | `:local` |
| 9 | Add Room entities + DAOs + migration | `local/.../data/ChunkEntity.kt`, `FaissMappingEntity.kt`, `ChunkDao.kt` | `:local` |
| 10 | Rewrite `MemoryService.kt` (RAG orchestrator) | `local/.../service/MemoryService.kt` | `:local` |
| 11 | Add `buildWithContext()` to ChatMLBuilder | `app/.../data/ChatMLBuilder.kt` | `:app` |
| 12 | Update `ChatViewModel` | `app/.../presenter/ChatViewModel.kt` | `:app` |
| 13 | Add Hilt bindings for new services | `local/.../di/DatabaseModule.kt`, new `RagModule` | `:local`, `:memelm` |
| 14 | Update Gradle deps + version catalog | `local/build.gradle.kts`, `gradle/libs.versions.toml` | all |
