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

struct TokenCallback {
    JNIEnv*   env;
    jobject   obj;       // the Kotlin StreamCallback instance
    jmethodID onToken;   // cached method ID — looked up once, reused per token
};

class LLMInference {
public:
    bool init(const char* modelPath, const char* mmprojPath,
              const char* backendPath, int contextSize, bool useVulkan);

    std::string processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt, const TokenCallback* cb = nullptr);
    std::string processTextOnly(const char* prompt, const TokenCallback* cb = nullptr);

    std::string getBackendInfo();
    void setSystemPrompt(const std::string& sysPrompt);
    void cancelGeneration();
    bool isGenerating() const;

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
    llama_pos   m_n_past = 0;

    // How many tokens are reserved for the generated response.
    // Checked before each decode to prevent context overflow.
    static constexpr int RESPONSE_RESERVE = 512;

    std::atomic<bool> m_cancelFlag{false};
    std::string generateTokens(int max_new_tokens = 512, const TokenCallback* cb = nullptr);

    // Returns false if there is not enough context headroom to process
    // `n_new_tokens` more tokens. Caller should resetContext() or warn user.
    bool hasContextHeadroom(int n_new_tokens) const;

    // Helper to build prompt (adjusted for MiniCPM-V style + reasoning support)
    std::string buildPrompt(const std::string& systemPrompt, const std::string& userPrompt, bool forReasoning = false);
    std::string buildImagePrompt(mtmd_context* mtmd_ctx, const std::string& systemPrompt,
                                 const std::string& userPrompt, bool forReasoning = false);
};