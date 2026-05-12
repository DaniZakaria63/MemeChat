//
// Created by dani on 5/6/26.
//

#include "LLMInference.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace {
    constexpr const char* LOG_TAG = "memelm";

    constexpr int N_THREADS_MIN = 2;
    constexpr int N_THREADS_MAX = 4;
    constexpr int N_THREADS_HEADROOM = 2;

    constexpr int DEFAULT_CONTEXT_SIZE = 8192;
    constexpr int MAX_CONTEXT_SIZE = 4096;
    constexpr int OVERFLOW_HEADROOM = 4;
    constexpr int BATCH_SIZE = 128;

    int resolve_thread_count(int requested) {
        if (requested > 0) {
            return requested;
        }
        long cores = sysconf(_SC_NPROCESSORS_ONLN);
        if (cores <= 0) {
            return N_THREADS_MIN;
        }
        int threads = static_cast<int>(cores) - N_THREADS_HEADROOM;
        if (threads < N_THREADS_MIN) {
            threads = N_THREADS_MIN;
        }
        return std::min(threads, N_THREADS_MAX);
    }

    bool is_valid_utf8(const std::string & s) {
        const unsigned char* bytes = reinterpret_cast<const unsigned char*>(s.c_str());
        size_t len = s.size();
        size_t i = 0;
        while (i < len) {
            if (bytes[i] <= 0x7F) {
                i += 1;
            } else if ((bytes[i] & 0xE0) == 0xC0) {
                if (i + 1 >= len || (bytes[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if ((bytes[i] & 0xF0) == 0xE0) {
                if (i + 2 >= len || (bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80) return false;
                i += 3;
            } else if ((bytes[i] & 0xF8) == 0xF0) {
                if (i + 3 >= len || (bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80 || (bytes[i + 3] & 0xC0) != 0x80) return false;
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    void llama_log_callback(ggml_log_level level, const char* text, void* /* user_data */) {
        if (!text) {
            return;
        }
        android_LogPriority prio = ANDROID_LOG_INFO;
        switch (level) {
            case GGML_LOG_LEVEL_ERROR:
                prio = ANDROID_LOG_ERROR;
                break;
            case GGML_LOG_LEVEL_WARN:
                prio = ANDROID_LOG_WARN;
                break;
            case GGML_LOG_LEVEL_INFO:
                prio = ANDROID_LOG_INFO;
                break;
            case GGML_LOG_LEVEL_DEBUG:
                prio = ANDROID_LOG_DEBUG;
                break;
            default:
                prio = ANDROID_LOG_INFO;
                break;
        }
        __android_log_print(prio, LOG_TAG, "%s", text);
    }

    void log_file_stats(const char* path) {
        if (!path) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Model path is null");
            return;
        }
        struct stat st = {};
        if (stat(path, &st) != 0) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                LOG_TAG,
                "stat failed for %s errno=%d (%s)",
                path,
                errno,
                strerror(errno)
            );
            return;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Model file stats: size=%lld bytes, mode=%o",
            static_cast<long long>(st.st_size),
            st.st_mode
        );
    }
}

void LLMInference::loadModel(const char *model_path, float minP, float temperature,
                             bool storeChats, long contextSize, const char *chatTemplate, int nThreads,
                             bool useMmap, bool useMlock) {

    static bool log_set = false;
    if (!log_set) {
        llama_log_set(llama_log_callback, nullptr);
        log_set = true;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "loadModel: %s", model_path ? model_path : "<null>");
    log_file_stats(model_path);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "loadModel mmap=%d mlock=%d", useMmap ? 1 : 0, useMlock ? 1 : 0);

    // 1. Initialize backends
    ggml_backend_load_all();

    // 2. Load model with memory-mapping options
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = useMmap;
    model_params.use_mlock = useMlock;
    _model = llama_model_load_from_file(model_path, model_params);
    if (!_model) {
        throw std::runtime_error("Failed to load model");
    }

    const int requested_ctx = contextSize > 0 ? static_cast<int>(contextSize) : 0;
    const int trained_ctx = llama_model_n_ctx_train(_model);
    int ctx_size = requested_ctx > 0 ? requested_ctx : (trained_ctx > 0 ? trained_ctx : DEFAULT_CONTEXT_SIZE);
    if (ctx_size > MAX_CONTEXT_SIZE) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Capping context: requested=%d cap=%d", ctx_size, MAX_CONTEXT_SIZE);
        ctx_size = MAX_CONTEXT_SIZE;
    }

    const int threads = resolve_thread_count(nThreads);

    // 3. Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(ctx_size);
    ctx_params.n_batch = std::max<uint32_t>(1, std::min<uint32_t>(BATCH_SIZE, ctx_params.n_ctx));
    ctx_params.n_ubatch = std::max<uint32_t>(1, std::min<uint32_t>(ctx_params.n_batch, 128));
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) throw std::runtime_error("Failed to create context");

    _vocab = llama_model_get_vocab(_model);

    // 4. Set up sampler
    _sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    _storeChats = storeChats;
    _response.clear();
    _pendingUtf8.clear();
    _isGenerating = false;
    _nPast = 0;

    if (chatTemplate != nullptr && std::strlen(chatTemplate) > 0) {
        _chatTemplate = strdup(chatTemplate);
        _ownsChatTemplate = true;
    } else {
        _chatTemplate = llama_model_chat_template(_model, nullptr);
        _ownsChatTemplate = false;
    }
}

void LLMInference::addChatMessage(const char *message, const char *role) {
    _messages.push_back({strdup(role), strdup(message)});
}

void LLMInference::clearBatch() {
    if (_batchInitialized) {
        llama_batch_free(_batch);
        _batchInitialized = false;
    }
    _batch = {};
}

void LLMInference::resetBatch(size_t nTokens) {
    clearBatch();
    if (nTokens == 0) {
        return;
    }
    _batch = llama_batch_init(static_cast<int32_t>(nTokens), 0, 1);
    _batchInitialized = true;
}

bool LLMInference::decodeTokens(const std::vector<llama_token>& tokens, bool logitsLast) {
    const int32_t n_tokens = static_cast<int32_t>(tokens.size());
    if (n_tokens == 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "decodeTokens: empty token list");
        return true;
    }

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(_ctx));
    if (n_batch <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "decodeTokens: invalid n_batch=%d", n_batch);
        return false;
    }
    int32_t idx = 0;
    llama_seq_id seq_id = 0;

    while (idx < n_tokens) {
        const int32_t n = std::min(n_batch, n_tokens - idx);
        llama_batch batch = llama_batch_init(n, 0, 1);
        batch.n_tokens = n;
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
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "startCompletion");

    if (!_ctx || !_model) {
        throw std::runtime_error("Model not loaded");
    }
    if (!_sampler) {
        throw std::runtime_error("Sampler not initialized");
    }

    _response.clear();
    _cacheResponseTokens.clear();
    _pendingUtf8.clear();
    _promptTokens.clear();
    _formattedMessages.clear();
    llama_sampler_reset(_sampler);

    if (!_storeChats) {
        for (auto & msg : _messages) {
            free(const_cast<char *>(msg.content));
            free(const_cast<char *>(msg.role));
        }
        _messages.clear();
    }

    _messages.push_back({strdup("user"), strdup(query ? query : "")});

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
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Tokenization failed: n_tokens=%d", n_tokens);
        throw std::runtime_error("Tokenization failed");
    }
    _promptTokens.resize(n_tokens);

    const int32_t max_tokens = static_cast<int32_t>(llama_n_ctx(_ctx)) - OVERFLOW_HEADROOM;
    if (static_cast<int32_t>(_promptTokens.size()) > max_tokens) {
        const int32_t skip = static_cast<int32_t>(_promptTokens.size()) - max_tokens;
        _promptTokens.erase(_promptTokens.begin(), _promptTokens.begin() + skip);
    }

    llama_memory_clear(llama_get_memory(_ctx), true);
    _nPast = 0;

    resetBatch(_promptTokens.size());
    if (!_batchInitialized) {
        throw std::runtime_error("Batch initialization failed");
    }

    for (size_t i = 0; i < _promptTokens.size(); ++i) {
        _batch.token[i] = _promptTokens[i];
        _batch.pos[i] = _nPast + static_cast<llama_pos>(i);
        _batch.n_seq_id[i] = 1;
        _batch.seq_id[i] = &_seq_id;
        _batch.logits[i] = (i == _promptTokens.size() - 1);
    }
    _batch.n_tokens = static_cast<int32_t>(_promptTokens.size());

    if (llama_decode(_ctx, _batch) != 0) {
        throw std::runtime_error("Prompt decode failed");
    }

    _nPast += static_cast<llama_pos>(_promptTokens.size());
    _isGenerating = true;
}

void LLMInference::initVision(const char* mmprojPath, const char* mediaMarker, int nThreads, bool useGpu, bool warmup) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "initVision: %s", mmprojPath ? mmprojPath : "<null>");

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
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "startCompletionWithImage: %zu bytes", imageSize);

    if (!_ctx || !_model) {
        throw std::runtime_error("Model not loaded");
    }
    if (!_mtmd) {
        throw std::runtime_error("Vision model not loaded");
    }
    if (!_sampler) {
        throw std::runtime_error("Sampler not initialized");
    }

    _response.clear();
    _pendingUtf8.clear();
    _promptTokens.clear();
    _formattedMessages.clear();
    llama_sampler_reset(_sampler);

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

    if (_nPast >= static_cast<llama_pos>(llama_n_ctx(_ctx)) - OVERFLOW_HEADROOM) {
        _isGenerating = false;
        return "[EOG]";
    }

    _currToken = llama_sampler_sample(_sampler, _ctx, -1);
    llama_sampler_accept(_sampler, _currToken);
    if (llama_vocab_is_eog(_vocab, _currToken)) {
        _isGenerating = false;
        if (_storeChats) {
            _messages.push_back({strdup("assistant"), strdup(_response.c_str())});
        }
        return "[EOG]";
    }

    clearBatch();
    resetBatch(1);
    if (!_batchInitialized) {
        _isGenerating = false;
        return "[EOG]";
    }

    _batch.token[0] = _currToken;
    _batch.pos[0] = _nPast;
    _batch.n_seq_id[0] = 1;
    _batch.seq_id[0] = &_seq_id;
    _batch.logits[0] = true;
    _batch.n_tokens = 1;

    if (llama_decode(_ctx, _batch) != 0) {
        _isGenerating = false;
        return "[EOG]";
    }
    _nPast += 1;

    char stack_buf[256];
    int32_t n = llama_token_to_piece(_vocab, _currToken, stack_buf, sizeof(stack_buf), 0, true);
    std::string piece;
    if (n < 0) {
        std::vector<char> heap_buf(static_cast<size_t>(-n));
        n = llama_token_to_piece(_vocab, _currToken, heap_buf.data(), static_cast<int32_t>(heap_buf.size()), 0, true);
        if (n < 0) {
            _isGenerating = false;
            return "[EOG]";
        }
        piece.assign(heap_buf.data(), static_cast<size_t>(n));
    } else {
        piece.assign(stack_buf, static_cast<size_t>(n));
    }

    _pendingUtf8 += piece;
    if (!is_valid_utf8(_pendingUtf8)) {
        return "";
    }

    std::string ready = _pendingUtf8;
    _pendingUtf8.clear();
    _response += ready;
    return ready;
}

void LLMInference::stopCompletion() {
    _isGenerating = false;
}

LLMInference::~LLMInference() {
    stopCompletion();
    clearBatch();
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

