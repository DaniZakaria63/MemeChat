//
// Created by dani on 5/6/26.
// Revised: fixed persistent n_past, context overflow guard, KV cache management
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
    bool init(const char* modelPath, const char* mmprojPath,
              const char* backendPath, int contextSize, bool useVulkan);

    std::string processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt);
    std::string processTextOnly(const char* prompt);

    std::string getBackendInfo();
    void setSystemPrompt(const std::string& sysPrompt);

    // Resets the full conversation: clears KV cache AND n_past cursor.
    // Call this between independent conversations.
    void resetContext();

    void release();

private:
    llama_model*       m_model    = nullptr;
    llama_context*     m_ctx      = nullptr;
    mtmd_context*      m_mtmd_ctx = nullptr;
    llama_sampler*     m_smpl     = nullptr;
    const llama_vocab* m_vocab    = nullptr;

    std::string m_systemPrompt;
    bool        m_gpuUsed = false;

    // THE critical fix: n_past must persist across calls so the KV cache
    // cursor stays in sync with what is already written in the cache.
    llama_pos m_n_past = 0;

    // How many tokens are reserved for the generated response.
    // Checked before each decode to prevent context overflow.
    static constexpr int RESPONSE_RESERVE = 512;

    std::string generateTokens(int max_new_tokens = 512);

    // Returns false if there is not enough context headroom to process
    // `n_new_tokens` more tokens. Caller should resetContext() or warn user.
    bool hasContextHeadroom(int n_new_tokens) const;
};