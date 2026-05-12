#include <jni.h>
#include <string>
#include "LLMInference.h"

static LLMInference g_inference;

extern "C" {

JNIEXPORT jboolean
JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeInit(
        JNIEnv* env, jobject /* this */,
        jstring modelPath, jstring mmprojPath, jint contextSize, jboolean useVulkan) {

    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    const char* mmproj = env->GetStringUTFChars(mmprojPath, nullptr);
    bool ok = g_inference.init(model, mmproj, contextSize, useVulkan);
    env->ReleaseStringUTFChars(modelPath, model);
    env->ReleaseStringUTFChars(mmprojPath, mmproj);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring
JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessImageAndText(
        JNIEnv *env, jobject /* this */,
        jobject bitmap, jstring prompt) {

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string result = g_inference.processImageAndText(env, bitmap, promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring
JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeProcessTextOnly(
        JNIEnv *env, jobject /* this */,
        jstring prompt) {

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string result = g_inference.processTextOnly(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring
JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeGetBackendInfo(
        JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(g_inference.getBackendInfo().c_str());
}

JNIEXPORT void
JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeRelease(
        JNIEnv * /* env */, jobject /* this */) {
    g_inference.release();
}

JNIEXPORT void
JNICALL
Java_fun_walawe_memelm_inference_InferenceEngineImpl_nativeSetSystemPrompt(
        JNIEnv *env, jobject /* this */, jstring prompt) {
    const char *str = env->GetStringUTFChars(prompt, nullptr);
    g_inference.setSystemPrompt(str);
    env->ReleaseStringUTFChars(prompt, str);
}
} // extern "C"