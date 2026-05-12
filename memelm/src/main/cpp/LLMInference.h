//
// Created by dani on 5/6/26.
//
#pragma once
#include <string>
#include <jni.h>     // for AndroidBitmapInfo etc (we'll pass processed image data)

class LLMInference {
public:
    // Returns true if model loaded successfully
    bool init(const char* modelPath, int contextSize, bool useVulkan);

    // Process an image (Android Bitmap) + text prompt
    std::string processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt);

    // Text‑only prompt (no image)
    std::string processTextOnly(const char* prompt);

    // Set system prompt globally
    void setSystemPrompt(const std::string& sysPrompt);
    // Returns backend info string
    std::string getBackendInfo();

    // Release all resources
    void release();

private:
    std::string m_systemPrompt;
};