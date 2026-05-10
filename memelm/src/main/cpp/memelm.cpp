#include "LLMInference.h"
#include <jni.h>
#include <stdexcept>

namespace {
    void throwJavaRuntime(JNIEnv * env, const char * message) {
        jclass exClass = env->FindClass("java/lang/RuntimeException");
        if (exClass) {
            env->ThrowNew(exClass, message);
        }
    }

    LLMInference * getPtr(jlong handle) {
        return reinterpret_cast<LLMInference *>(handle);
    }
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;


extern "C" JNIEXPORT jlong JNICALL
Java_fun_walawe_memelm_MemeLM_loadModel(
        JNIEnv* env, jobject /* thiz */, jstring modelPath, jfloat minP,
        jfloat temperature, jboolean storeChats, jlong contextSize,
        jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock) {

    const char* modelPathCstr = env->GetStringUTFChars(modelPath, nullptr);
    const char* chatTemplateCstr = nullptr;
    if (chatTemplate != nullptr) {
        chatTemplateCstr = env->GetStringUTFChars(chatTemplate, nullptr);
    }

    try {
        auto* llmInference = new LLMInference();
        llmInference->loadModel(modelPathCstr, minP, temperature, storeChats == JNI_TRUE,
                                contextSize, chatTemplateCstr,
                                nThreads, useMmap == JNI_TRUE, useMlock == JNI_TRUE);
        if (chatTemplate != nullptr) {
            env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        }
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        return reinterpret_cast<jlong>(llmInference);
    } catch (const std::exception & ex) {
        if (chatTemplate != nullptr) {
            env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        }
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        throwJavaRuntime(env, ex.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_memelm_MemeLM_addChatMessage(
        JNIEnv* env, jobject /* thiz */, jlong modelPtr, jstring message, jstring role) {
    auto * llm = getPtr(modelPtr);
    if (!llm) {
        throwJavaRuntime(env, "Model not loaded");
        return;
    }

    const char* messageCstr = env->GetStringUTFChars(message, nullptr);
    const char* roleCstr = env->GetStringUTFChars(role, nullptr);
    llm->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_memelm_MemeLM_startCompletion(
        JNIEnv* env, jobject /* thiz */, jlong modelPtr, jstring prompt) {
    auto * llm = getPtr(modelPtr);
    if (!llm) {
        throwJavaRuntime(env, "Model not loaded");
        return;
    }

    const char* promptCstr = env->GetStringUTFChars(prompt, nullptr);
    try {
        llm->startCompletion(promptCstr);
    } catch (const std::exception & ex) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        throwJavaRuntime(env, ex.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_fun_walawe_memelm_MemeLM_completionLoop(
        JNIEnv* env, jobject /* thiz */, jlong modelPtr) {
    auto * llm = getPtr(modelPtr);
    if (!llm) {
        throwJavaRuntime(env, "Model not loaded");
        return env->NewStringUTF("[EOG]");
    }

    const std::string piece = llm->completionLoop();
    return env->NewStringUTF(piece.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_memelm_MemeLM_close(
        JNIEnv* env, jobject /* thiz */, jlong modelPtr) {
    auto * llm = getPtr(modelPtr);
    if (!llm) {
        return;
    }
    delete llm;
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_memelm_MemeLM_initVision(
        JNIEnv* env, jobject /* thiz */, jlong modelPtr, jstring mmprojPath,
        jstring mediaMarker, jint nThreads, jboolean useGpu, jboolean warmup) {
    auto * llm = getPtr(modelPtr);
    if (!llm) {
        throwJavaRuntime(env, "Model not loaded");
        return;
    }

    const char* mmprojCstr = env->GetStringUTFChars(mmprojPath, nullptr);
    const char* markerCstr = nullptr;
    if (mediaMarker != nullptr) {
        markerCstr = env->GetStringUTFChars(mediaMarker, nullptr);
    }

    try {
        llm->initVision(mmprojCstr, markerCstr, nThreads, useGpu == JNI_TRUE, warmup == JNI_TRUE);
    } catch (const std::exception & ex) {
        if (mediaMarker != nullptr) {
            env->ReleaseStringUTFChars(mediaMarker, markerCstr);
        }
        env->ReleaseStringUTFChars(mmprojPath, mmprojCstr);
        throwJavaRuntime(env, ex.what());
        return;
    }

    if (mediaMarker != nullptr) {
        env->ReleaseStringUTFChars(mediaMarker, markerCstr);
    }
    env->ReleaseStringUTFChars(mmprojPath, mmprojCstr);
}

extern "C" JNIEXPORT void JNICALL
Java_fun_walawe_memelm_MemeLM_startCompletionWithImage(
        JNIEnv* env, jobject /* thiz */, jlong modelPtr, jstring prompt, jbyteArray imageBytes) {
    auto * llm = getPtr(modelPtr);
    if (!llm) {
        throwJavaRuntime(env, "Model not loaded");
        return;
    }

    const char* promptCstr = env->GetStringUTFChars(prompt, nullptr);
    jbyte* bytes = env->GetByteArrayElements(imageBytes, nullptr);
    jsize len = env->GetArrayLength(imageBytes);

    try {
        llm->startCompletionWithImage(promptCstr, reinterpret_cast<unsigned char*>(bytes), static_cast<size_t>(len));
    } catch (const std::exception & ex) {
        env->ReleaseByteArrayElements(imageBytes, bytes, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, promptCstr);
        throwJavaRuntime(env, ex.what());
        return;
    }

    env->ReleaseByteArrayElements(imageBytes, bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(prompt, promptCstr);
}
