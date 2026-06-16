#include <jni.h>
#include <string>
#include "LLMInference.h"
#include "EmbeddingEngine.h"
#include "logging.h"

static LLMInference  g_inference;
static EmbeddingEngine g_embedding;

extern "C" {
JNIEXPORT jboolean JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeInit(
        JNIEnv *env, jobject /* this */,
        jstring modelPath, jstring mmprojPath, jstring backendPath, jint contextSize,
        jboolean useVulkan) {
    const char *model = env->GetStringUTFChars(modelPath, nullptr);
    const char *mmproj = env->GetStringUTFChars(mmprojPath, nullptr);
    const char *backend = env->GetStringUTFChars(backendPath, nullptr);
    bool ok = g_inference.init(model, mmproj, backend, contextSize, useVulkan);
    env->ReleaseStringUTFChars(modelPath, model);
    env->ReleaseStringUTFChars(mmprojPath, mmproj);
    env->ReleaseStringUTFChars(backendPath, backend);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessImageAndText(
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

JNIEXPORT jstring JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeGetBackendInfo(
        JNIEnv *env, jobject) {
    std::string info = g_inference.getBackendInfo();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeRelease(
        JNIEnv * /* env */, jobject /* this */) {
    g_inference.release();
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeSetSystemPrompt(
        JNIEnv *env, jobject /* this */, jstring prompt) {
    const char *str = env->GetStringUTFChars(prompt, nullptr);
    g_inference.setSystemPrompt(str);
    env->ReleaseStringUTFChars(prompt, str);
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeResetContext(
        JNIEnv * /* env */, jobject /* this */) {
    g_inference.resetContext();
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeCancelGeneration(
        JNIEnv * /* env */, jobject /* this */) {
    g_inference.cancelGeneration();
}

JNIEXPORT jboolean JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeIsGenerating(
        JNIEnv * /* env */, jobject /* this */) {
    return g_inference.isGenerating() ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// EmbeddingEngine JNI bridge
// ============================================================================

/**
 * nativeInit: Initialise the embedding engine.
 *
 * Must be called AFTER InferenceEngineImpl.nativeInit has loaded the model.
 * Retrieves the shared llama_model* from LLMInference and creates a
 * dedicated embedding context.
 *
 * @param contextSize  Maximum token window (e.g. 512).
 * @return true if the embedding context was created successfully.
 */
JNIEXPORT jboolean JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeInit(
        JNIEnv* /* env */, jobject /* this */, jint contextSize) {
    llama_model* model = g_inference.getModel();
    if (model == nullptr) {
        LOGe("EmbeddingEngine JNI: LLMInference has no loaded model — call init first");
        return JNI_FALSE;
    }
    bool ok = g_embedding.init(model, static_cast<int>(contextSize));
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * nativeEmbed: Embed a single text string.
 *
 * @param text  UTF-8 input (preprocessed query or chunk).
 * @return      float[] of dimension = llama_n_embd (2048 for LLaMA 3B).
 *              Returns null on failure.
 */
JNIEXPORT jfloatArray JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeEmbed(
        JNIEnv* env, jobject /* this */, jstring text) {
    const char* utfText = env->GetStringUTFChars(text, nullptr);
    if (utfText == nullptr) return nullptr;

    std::vector<float> vec = g_embedding.embed(utfText);
    env->ReleaseStringUTFChars(text, utfText);

    if (vec.empty()) return nullptr;

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(vec.size()));
    if (result == nullptr) return nullptr;

    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(vec.size()), vec.data());
    return result;
}

/**
 * nativeEmbedBatch: Embed multiple texts in one call.
 *
 * Each string is embedded sequentially.  Returns an array of float[]
 * where the outer array length matches the input count.
 *
 * @param texts  String array of texts to embed.
 * @return       Array of float[] embeddings, one per input.
 */
JNIEXPORT jobjectArray JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeEmbedBatch(
        JNIEnv* env, jobject /* this */, jobjectArray texts) {
    if (texts == nullptr) return nullptr;

    jsize count = env->GetArrayLength(texts);
    if (count == 0) return nullptr;

    // Collect UTF-8 strings sequentially — avoids JNI pinning.
    std::vector<std::string> utfTexts;
    utfTexts.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
        if (js == nullptr) continue;
        const char* cstr = env->GetStringUTFChars(js, nullptr);
        if (cstr != nullptr) {
            utfTexts.emplace_back(cstr);
            env->ReleaseStringUTFChars(js, cstr);
        }
        env->DeleteLocalRef(js);
    }

    std::vector<std::vector<float>> embeddings = g_embedding.embedBatch(utfTexts);
    if (embeddings.empty()) return nullptr;

    // Build Kotlin Array<FloatArray>.
    jclass floatArrayClass = env->FindClass("[F");
    if (floatArrayClass == nullptr) return nullptr;

    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(embeddings.size()), floatArrayClass, nullptr);
    if (result == nullptr) return nullptr;

    for (size_t i = 0; i < embeddings.size(); ++i) {
        const auto& vec = embeddings[i];
        jfloatArray jVec = env->NewFloatArray(static_cast<jsize>(vec.size()));
        if (jVec == nullptr) {
            // Clean up already-created arrays on failure.
            for (size_t j = 0; j < i; ++j) {
                jfloatArray old = static_cast<jfloatArray>(
                    env->GetObjectArrayElement(result, static_cast<jsize>(j)));
                if (old) env->DeleteLocalRef(old);
            }
            env->DeleteLocalRef(result);
            return nullptr;
        }
        env->SetFloatArrayRegion(jVec, 0, static_cast<jsize>(vec.size()), vec.data());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), jVec);
        env->DeleteLocalRef(jVec);
    }

    env->DeleteLocalRef(floatArrayClass);
    return result;
}

/**
 * nativeDimension: Query the embedding dimension.
 */
JNIEXPORT jint JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeDimension(
        JNIEnv* /* env */, jobject /* this */) {
    return static_cast<jint>(g_embedding.dimension());
}

/**
 * nativeRelease: Tear down the embedding context.
 *
 * Safe to call multiple times.  Should be called when the app is
 * shutting down or when the user switches to a different model.
 */
JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeRelease(
        JNIEnv* /* env */, jobject /* this */) {
    g_embedding.release();
}

} // extern "C"