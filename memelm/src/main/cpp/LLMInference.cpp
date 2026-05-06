//
// Created by dani on 5/6/26.
//

#include "LLMInference.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <android/log.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <stdexcept>

void LLMInference::loadModel(const char *model_path, float minP, float temperature,
                             bool storeChats, long contextSize, const char *chatTemplate, int nThreads,
                             bool useMmap, bool useMlock) {

    // 1. Initialize backends
    ggml_backend_load_all();

    // 2. Load model with memory-mapping options
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = useMmap;
    model_params.use_mlock = useMlock;
    _model = llama_model_load_from_file(model_path, model_params);
    if (!_model) throw std::runtime_error("Failed to load model");

    // 3. Create context (this is where you'd allocate vision space for VLMs)
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = contextSize;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) throw std::runtime_error("Failed to create context");

    _vocab = llama_model_get_vocab(_model);

    // 4. Set up sampler
    _sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    this->_storeChats = storeChats;
    if (chatTemplate != nullptr && std::strlen(chatTemplate) > 0) {
        this->_chatTemplate = strdup(chatTemplate);
        this->_ownsChatTemplate = true;
    } else {
        this->_chatTemplate = llama_model_chat_template(_model, nullptr);
        this->_ownsChatTemplate = false;
    }
}

void LLMInference::addChatMessage(const char *message, const char *role) {
    _messages.push_back({strdup(role), strdup(message)});
}

bool LLMInference::decodeTokens(const std::vector<llama_token>& tokens, bool logitsLast) {
    const int32_t n_tokens = static_cast<int32_t>(tokens.size());
    if (n_tokens == 0) {
        return true;
    }

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(_ctx));
    int32_t idx = 0;
    llama_seq_id seq_id = 0;

    while (idx < n_tokens) {
        const int32_t n = std::min(n_batch, n_tokens - idx);
        llama_batch batch = llama_batch_init(n, 0, 1);
        for (int32_t i = 0; i < n; i++) {
            batch.token[i] = tokens[idx + i];
            batch.pos[i] = _nPast + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i] = &seq_id;
            batch.logits[i] = logitsLast && (idx + i == n_tokens - 1);
        }
        const int decode_status = llama_decode(_ctx, batch);
        llama_batch_free(batch);
        if (decode_status != 0) {
            return false;
        }
        _nPast += n;
        idx += n;
    }

    return true;
}

void LLMInference::startCompletion(const char *query) {
    if (!_ctx || !_model) {
        throw std::runtime_error("Model not loaded");
    }

    _response.clear();
    _promptTokens.clear();
    _formattedMessages.clear();

    if (!_storeChats) {
        for (auto & msg : _messages) {
            free(const_cast<char *>(msg.content));
            free(const_cast<char *>(msg.role));
        }
        _messages.clear();
    }

    _messages.push_back({strdup("user"), strdup(query)});

    const char * tmpl = _chatTemplate ? _chatTemplate : "";
    _formattedMessages.resize(llama_n_ctx(_ctx));
    int32_t new_len = llama_chat_apply_template(
        tmpl, _messages.data(), _messages.size(), true,
        _formattedMessages.data(), static_cast<int32_t>(_formattedMessages.size()));
    if (new_len > static_cast<int32_t>(_formattedMessages.size())) {
        _formattedMessages.resize(new_len);
        new_len = llama_chat_apply_template(
            tmpl, _messages.data(), _messages.size(), true,
            _formattedMessages.data(), static_cast<int32_t>(_formattedMessages.size()));
    }
    if (new_len < 0) {
        throw std::runtime_error("Failed to apply chat template");
    }

    std::string prompt(_formattedMessages.begin(), _formattedMessages.begin() + new_len);

    _promptTokens.resize(prompt.size() + 8);
    int32_t n_tokens = llama_tokenize(
        _vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        _promptTokens.data(), static_cast<int32_t>(_promptTokens.size()), true, true);
    if (n_tokens < 0) {
        _promptTokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            _vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            _promptTokens.data(), static_cast<int32_t>(_promptTokens.size()), true, true);
    }
    if (n_tokens <= 0) {
        throw std::runtime_error("Tokenization failed");
    }
    _promptTokens.resize(n_tokens);

    llama_memory_clear(llama_get_memory(_ctx), true);
    _nPast = 0;
    if (!decodeTokens(_promptTokens, true)) {
        throw std::runtime_error("Prompt decode failed");
    }

    _isGenerating = true;
}

void LLMInference::initVision(const char* mmprojPath, const char* mediaMarker, int nThreads, bool useGpu, bool warmup) {
    if (!_model) {
        throw std::runtime_error("Text model not loaded");
    }

    if (_mtmd) {
        mtmd_free(_mtmd);
        _mtmd = nullptr;
    }

    mtmd_context_params params = mtmd_context_params_default();
    params.n_threads = nThreads;
    params.use_gpu = useGpu;
    params.warmup = warmup;
    if (mediaMarker != nullptr && std::strlen(mediaMarker) > 0) {
        params.media_marker = mediaMarker;
        _mediaMarker = mediaMarker;
    } else {
        _mediaMarker = mtmd_default_marker();
    }

    _mtmd = mtmd_init_from_file(mmprojPath, _model, params);
    if (!_mtmd) {
        throw std::runtime_error("Failed to load mmproj model");
    }
}

void LLMInference::startCompletionWithImage(const char* query, const unsigned char* imageData, size_t imageSize) {
    if (!_ctx || !_model) {
        throw std::runtime_error("Model not loaded");
    }
    if (!_mtmd) {
        throw std::runtime_error("Vision model not loaded");
    }

    _response.clear();
    _promptTokens.clear();
    _formattedMessages.clear();

    if (!_storeChats) {
        for (auto & msg : _messages) {
            free(const_cast<char *>(msg.content));
            free(const_cast<char *>(msg.role));
        }
        _messages.clear();
    }

    std::string content(query ? query : "");
    const std::string marker = _mediaMarker.empty() ? mtmd_default_marker() : _mediaMarker;
    if (content.find(marker) == std::string::npos) {
        content = marker + content;
    }

    _messages.push_back({strdup("user"), strdup(content.c_str())});

    const char * tmpl = _chatTemplate ? _chatTemplate : "";
    _formattedMessages.resize(llama_n_ctx(_ctx));
    int32_t new_len = llama_chat_apply_template(
        tmpl, _messages.data(), _messages.size(), true,
        _formattedMessages.data(), static_cast<int32_t>(_formattedMessages.size()));
    if (new_len > static_cast<int32_t>(_formattedMessages.size())) {
        _formattedMessages.resize(new_len);
        new_len = llama_chat_apply_template(
            tmpl, _messages.data(), _messages.size(), true,
            _formattedMessages.data(), static_cast<int32_t>(_formattedMessages.size()));
    }
    if (new_len < 0) {
        throw std::runtime_error("Failed to apply chat template");
    }

    std::string prompt(_formattedMessages.begin(), _formattedMessages.begin() + new_len);

    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(_mtmd, imageData, imageSize);
    if (!bitmap) {
        throw std::runtime_error("Failed to decode image buffer");
    }

    mtmd_input_text text;
    text.text = prompt.c_str();
    text.add_special = _messages.size() == 1;
    text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { bitmap };
    int32_t tok_res = mtmd_tokenize(_mtmd, chunks, &text, bitmaps, 1);
    if (tok_res != 0) {
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        throw std::runtime_error("Unable to tokenize multimodal prompt");
    }

    llama_memory_clear(llama_get_memory(_ctx), true);
    _nPast = 0;
    llama_pos new_n_past = 0;
    const int32_t eval_res = mtmd_helper_eval_chunks(
        _mtmd, _ctx, chunks, _nPast, 0, static_cast<int32_t>(llama_n_batch(_ctx)), true, &new_n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);

    if (eval_res != 0) {
        throw std::runtime_error("Unable to eval multimodal prompt");
    }

    _nPast = new_n_past;
    _isGenerating = true;
}

std::string LLMInference::completionLoop() {
    if (!_isGenerating) {
        return "[EOG]";
    }

    const llama_token new_token_id = llama_sampler_sample(_sampler, _ctx, -1);
    if (llama_vocab_is_eog(_vocab, new_token_id)) {
        _isGenerating = false;
        if (_storeChats) {
            _messages.push_back({strdup("assistant"), strdup(_response.c_str())});
        }
        return "[EOG]";
    }

    char buf[256];
    const int n = llama_token_to_piece(_vocab, new_token_id, buf, sizeof(buf), 0, true);
    if (n < 0) {
        _isGenerating = false;
        return "[EOG]";
    }

    std::string piece(buf, n);
    _response += piece;

    std::vector<llama_token> next_token = { new_token_id };
    if (!decodeTokens(next_token, true)) {
        _isGenerating = false;
        return "[EOG]";
    }

    return piece;
}

void LLMInference::stopCompletion() {
    _isGenerating = false;
}

LLMInference::~LLMInference() {
    stopCompletion();
    for (auto & msg : _messages) {
        free(const_cast<char *>(msg.content));
        free(const_cast<char *>(msg.role));
    }
    if (_ownsChatTemplate && _chatTemplate) {
        free(const_cast<char *>(_chatTemplate));
        _chatTemplate = nullptr;
    }
    if (_mtmd) {
        mtmd_free(_mtmd);
        _mtmd = nullptr;
    }
    if (_sampler) {
        llama_sampler_free(_sampler);
        _sampler = nullptr;
    }
    if (_ctx) {
        llama_free(_ctx);
        _ctx = nullptr;
    }
    if (_model) {
        llama_model_free(_model);
        _model = nullptr;
    }
}