//
// Created by dani on 5/6/26.
//
#pragma once
#include <string>
#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

class LLMInference {
public:
    // Returns true if model loaded successfully
    bool init(const char* modelPath, const char* mmprojPath, int contextSize, bool useVulkan);

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
    llama_model*    m_model = nullptr;
    llama_context*  m_ctx = nullptr;
    mtmd_context*   m_mtmd_ctx = nullptr;   // new
    std::string     m_systemPrompt;
    bool            m_gpuUsed = false;
    int             m_gpuLayers = 0;

    std::string generateTokens(int max_new_tokens = 512);
};