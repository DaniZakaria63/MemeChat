//
// Created by dani on 5/6/26.
//
#include <android/log.h>
#include <android/bitmap.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <vector>
#include <cstring>
#include <fstream>
#include <sys/stat.h>

#include "logging.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "llama.h"
#include "LLMInference.h"

using namespace std;

// Forward declarations
static bool      readFileHeader(const char* path, char* out, size_t len);
static long long getFileSize(const char* path);

static void llamaAndroidLogCallback(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGe("llama: %s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGw("llama: %s", text); break;
        default:                   LOGi("llama: %s", text); break;
    }
}

// Bitmap → RGB byte vector
static std::vector<uint8_t> bitmapToRGB(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGe("bitmapToRGB: failed to get info");
        return {};
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGe("bitmapToRGB: unsupported format %d", info.format);
        return {};
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGe("bitmapToRGB: failed to lock pixels");
        return {};
    }

    const int w      = static_cast<int>(info.width);
    const int h      = static_cast<int>(info.height);
    const int stride = static_cast<int>(info.stride);

    LOGi("bitmapToRGB: w=%d h=%d stride=%d (packed would be %d)", w, h, stride, w*4);

    std::vector<uint8_t> rgb(w * h * 3);
    const auto* src = static_cast<const uint8_t*>(pixels);

    for (int y = 0; y < h; y++) {
        const uint8_t* row = src + y * stride;
        for (int x = 0; x < w; x++) {
            rgb[(y * w + x) * 3 + 0] = row[x * 4 + 0]; // R
            rgb[(y * w + x) * 3 + 1] = row[x * 4 + 1]; // G
            rgb[(y * w + x) * 3 + 2] = row[x * 4 + 2]; // B
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return rgb;
}

//
/**
 *   MiniCPM chat template
 *   <|im_start|>system
 *   You are a helpful assistant<|im_end|>
 *   <|im_start|>user
 *   Hello<|im_end|>
 *   <|im_start|>assistant
 *   Hi there<|im_end|>
 *   <|im_start|>user
 *   How are you?<|im_end|>
 *   <|im_start|>assistant
 *   <think>
 */

static constexpr const char* TOK_IM_START   = "<|im_start|>";
static constexpr const char* TOK_IM_END     = "<|im_end|>";
static constexpr const char* TOK_SYSTEM     = "system\n";
static constexpr const char* TOK_USER       = "user\n";
static constexpr const char* TOK_ASSISTANT  = "assistant\n";
static constexpr const char* TOK_THINK_START  = "<think>";
static constexpr const char* TOK_THINK_END = "</think>";

std::string LLMInference::buildImagePrompt(mtmd_context* mtmd_ctx,
                               const string& systemPrompt,
                               const string& userPrompt,
                               bool forReasoning) {
    const char* marker = mtmd_default_marker();

    string p;
    if (!systemPrompt.empty()) {
        p += TOK_IM_START; p += TOK_SYSTEM;
        p += systemPrompt;
        p += TOK_IM_END;   p += "\n";
    }
    p += TOK_IM_START; p += TOK_USER; p += "\n";
    p += marker;
    p += "\n";
    p += userPrompt;
    p += TOK_IM_END;   p += "\n";
    p += TOK_IM_START; p += TOK_ASSISTANT;
    if (forReasoning) p += TOK_THINK_START;  p += "\n";
    return p;
}

string LLMInference::buildImageTurnPrompt(const string& userPrompt, bool forReasoning) {
    const char* marker = mtmd_default_marker();
    string p;
    p += TOK_IM_START; p += TOK_USER; p += "\n";
    p += marker;
    p += "\n";
    p += userPrompt;
    p += TOK_IM_END;   p += "\n";
    p += TOK_IM_START; p += TOK_ASSISTANT;
    if (forReasoning) p += TOK_THINK_START;  p += "\n";
    return p;
}

// Context headroom guard
bool LLMInference::hasContextHeadroom(int n_new_tokens) const {
    const int n_ctx  = llama_n_ctx(m_ctx);
    const int used   = static_cast<int>(m_n_past);
    const int needed = n_new_tokens + RESPONSE_RESERVE;
    if (used + needed > n_ctx) {
        LOGw("Context headroom check: used=%d needed=%d n_ctx=%d — overflow imminent",
             used, needed, n_ctx);
        return false;
    }
    return true;
}

static bool isCompleteUtf8(const std::string& s) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(s.data());
    int i = 0, len = static_cast<int>(s.size());
    while (i < len) {
        uint8_t b = bytes[i];
        int charLen;
        if      (b < 0x80)         charLen = 1;
        else if ((b & 0xE0) == 0xC0) charLen = 2;
        else if ((b & 0xF0) == 0xE0) charLen = 3;
        else if ((b & 0xF8) == 0xF0) charLen = 4;
        else return false; // invalid lead byte
        if (i + charLen > len) return false; // incomplete at end
        i += charLen;
    }
    return true;
}

// Token generation loop
// Reads from current m_n_past position and advances it as tokens are generated.
// m_n_past is a member — it persists across calls, keeping KV cache in sync.
string LLMInference::generateTokens(int max_new_tokens, const TokenCallback* cb) {
    string response;
    char   piece_buf[256];
    const  int n_ctx = llama_n_ctx(m_ctx);
    std::string utf8Carry;

    m_cancelFlag.store(false, std::memory_order_relaxed);

    for (int i = 0; i < max_new_tokens; i++) {
        if (m_cancelFlag.load(std::memory_order_relaxed)) {
            LOGi("Generation cancelled by user");
            break;
        }

        // Context overflow safety — stop generation before KV cache is full
        if (static_cast<int>(m_n_past) >= n_ctx - 4) {
            LOGw("generateTokens: approaching context limit (%d/%d), stopping", (int)m_n_past, n_ctx);
            break;
        }

        llama_token new_token = llama_sampler_sample(m_smpl, m_ctx, -1);

        // MiniCPM-V4.6 Token IDs: look @logging.h
        // Generic EOS fallback for any vocab
        if (llama_vocab_is_eog(m_vocab, new_token)) {
            LOGi("generateTokens: vocab EOG at step %d", i);
            break;
        }
        /* if (new_token == 2 || new_token == 73440) break; */
        int n_piece = llama_token_to_piece(m_vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece < 0) {
            LOGw("generateTokens: token_to_piece returned %d, skipping", n_piece);
            continue; // skip malformed piece, don't break — model may recover
        }
        string piece(piece_buf, n_piece);
        utf8Carry += piece;

        if (isCompleteUtf8(utf8Carry)) {
            response += utf8Carry;

            if (cb && cb->obj && cb->onToken) {
                jstring jpiece = cb->env->NewStringUTF(utf8Carry.c_str());
                cb->env->CallVoidMethod(cb->obj, cb->onToken, jpiece);
                cb->env->DeleteLocalRef(jpiece);
                if (cb->env->ExceptionCheck()) {
                    cb->env->ExceptionClear();
                    break;
                }
            }
            utf8Carry.clear();
        }

        // Decode the newly generated token into the KV cache at m_n_past position
        llama_batch batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(m_ctx, batch) != 0) {
            LOGe("generateTokens: llama_decode failed at step %d", i);
            break;
        }

        // Advance the member-level cursor. This keeps the KV cache
        // and n_past in sync across multiple calls.
        m_n_past++;
    }

    if (!utf8Carry.empty() && isCompleteUtf8(utf8Carry)) {
        response += utf8Carry;
        if (cb && cb->obj && cb->onToken) {
            jstring jpiece = cb->env->NewStringUTF(utf8Carry.c_str());
            cb->env->CallVoidMethod(cb->obj, cb->onToken, jpiece);
            cb->env->DeleteLocalRef(jpiece);
        }
    }

    // Reset sampler state (e.g. repetition penalty history) AFTER generation,
    // not before — resetting before would discard penalties for the current turn
    llama_sampler_reset(m_smpl);
    m_cancelFlag.store(false, std::memory_order_relaxed);

    return response;
}

// Initialization
bool LLMInference::init(const char* modelPath, const char* mmprojPath,
                        const char* backendPath, int contextSize, bool useVulkan) {

    // Register log callback first — without this all llama errors are invisible
    llama_log_set(llamaAndroidLogCallback, nullptr);
    LOGi("init: model=%s ctx=%d vulkan=%d", modelPath, contextSize, useVulkan ? 1 : 0);

    // Pre-flight file checks
    if (getFileSize(modelPath) <= 0) {
        LOGe("init: model file missing or unreadable: %s", modelPath);
        return false;
    }
    if (getFileSize(mmprojPath) <= 0) {
        LOGe("init: mmproj file missing or unreadable: %s", mmprojPath);
        return false;
    }
    char header[4] = {0};
    if (!readFileHeader(modelPath, header, 4) ||
        static_cast<uint8_t>(header[0]) != 0x47 ||
        static_cast<uint8_t>(header[1]) != 0x47 ||
        static_cast<uint8_t>(header[2]) != 0x55 ||
        static_cast<uint8_t>(header[3]) != 0x46) {
        LOGe("init: model is not a valid GGUF file");
        return false;
    }
    LOGi("init: pre-flight OK");

    // Backend init
    // ggml_backend_load_all() activates statically compiled backends (CPU etc.)
    // It does NOT need a path on Android when GGML_BACKEND_DL is OFF (default).
    ggml_backend_load_all();
    llama_backend_init();

    LOGi("init: backends loaded, registered count = %zu", ggml_backend_reg_count());

    // Model load
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap    = true;
    model_params.use_mlock   = false;
    // n_gpu_layers=0 forces CPU. Mali-G57 MC2 has limited Vulkan compute support.
    // Re-enable only after confirming ggml_vulkan works on this specific GPU.
    model_params.n_gpu_layers = 0;
    m_gpuUsed = false;

    m_model = llama_model_load_from_file(modelPath, model_params);
    if (!m_model) {
        LOGe("init: llama_model_load_from_file returned null");
        return false;
    }
    LOGi("init: model loaded OK");

    // Context init
    const int n_threads = std::max(2, std::min(4, (int)sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("init: using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = contextSize;
    ctx_params.n_batch         = 512;
    ctx_params.n_ubatch        = 512;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    m_ctx = llama_init_from_model(m_model, ctx_params);
    if (!m_ctx) {
        LOGe("init: llama_init_from_model returned null");
        llama_model_free(m_model);
        m_model = nullptr;
        return false;
    }

    m_vocab   = llama_model_get_vocab(m_model);
    m_n_past  = 0; // explicit reset on fresh init

    // Multimodal projector init
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu       = false; // matches model_params.n_gpu_layers = 0
    mtmd_params.n_threads     = n_threads;
    mtmd_params.print_timings = false;

    m_mtmd_ctx = mtmd_init_from_file(mmprojPath, m_model, mtmd_params);
    if (!m_mtmd_ctx) {
        LOGe("init: mtmd_init_from_file returned null");
        llama_free(m_ctx);
        llama_model_free(m_model);
        m_ctx   = nullptr;
        m_model = nullptr;
        return false;
    }
    LOGi("init: mtmd loaded OK");

    // Sampler init
    auto sampler_params       = llama_sampler_chain_default_params();
    sampler_params.no_perf    = true;
    m_smpl                    = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(m_smpl, llama_sampler_init_greedy());

    LOGi("init: complete. n_ctx=%d threads=%d", contextSize, n_threads);
    return true;
}

// Image + Text inference with KV cache persistence
std::string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt,
                                               bool resetFirst, bool forReasoning,
                                               const TokenCallback* cb) {
    if (!m_mtmd_ctx || !m_ctx) {
        LOGe("processImageAndText: engine not initialized");
        return "";
    }

    if (resetFirst) {
        resetContext();
        LOGi("processImageAndText: context reset for new conversation");
    }

    std::vector<uint8_t> rgb = bitmapToRGB(env, bitmap);
    if (rgb.empty()) return "";

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);

    mtmd_bitmap* bmp = mtmd_bitmap_init(
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            rgb.data()
    );
    if (!bmp) {
        LOGe("processImageAndText: mtmd_bitmap_init failed");
        return "";
    }

    // C++ builds the prompt because only it can call mtmd_default_marker()
    const string full_prompt = resetFirst
        ? buildImagePrompt(m_mtmd_ctx, m_systemPrompt, prompt, forReasoning)
        : buildImageTurnPrompt(prompt, forReasoning);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks*    chunks     = mtmd_input_chunks_init();
    const mtmd_bitmap*    bitmaps[]  = { bmp };
    const int             res        = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bmp);

    if (res != 0) {
        LOGe("processImageAndText: mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (!hasContextHeadroom(512)) {
        LOGw("processImageAndText: not enough context");
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, m_n_past, 0, 512, true, &m_n_past) != 0) {
        LOGe("processImageAndText: mtmd_helper_eval_chunks failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    LOGi("processImageAndText: evaluated, n_past=%d, generating...", (int)m_n_past);
    return generateTokens(512, cb);
}

std::string LLMInference::processConversation(const char* chatML, bool resetFirst, const TokenCallback* cb) {
    if (!m_ctx) {
        LOGe("processConversation: engine not initialized");
        return "";
    }

    if (resetFirst) {
        resetContext();
        LOGi("processConversation: context reset for new conversation");
    }

    string full_prompt(chatML);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    int res = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, nullptr, 0);
    if (res != 0) {
        LOGe("processConversation: mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (!hasContextHeadroom(256)) {
        LOGw("processConversation: not enough context");
        mtmd_input_chunks_free(chunks);
        return "";
    }

    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, m_n_past, 0, 512, true, &m_n_past) != 0) {
        LOGe("processConversation: mtmd_helper_eval_chunks failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    LOGi("processConversation: evaluated, n_past=%d, generating...", (int)m_n_past);
    return generateTokens(512, cb);
}

// ── Context reset ─────────────
void LLMInference::resetContext() {
    if (!m_ctx) {
        LOGw("resetContext: context is null, nothing to reset");
        return;
    }

    // llama_kv_cache_seq_rm was removed in newer llama.cpp.
    // Correct modern API: get the memory handle first, then call seq_rm on it.
    llama_memory_t mem = llama_get_memory(m_ctx);
    llama_memory_seq_rm(mem, 0, 0, -1);

    // Reset n_past cursor to match the now-empty KV cache
    m_n_past = 0;

    if (m_smpl) {
        llama_sampler_reset(m_smpl);
    }

    LOGi("resetContext: KV cache cleared, n_past reset to 0");
}

// ── Accessors
std::string LLMInference::getBackendInfo() {
    return m_gpuUsed ? "GPU (Vulkan) ON" : "CPU only";
}

void LLMInference::setSystemPrompt(const std::string& sysPrompt) {
    m_systemPrompt = sysPrompt;
}

void LLMInference::cancelGeneration() {
    m_cancelFlag.store(true, std::memory_order_relaxed);
    LOGi("Generation cancellation requested");
}

bool LLMInference::isGenerating() const {
    return m_cancelFlag.load(std::memory_order_relaxed);
}

// ── Cleanup ──
void LLMInference::release() {
    if (m_smpl)     { llama_sampler_free(m_smpl);  m_smpl     = nullptr; }
    if (m_mtmd_ctx) { mtmd_free(m_mtmd_ctx);       m_mtmd_ctx = nullptr; }
    if (m_ctx)      { llama_free(m_ctx);            m_ctx      = nullptr; }
    if (m_model)    { llama_model_free(m_model);   m_model    = nullptr; }
    llama_backend_free();
    m_n_past = 0;
    LOGi("release: all resources freed");
}

// ── File utilities ────────────
static bool readFileHeader(const char* path, char* out, size_t len) {
    if (!path || !out || len == 0) return false;
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;
    file.read(out, static_cast<std::streamsize>(len));
    return static_cast<size_t>(file.gcount()) == len;
}

static long long getFileSize(const char* path) {
    if (!path || path[0] == '\0') return -1;
    struct stat st{};
    return (stat(path, &st) == 0) ? static_cast<long long>(st.st_size) : -1;
}