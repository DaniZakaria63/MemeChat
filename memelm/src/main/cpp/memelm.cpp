#include <jni.h>
#include <string>
#include "LLMInference.h"
#include "logging.h"

static LLMInference g_inference;

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
} // extern "C"