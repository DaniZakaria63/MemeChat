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
        jstring modelPath, jstring mmprojPath, jstring backendPath, jint contextSize) {
    const char *model = env->GetStringUTFChars(modelPath, nullptr);
    const char *mmproj = env->GetStringUTFChars(mmprojPath, nullptr);
    const char *backend = env->GetStringUTFChars(backendPath, nullptr);
    bool ok = g_inference.init(model, mmproj, backend, contextSize);
    env->ReleaseStringUTFChars(modelPath, model);
    env->ReleaseStringUTFChars(mmprojPath, mmproj);
    env->ReleaseStringUTFChars(backendPath, backend);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessImageAndText(
        JNIEnv *env, jobject, jobject bitmap,
        jstring prompt, jboolean forReasoning, jobject tokenCallback) {

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
    g_inference.processImageAndText(env, bitmap, promptStr, forReasoning, &cb);
    env->ReleaseStringUTFChars(prompt, promptStr);
    if (onComplete) env->CallVoidMethod(tokenCallback, onComplete);
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessConversation(
        JNIEnv *env, jobject, jstring chatML, jobject tokenCallback) {

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
    g_inference.processConversation(promptStr, &cb);
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

JNIEXPORT jboolean JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeInit(
        JNIEnv* env, jobject /* this */, jstring modelPath, jint contextSize) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return JNI_FALSE;

    bool ok = g_embedding.init(path, static_cast<int>(contextSize));
    env->ReleaseStringUTFChars(modelPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}


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
 * @warning This native function still under maintenance
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

JNIEXPORT jint JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeDimension(
        JNIEnv* /* env */, jobject /* this */) {
    return static_cast<jint>(g_embedding.dimension());
}

JNIEXPORT void JNICALL
Java_fun_walawe_memelm_inference_EmbeddingEngineImpl_nativeRelease(
        JNIEnv* /* env */, jobject /* this */) {
    g_embedding.release();
}

} // extern "C"