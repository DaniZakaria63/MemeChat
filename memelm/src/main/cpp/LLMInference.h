//
// Created by dani on 5/6/26.
//

#pragma once
#include "chat.h"
#include "common.h"
#include "llama.h"
#include <string>
#include <vector>

class LLMInference {
    llama_context* _ctx = nullptr;
    llama_model* _model = nullptr;
    llama_sampler* _sampler = nullptr;
    const llama_vocab* _vocab = nullptr;
    std::vector<llama_chat_message> _messages;   // Chat history
    std::vector<char> _formattedMessages;        // Formatted prompt buffer
    std::vector<llama_token> _promptTokens;      // Tokenized prompt
    std::string _response;
    std::string _pendingUtf8;
    bool _storeChats = true;
    bool _isGenerating = false;
    bool _ownsChatTemplate = false;
    const char* _chatTemplate = nullptr;
    llama_pos _nPast = 0;

    struct mtmd_context* _mtmd = nullptr;
    std::string _mediaMarker;

public:
    void loadModel(const char* modelPath, float minP, float temperature,
                   bool storeChats, long contextSize, const char* chatTemplate,
                   int nThreads, bool useMmap, bool useMlock);
    void addChatMessage(const char* message, const char* role);
    void startCompletion(const char* query);
    void stopCompletion();
    void startCompletionWithImage(const char* query, const unsigned char* imageData, size_t imageSize);
    void initVision(const char* mmprojPath, const char* mediaMarker, int nThreads, bool useGpu, bool warmup);
    std::string completionLoop();
    ~LLMInference();

private:
    bool decodeTokens(const std::vector<llama_token>& tokens, bool logitsLast);
};