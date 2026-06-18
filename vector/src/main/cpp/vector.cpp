#include <jni.h>
#include <mutex>
#include <new>
#include <faiss/IndexFlat.h>
#include <faiss/IndexIDMap.h>
#include <faiss/index_io.h>
#include <faiss/impl/IDSelector.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VectorStore", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VectorStore", __VA_ARGS__)

static faiss::IndexIDMap* g_index = nullptr;
static std::mutex g_mutex;
static JavaVM* g_vm = nullptr;
static jclass searchResultClass = nullptr;
static jmethodID searchResultCtor = nullptr;

static void destroyIndex() {
    delete g_index;
    g_index = nullptr;
}

static faiss::IndexIDMap* createFreshIndex(int dim) {
    auto* flat = new faiss::IndexFlatIP((faiss::idx_t)dim);
    auto* idmap = new faiss::IndexIDMap(flat);
    return idmap;
}

static faiss::IndexIDMap* loadIndex(const char* path) {
    try {
        auto* loaded = faiss::read_index(path);
        auto* idmap = dynamic_cast<faiss::IndexIDMap*>(loaded);
        if (!idmap) {
            idmap = new faiss::IndexIDMap(loaded);
        }
        LOGI("Loaded index from %s (dim=%d, ntotal=%zd)", path, idmap->d, idmap->ntotal);
        return idmap;
    } catch (const faiss::FaissException& e) {
        LOGE("Failed to load index from %s: %s", path, e.what());
        return nullptr;
    } catch (const std::exception& e) {
        LOGE("Unexpected error loading index: %s", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localRef = env->FindClass(
        "fun/walawe/vector/VectorStore$SearchResult");
    if (!localRef) {
        LOGE("Failed to find SearchResult class");
        return JNI_ERR;
    }
    searchResultClass = (jclass)env->NewGlobalRef(localRef);
    env->DeleteLocalRef(localRef);

    searchResultCtor = env->GetMethodID(
        searchResultClass, "<init>", "(JF)V");
    if (!searchResultCtor) {
        LOGE("Failed to find SearchResult constructor");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

// Called once on app / service start. Creates or reloads the index.

extern "C" JNIEXPORT jboolean JNICALL
Java_fun_walawe_vector_VectorStore_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring checkpointPath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    destroyIndex();

    if (checkpointPath != nullptr) {
        const char* path = env->GetStringUTFChars(checkpointPath, nullptr);
        g_index = loadIndex(path);
        env->ReleaseStringUTFChars(checkpointPath, path);
    }

    if (g_index) {
        LOGI("Init done: loaded index (dim=%d, ntotal=%zd)", g_index->d, g_index->ntotal);
    } else {
        LOGI("Init done: no checkpoint, will lazy-create on first add");
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_vector_VectorStore_nativeAdd(
    JNIEnv* env, jobject /* thiz */, jlong id, jfloatArray embedding) {

    int dim = (int)env->GetArrayLength(embedding);
    if (dim <= 0) return;

    std::lock_guard<std::mutex> lock(g_mutex);

    // Lazy-create index on first add if no checkpoint was loaded
    if (!g_index) {
        g_index = createFreshIndex(dim);
        LOGI("Lazy-created index (dim=%d) on first add", dim);
    }

    if (dim != g_index->d) {
        LOGE("nativeAdd: dimension mismatch — index=%d, got=%d",
             g_index->d, dim);
        return;
    }

    // Remove any existing entry with this ID to avoid duplicates
    faiss::idx_t faissId = (faiss::idx_t)id;
    faiss::IDSelectorArray sel(1, &faissId);
    g_index->remove_ids(sel);

    // Add the new vector
    jfloat* vec = env->GetFloatArrayElements(embedding, nullptr);
    g_index->add_with_ids(1, vec, &faissId);
    env->ReleaseFloatArrayElements(embedding, vec, JNI_ABORT);

    LOGI("Added/updated id=%lld, ntotal=%zd", (long long)id, g_index->ntotal);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_fun_walawe_vector_VectorStore_nativeSearch(
    JNIEnv* env, jobject /* thiz */, jfloatArray queryEmbedding, jint topK) {

    int k = (int)topK;
    if (k <= 0) k = 1;

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_index || g_index->ntotal == 0) {
        return env->NewObjectArray(0, searchResultClass, nullptr);
    }

    jfloat* query = env->GetFloatArrayElements(queryEmbedding, nullptr);

    auto distances = std::vector<float>((size_t)k);
    auto labels = std::vector<faiss::idx_t>((size_t)k);

    try {
        g_index->search(1, query, (faiss::idx_t)k, distances.data(), labels.data());
    } catch (const faiss::FaissException& e) {
        LOGE("Search failed: %s", e.what());
        env->ReleaseFloatArrayElements(queryEmbedding, query, JNI_ABORT);
        return env->NewObjectArray(0, searchResultClass, nullptr);
    }

    env->ReleaseFloatArrayElements(queryEmbedding, query, JNI_ABORT);

    // Count valid results (FAISS fills unfilled slots with label=-1)
    size_t count = 0;
    for (int i = 0; i < k; i++) {
        if (labels[(size_t)i] >= 0) count++;
    }

    jobjectArray arr = env->NewObjectArray((jsize)count, searchResultClass, nullptr);
    if (!arr) return nullptr;

    size_t outIdx = 0;
    for (int i = 0; i < k; i++) {
        if (labels[(size_t)i] < 0) continue;
        jobject obj = env->NewObject(
            searchResultClass, searchResultCtor,
            (jlong)labels[(size_t)i], (jfloat)distances[(size_t)i]);
        if (!obj) {
            LOGE("Failed to create SearchResult object");
            env->DeleteLocalRef(arr);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, (jsize)outIdx++, obj);
        env->DeleteLocalRef(obj);
    }

    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_vector_VectorStore_nativeRemove(
    JNIEnv* /* env */, jobject /* thiz */, jlong id) {

    if (!g_index) return;
    std::lock_guard<std::mutex> lock(g_mutex);

    faiss::idx_t faissId = (faiss::idx_t)id;
    faiss::IDSelectorArray sel(1, &faissId);
    size_t removed = g_index->remove_ids(sel);

    if (removed > 0) {
        LOGI("Removed id=%lld (%zu entries), ntotal=%zd",
             (long long)id, removed, g_index->ntotal);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_vector_VectorStore_nativeSave(
    JNIEnv* env, jobject /* thiz */, jstring path) {

    if (!g_index) return;
    const char* p = env->GetStringUTFChars(path, nullptr);

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        try {
            faiss::write_index(g_index, p);
            LOGI("Saved index to %s (ntotal=%zd)", p, g_index->ntotal);
        } catch (const faiss::FaissException& e) {
            LOGE("Failed to save index: %s", e.what());
        }
    }

    env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_vector_VectorStore_nativeRelease(
    JNIEnv* /* env */, jobject /* thiz */) {

    std::lock_guard<std::mutex> lock(g_mutex);
    destroyIndex();
    LOGI("Index released");
}

extern "C" JNIEXPORT jint JNICALL
Java_fun_walawe_vector_VectorStore_nativeSize(
    JNIEnv* /* env */, jobject /* thiz */) {

    if (!g_index) return 0;
    return (jint)g_index->ntotal;
}

extern "C" JNIEXPORT jint JNICALL
Java_fun_walawe_vector_VectorStore_nativeDimension(
    JNIEnv* /* env */, jobject /* thiz */) {

    if (!g_index) return 0;
    return (jint)g_index->d;
}
