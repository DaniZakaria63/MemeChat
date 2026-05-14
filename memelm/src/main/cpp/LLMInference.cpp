//
// Created by dani on 5/6/26.
// Revised: persistent n_past, context overflow guard, KV cache seq_id fix,
//          conversation history, safe bitmap handling, sampler reset timing.
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

// ── Forward declarations ──────────────────────────────────────────────────
static bool      readFileHeader(const char* path, char* out, size_t len);
static long long getFileSize(const char* path);

// ── llama.cpp log → Android logcat ───────────────────────────────────────
static void llamaAndroidLogCallback(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGe("llama: %s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGw("llama: %s", text); break;
        default:                   LOGi("llama: %s", text); break;
    }
}

// ── Bitmap → RGB byte vector ──────────────────────────────────────────────
static std::vector<uint8_t> bitmapToRGB(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGe("bitmapToRGB: failed to get bitmap info");
        return {};
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGe("bitmapToRGB: only RGBA_8888 is supported, got format %d", info.format);
        return {};
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGe("bitmapToRGB: failed to lock pixels");
        return {};
    }
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    std::vector<uint8_t> rgb(w * h * 3);
    const auto* src = static_cast<const uint8_t*>(pixels);
    for (int i = 0; i < w * h; i++) {
        rgb[i * 3 + 0] = src[i * 4 + 0]; // R
        rgb[i * 3 + 1] = src[i * 4 + 1]; // G
        rgb[i * 3 + 2] = src[i * 4 + 2]; // B
        // Alpha (i*4+3) is intentionally dropped — mtmd wants RGB only
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return rgb;
}

// ── Qwen2-VL chat template tokens ────────────────────────────────────────
static constexpr const char* TOK_IM_START  = "<|im_start|>";
static constexpr const char* TOK_IM_END    = "<|im_end|>";
static constexpr const char* TOK_SYSTEM    = "system\n";
static constexpr const char* TOK_USER      = "user\n";
static constexpr const char* TOK_ASSISTANT = "assistant\n";

// Builds the full chat-formatted prompt string.
static string buildPrompt(const string& systemPrompt, const string& userPrompt) {
    string p;
    if (!systemPrompt.empty()) {
        p += TOK_IM_START; p += TOK_SYSTEM;
        p += systemPrompt;
        p += TOK_IM_END;   p += "\n";
    }
    p += TOK_IM_START; p += TOK_USER;
    p += userPrompt;
    p += TOK_IM_END;   p += "\n";
    p += TOK_IM_START; p += TOK_ASSISTANT;
    return p;
}

static string buildImagePrompt(mtmd_context* mtmd_ctx,
                               const string& systemPrompt,
                               const string& userPrompt) {
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
    return p;
}

// ── Context headroom guard ────────────────────────────────────────────────
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

// ── Token generation loop ─────────────────────────────────────────────────
// Reads from current m_n_past position and advances it as tokens are generated.
// m_n_past is a member — it persists across calls, keeping KV cache in sync.
string LLMInference::generateTokens(int max_new_tokens) {
    string response;
    char   piece_buf[256];
    const  int n_ctx = llama_n_ctx(m_ctx);

    for (int i = 0; i < max_new_tokens; i++) {

        // Context overflow safety — stop generation before KV cache is full
        if (static_cast<int>(m_n_past) >= n_ctx - 4) {
            LOGw("generateTokens: approaching context limit (%d/%d), stopping", (int)m_n_past, n_ctx);
            break;
        }

        llama_token new_token = llama_sampler_sample(m_smpl, m_ctx, -1);

        // Qwen2-VL EOG token IDs:
        // 151645 = <|im_end|>   (primary stop)
        // 151643 = <|endoftext|>
        // 151644 = <|im_start|> (safety: should not appear in output)
        if (new_token == 151645 || new_token == 151643 || new_token == 151644) {
            LOGi("generateTokens: EOG token %d at step %d", new_token, i);
            break;
        }
        // Generic EOS fallback for any vocab
        if (llama_vocab_is_eog(m_vocab, new_token)) {
            LOGi("generateTokens: vocab EOG at step %d", i);
            break;
        }

        int n_piece = llama_token_to_piece(m_vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece < 0) {
            LOGw("generateTokens: token_to_piece returned %d, skipping", n_piece);
            continue; // skip malformed piece, don't break — model may recover
        }
        response.append(piece_buf, n_piece);

        // Decode the newly generated token into the KV cache at m_n_past position
        llama_batch batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(m_ctx, batch) != 0) {
            LOGe("generateTokens: llama_decode failed at step %d", i);
            break;
        }

        // ── THE KEY FIX ──────────────────────────────────────────────────
        // Advance the member-level cursor. This is what keeps the KV cache
        // and n_past in sync across multiple calls to processTextOnly /
        // processImageAndText. If this were a local variable, the next call
        // would reset to 0 and corrupt the KV cache state.
        m_n_past++;
    }

    // Reset sampler state (e.g. repetition penalty history) AFTER generation,
    // not before — resetting before would discard penalties for the current turn
    llama_sampler_reset(m_smpl);
    return response;
}

// ── Initialization ────────────────────────────────────────────────────────
bool LLMInference::init(const char* modelPath, const char* mmprojPath,
                        const char* backendPath, int contextSize, bool useVulkan) {

    // Register log callback first — without this all llama errors are invisible
    llama_log_set(llamaAndroidLogCallback, nullptr);
    LOGi("init: model=%s ctx=%d vulkan=%d", modelPath, contextSize, useVulkan ? 1 : 0);

    // ── Pre-flight file checks ────────────────────────────────────────────
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

    // ── Backend init ──────────────────────────────────────────────────────
    // ggml_backend_load_all() activates statically compiled backends (CPU etc.)
    // It does NOT need a path on Android when GGML_BACKEND_DL is OFF (default).
    ggml_backend_load_all();
    llama_backend_init();

    LOGi("init: backends loaded, registered count = %zu", ggml_backend_reg_count());

    // ── Model load ────────────────────────────────────────────────────────
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

    // ── Context init ──────────────────────────────────────────────────────
    const int n_threads = std::max(2, std::min(4, (int)sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("init: using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = contextSize;
    ctx_params.n_batch         = 512;   // larger batch for faster prompt processing
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

    // ── Multimodal projector init ─────────────────────────────────────────
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

    // ── Sampler init ──────────────────────────────────────────────────────
    auto sampler_params       = llama_sampler_chain_default_params();
    sampler_params.no_perf    = true;
    m_smpl                    = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(m_smpl, llama_sampler_init_greedy());

    LOGi("init: complete. n_ctx=%d threads=%d", contextSize, n_threads);
    return true;
}

// ── Image + Text inference ────────────────────────────────────────────────
std::string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt) {
    if (!m_mtmd_ctx || !m_ctx) {
        LOGe("processImageAndText: engine not initialized");
        return "";
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

    const string full_prompt = buildImagePrompt(m_mtmd_ctx, m_systemPrompt, prompt);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks*    chunks     = mtmd_input_chunks_init();
    const mtmd_bitmap*    bitmaps[]  = { bmp };
    const int             res        = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bmp); // free immediately after tokenize — safe

    if (res != 0) {
        LOGe("processImageAndText: mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    // Context overflow check before committing decode
    // Vision tokens can be large (Qwen2-VL uses ~256 per image)
    if (!hasContextHeadroom(512 /* approx vision + text tokens */)) {
        LOGw("processImageAndText: not enough context — call resetContext() first");
        mtmd_input_chunks_free(chunks);
        return "";
    }

    // mtmd_helper_eval_chunks appends to KV cache starting at m_n_past
    // and writes the new cursor position back into m_n_past
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, m_n_past, 0, 512, true, &m_n_past) != 0) {
        LOGe("processImageAndText: mtmd_helper_eval_chunks failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    LOGi("processImageAndText: prompt evaluated, n_past=%d, generating...", (int)m_n_past);
    return generateTokens(512);
}

// ── Text-only inference ───────────────────────────────────────────────────
std::string LLMInference::processTextOnly(const char* prompt) {
    if (!m_ctx) {
        LOGe("processTextOnly: engine not initialized");
        return "";
    }

    const string full_prompt = buildPrompt(m_systemPrompt, prompt);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const int          res    = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, nullptr, 0);
    if (res != 0) {
        LOGe("processTextOnly: mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    // Context overflow check
    if (!hasContextHeadroom(256)) {
        LOGw("processTextOnly: not enough context — call resetContext() first");
        mtmd_input_chunks_free(chunks);
        return "";
    }

    // Append to KV cache at m_n_past; m_n_past is updated by the call
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, m_n_past, 0, 512, true, &m_n_past) != 0) {
        LOGe("processTextOnly: mtmd_helper_eval_chunks failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    LOGi("processTextOnly: prompt evaluated, n_past=%d, generating...", (int)m_n_past);
    return generateTokens(512);
}

// ── Context reset ─────────────────────────────────────────────────────────
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

// ── Accessors ─────────────────────────────────────────────────────────────
std::string LLMInference::getBackendInfo() {
    return m_gpuUsed ? "GPU (Vulkan) ON" : "CPU only";
}

void LLMInference::setSystemPrompt(const std::string& sysPrompt) {
    m_systemPrompt = sysPrompt;
}

// ── Cleanup ───────────────────────────────────────────────────────────────
void LLMInference::release() {
    if (m_smpl)     { llama_sampler_free(m_smpl);  m_smpl     = nullptr; }
    if (m_mtmd_ctx) { mtmd_free(m_mtmd_ctx);       m_mtmd_ctx = nullptr; }
    if (m_ctx)      { llama_free(m_ctx);            m_ctx      = nullptr; }
    if (m_model)    { llama_model_free(m_model);   m_model    = nullptr; }
    llama_backend_free();
    m_n_past = 0;
    LOGi("release: all resources freed");
}

// ── File utilities ────────────────────────────────────────────────────────
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