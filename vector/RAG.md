# RAG Implementation Plan (For Review)

> **Status:** Plan — review and approve before building.
> This document describes *what* needs to be built, *how* each piece works, and *why* decisions were made. Code files should be read alongside this plan.

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ANDROID APPLICATION                          │
│                                                                     │
│  ┌────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │ :app        │  │ :local              │  │ :memelm             │  │
│  │ ChatVM      │──► MemoryService       │  │ EmbeddingEngine     │  │
│  │ sendMsg()   │  │ (RAG orchestrator)  │  │  (llama_decode)     │  │
│  │  → retrieve │  │  PreprocessTextSvc  │  │ LLMInference        │  │
│  │  → generate │  │  ChunkDao (Room)    │  │                     │  │
│  └──────┬──────┘  └─────────┬───────────┘  └──────────┬──────────┘  │
│         │                   │                          │            │
│         │            ┌──────▼────────┐                 │            │
│         │            │ :vector       │                 │            │
│         │            │ VectorStore   │←── JNI          │            │
│         │            │ libvector.so  │                 │            │
│         │            │  ┌──────────┐ │                 │            │
│         │            │  │ FAISS    │ │                 │            │
│         │            │  │IndexFlat │ │                 │            │
│         │            │  │+IDMap    │ │                 │            │
│         │            │  └──────────┘ │                 │            │
│         │            └──────▲────────┘                 │            │
│         │                   │                          │            │
│         │            ┌──────┴────────┐                 │            │
│         │            │ faiss/        │ (git submodule) │            │
│         │            │ (compiled via │                 │            │
│         │            │  add_subdir)  │                 │            │
│         │            └───────────────┘                 │            │
└─────────────────────────────────────────────────────────────────────┘
```

### Module Dependency Graph

```
:app ──► :local ──► :memelm (EmbeddingEngine interface)
                  ──► :vector (VectorStore class)
      ──► :memelm (InferenceEngine interface)
      ──► :modelpull
      ──► :constant

:vector → no Gradle module deps (pure JNI + FAISS C++)
:memelm → :constant only
:local  → :memelm, :vector
```

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Vector store backend | **FAISS `IndexFlatIP` + `IndexIDMap`** | Battle-tested, exact search, inner product = cosine sim on L2-normed vecs |
| FAISS integration | **`add_subdirectory` the submodule** | Follows same pattern as `memelm/llama.cpp` |
| Save/load | **FAISS native `write_index` / `read_index`** | Self-contained binary format, fast, no need to reinvent |
| Kotlin API | **Explicit lifecycle: `init()` / `release()`** | Path is runtime-provided (Android `filesDir`), not known at load time |
| FAISS knobs | `FAISS_OPT_LEVEL=generic`, `FAISS_ENABLE_*` all OFF | IndexFlat doesn't need GPU, SIMD variants, Python, etc. |

---

## 2. Phase 1: OpenNLP Preprocessing (`:local`)

Text preprocessing splits raw messages into clean chunks suitable for embedding. This is the first step in the RAG pipeline.

| Item | Detail |
|------|--------|
| Library | `org.apache.opennlp:opennlp-runtime:3.0.0-M3` |
| Models | `opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin`, `opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin` |
| Service | `PreprocessTextService` — sentence detection + tokenization + noise reduction |
| Thread safety | Both models are thread-safe as of OpenNLP 3.0.0 |

### Pipeline

```
raw text → sentence detection → tokenization → noise removal → chunk assembly
```

`buildChunks(text, maxChars=1000)` merges sentences up to 1000 chars, producing `Chunk` objects that are each embedded and stored.

---

## 3. Phase 2: Embedding Engine (`:memelm`)

Converts text chunks into vector embeddings using a dedicated embedding model.

*(already implemented — documented for completeness)*

- `EmbeddingEngine` loads its own Gemma GGUF model (separate from the inference model)
- `llama_decode` with `LLAMA_POOLING_TYPE_MEAN`
- L2-normalized output vectors (unit length → inner product = cosine similarity)
- JNI bridge in `memelm.cpp`

### Interface

```kotlin
interface EmbeddingEngine {
    fun init(modelPath: String): Boolean
    fun embed(text: String): FloatArray
    fun release()
}
```

Each call to `embed()` tokenizes → clears KV cache → runs llama_decode → reads pooled embedding → L2-normalizes → returns FloatArray.

---

## 4. Phase 3: Vector Store — FAISS (`:vector`)

Stores and searches embedding vectors. This is the retrieval backend of the RAG pipeline.

### 4.1 FAISS Build Integration

**`vector/build.gradle.kts`** — pass `FAISS_DIR` to CMake, bump cmake version:

```kotlin
externalNativeBuild {
    cmake {
        arguments += "-DFAISS_DIR=${rootProject.projectDir}/faiss"
    }
}
cmake {
    version = "3.31.6"  // matches memelm; >=3.24 needed by FAISS
}
```

**`vector/src/main/cpp/CMakeLists.txt`** — builds FAISS from submodule, compiles vector.cpp:

```cmake
set(FAISS_ENABLE_GPU     OFF)
set(FAISS_ENABLE_PYTHON  OFF)
set(FAISS_ENABLE_C_API   OFF)
set(FAISS_ENABLE_EXTRAS  OFF)
set(FAISS_ENABLE_MKL     OFF)
set(FAISS_ENABLE_SVS     OFF)
set(FAISS_ENABLE_METAL   OFF)
set(FAISS_ENABLE_CUVS    OFF)
set(FAISS_OPT_LEVEL      "generic")

add_subdirectory(${FAISS_DIR})

add_library(vector SHARED vector.cpp)
target_link_libraries(vector PRIVATE faiss android log)
target_compile_options(vector PRIVATE -O3)
```

**Why this works:** Disabling all `FAISS_ENABLE_*` flags avoids OpenMP and BLAS dependencies. `FAISS_OPT_LEVEL=generic` emits portable C++ with no arch-specific SIMD. Since we only use `IndexFlatIP` and `IndexIDMap`, none of the GPU/Python/MKL/SIMD paths are compiled.

#### Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| CMake 3.24+ not on user's system | Build fails | Use same version as memelm (3.31.6) via SDK Manager |
| OpenMP not auto-detected | Build fails | NDK 27+ includes `-fopenmp`; may need `OpenMP_CXX_FLAGS` override |
| FAISS internal cmake policies incompatible with Gradle | Build fails | Fallback to listing FAISS source files manually |
| Compile time | ~2–5 min for first build | Incremental after that |

### 4.2 Files

| File | Purpose |
|------|---------|
| `vector/src/main/cpp/vector.cpp` | JNI bridge + FAISS operations (248 lines) |
| `vector/src/main/cpp/CMakeLists.txt` | Builds FAISS from submodule, compiles vector.cpp |
| `vector/src/main/java/fun/walawe/vector/VectorStore.kt` | Kotlin singleton API |
| `vector/build.gradle.kts` | Passes `FAISS_DIR`, configures NDK/CMake |

### 4.3 C++ Architecture

```cpp
// Global state
static faiss::IndexIDMap*    g_index;         // the index (null until init/lazy-create)
static std::mutex            g_mutex;         // guards all operations
static JavaVM*               g_vm;            // cached for JNI callbacks
static jclass                searchResultClass;  // global ref — cached in JNI_OnLoad
static jmethodID             searchResultCtor;   // "(JF)V" constructor

JNI_OnLoad → caches JavaVM, SearchResult class + constructor

nativeInit(String? checkpointPath):
  lock → destroyIndex()
  if path != null: faiss::read_index(path) → wrap in IndexIDMap if needed
  if no path or load fails: g_index stays null → lazy-created on first add

nativeAdd(long id, float[] embedding):
  lock → auto-detect dim from array length
  if g_index null → createFreshIndex(dim) (lazy init)
  remove_ids(id) to replace duplicates
  add_with_ids(1, embedding, &id)

nativeSearch(float[] query, int topK):
  lock → if index empty → empty result array
  g_index->search(1, query, topK, distances, labels)
  build SearchResult[] from (labels, distances)

nativeRemove(long id):
  lock → IDSelectorArray → remove_ids

nativeSave(String path):
  lock → faiss::write_index(g_index, path)

nativeRelease(): lock → delete g_index
nativeSize(): return g_index->ntotal
nativeDimension(): return g_index->d
```

### 4.4 Key Details

**Dimension auto-detection:** `nativeAdd` reads the array length of the first embedding and uses it to create the index. No need to configure dimension ahead of time. Subsequent calls verify consistency.

**Duplicate handling:** Every `nativeAdd` first removes any existing entry with the same ID via `IDSelectorArray`, then inserts the new vector. This guarantees at most one version of each message.

**JNI class caching:** `JNI_OnLoad` caches `VectorStore$SearchResult` as a global reference. `nativeSearch` creates instances via `NewObject(searchResultClass, searchResultCtor, id, score)` without looking up the class on every call.

**Persistence:** Uses FAISS's native `write_index` / `read_index` binary format. The file contains index type, dimension, all vectors (float32), and all IDs (int64). Self-contained — no external schema needed.

**Thread safety:** Single `std::mutex` protects all operations. The empty-index check in `nativeSearch` is inside the lock to prevent a TOCTOU race with `destroyIndex`.

### 4.5 Kotlin API

```kotlin
object VectorStore {
    fun init(checkpointPath: String? = null): Boolean  // load or empty
    fun add(id: Long, embedding: FloatArray)            // insert/update
    fun search(query: FloatArray, topK: Int = 5): List<SearchResult>
    fun remove(id: Long)                                // delete
    fun save(path: String)                              // persist
    fun release()                                       // free native memory
    fun size(): Int
    fun dimension(): Int

    data class SearchResult(val id: Long, val score: Float)

    init { System.loadLibrary("vector") }  // triggers JNI_OnLoad
}
```

Lifecycle:
1. App `onCreate`: no setup needed
2. First use: call `VectorStore.init("$filesDir/cache.faiss")`
3. On each message: `embed(text)` → `VectorStore.add(id, embedding)`
4. On each query: `VectorStore.search(query, 5)`
5. Periodic: `VectorStore.save(...)`
6. On shutdown: `VectorStore.release()`

---

## 5. Phase 4: Room DB Extensions (`:local`)

*(unchanged from previous plan)*

| Entity | Table | Key |
|--------|-------|-----|
| `ChunkEntity` | `chunks` | `id` (UUID), FK → `messages.id`, indexed `faissId` |
| `FaissMappingEntity` | `faiss_mappings` | PK = `faissId`, indexed `chunkId` |
| `ChunkDao` | — | `getChunksByFaissIds()`, `getFaissIdsByConversation()` |

Migration v1→v2 creates both tables.

---

## 6. Phase 5: MemoryService — RAG Orchestrator (`:local`)

Rewrites existing keyword `MemoryService` to:

```
storeMessage(msg):
  preprocess → chunk → embed each chunk → FAISS.add → Room.insert

retrieve(query):
  preprocess → embed → FAISS.search → filter by MIN_SCORE → Room.fetch

deleteConversation(id):
  FAISS.remove each → Room.delete
```

**Design choice:** Single-sequence embedding (one text at a time). Multi-sequence batching can be added later if performance requires it.

---

## 7. Phase 6: Context Injection (`:app`)

Add `ChatMLBuilder.buildWithContext()`:
- Injects `[Context 1 (relevance: 0.91)] ...` blocks into the system prompt
- Followed by recent message history
- Used when `MemoryService.retrieve()` returns results

---

## 8. Phase 7: ChatViewModel Integration (`:app`)

```
sendMessage(text):
  1. if text-only: MemoryService.retrieve(text)
  2. build ChatML with context (if retrieved) or existing build functions
  3. generate response
  4. fire-and-forget: storeMessage(userMsg) + storeMessage(response) + save checkpoint

init:
  1. prepareModel()
  2. vectorStore.init(checkpointPath)
  3. if index empty: rebuildFromRoom()
```

---

## 9. Migration Path

| # | Phase | What | Files | Module | Status |
|---|-------|------|-------|--------|--------|
| 1 | **Preprocessing** | OpenNLP deps + models | `libs.versions.toml`, `assets/models/` | `:local` | ✅ Done |
| 2 | **Preprocessing** | OpenNLP PreprocessTextService | `local/.../service/PreprocessTextService.kt` | `:local` | ✅ Done |
| 3 | **Embedding** | EmbeddingEngine C++ | `EmbeddingEngine.h/.cpp`, `memelm.cpp` | `:memelm` | ✅ Done |
| 4 | **Embedding** | EmbeddingEngine Kotlin API | `EmbeddingEngine.kt` | `:memelm` | ✅ Done |
| 5 | **Vector Store** | FAISS CMake integration | `vector/CMakeLists.txt`, `build.gradle.kts` | `:vector` | ✅ Done |
| 6 | **Vector Store** | FAISS-backed vector.cpp | `vector/src/main/cpp/vector.cpp` | `:vector` | ✅ Done |
| 7 | **Vector Store** | VectorStore Kotlin API | `VectorStore.kt` | `:vector` | ✅ Done |
| 8 | **Persistence** | Room entities + DAOs + migration | `local/.../data/ChunkEntity.kt`, etc. | `:local` | ❌ Pending |
| 9 | **Orchestrator** | MemoryService rewrite | `local/.../service/MemoryService.kt` | `:local` | ❌ Pending |
| 10 | **Injection** | ChatMLBuilder.buildWithContext | `app/.../data/ChatMLBuilder.kt` | `:app` | ❌ Pending |
| 11 | **Integration** | ChatViewModel integration | `app/.../presenter/ChatViewModel.kt` | `:app` | ❌ Pending |
| 12 | **DI** | Hilt bindings | `local/.../di/DatabaseModule.kt`, `RagModule` | `:local` | ❌ Pending |
| 13 | **Deps** | Gradle deps update | `local/build.gradle.kts`, version catalog | all | ❌ Pending |
