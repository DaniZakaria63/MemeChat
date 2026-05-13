//
// Created by dani on 5/6/26.
//
#pragma once
#include <string>
#include <vector>
#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

class LLMInference {
public:
    bool init(const char* modelPath, const char* mmprojPath, const char* backendPath, int contextSize, bool useVulkan);
    std::string processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt);
    std::string processTextOnly(const char* prompt);
    std::string getBackendInfo();
    void release();
    void setSystemPrompt(const std::string& sysPrompt);
    void resetContext();

private:
    llama_model*    m_model = nullptr;
    llama_context*  m_ctx = nullptr;
    mtmd_context*   m_mtmd_ctx = nullptr;
    std::string     m_systemPrompt;
    bool            m_gpuUsed = false;

    llama_sampler*  m_smpl = nullptr;
    const llama_vocab* m_vocab = nullptr;

    std::vector<llama_token> tokenize(const std::string& text, bool add_special, bool parse_special);
    std::string generateTokens(int n_past, int max_new_tokens = 512);
};