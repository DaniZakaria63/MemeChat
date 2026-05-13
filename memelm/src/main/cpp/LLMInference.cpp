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
#include "logging.h"
#include "llama.h"
#include "LLMInference.h"


using namespace std;

static bool readFileHeader(const char* path, char* out, size_t len);
static long long getFileSize(const char* path);

static void llamaAndroidLogCallback(ggml_log_level level, const char* text, void* /* user_data */) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGe("llama: %s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGw("llama: %s", text); break;
        case GGML_LOG_LEVEL_INFO:  LOGi("llama: %s", text); break;
        default:                   LOGi("llama: %s", text); break;
    }
}

static std::vector<uint8_t> bitmapToRGB(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGe("Failed to get bitmap info");
        return {};
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGe("Only RGBA_8888 supported");
        return {};
    }
    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGe("Failed to lock bitmap pixels");
        return {};
    }
    int w = info.width, h = info.height;
    std::vector<uint8_t> rgb(w * h * 3);
    uint8_t* src = static_cast<uint8_t*>(pixels);
    for (int i = 0; i < w * h; i++) {
        rgb[i * 3 + 0] = src[i * 4 + 0];
        rgb[i * 3 + 1] = src[i * 4 + 1];
        rgb[i * 3 + 2] = src[i * 4 + 2];
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return rgb;
}

// ── Prompt Template Tokens (File-scope, exact Qwen2-VL formatting) ───────
static constexpr const char* TOK_IM_START  = "<|im_start|>";
static constexpr const char* TOK_IM_END    = "<|im_end|>";
static constexpr const char* TOK_SYSTEM    = "system\n";
static constexpr const char* TOK_USER      = "user\n";
static constexpr const char* TOK_ASSISTANT = "assistant\n";

static string buildPrompt(const string& systemPrompt, const string& userPrompt) {
    string result;
    result += TOK_IM_START; result += TOK_SYSTEM; result += systemPrompt; result += TOK_IM_END; result += "\n";
    result += TOK_IM_START; result += TOK_USER; result += userPrompt; result += TOK_IM_END; result += "\n";
    result += TOK_IM_START; result += TOK_ASSISTANT;
    return result;
}

std::vector<llama_token> LLMInference::tokenize(const string& text, bool add_special, bool parse_special) {
    int n_ctx = llama_n_ctx(m_ctx);
    std::vector<llama_token> tokens(std::max(1, n_ctx));
    int n = llama_tokenize(m_vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), add_special, parse_special);
    if (n < 0) {
        LOGe("Tokenization failed");
        return {};
    }
    tokens.resize(n);
    return tokens;
}

string LLMInference::generateTokens(int n_past, int max_new_tokens) {
    string response;
    char piece_buf[256];

    for (int i = 0; i < max_new_tokens; i++) {
        llama_token new_token = llama_sampler_sample(m_smpl, m_ctx, -1);

        // Qwen2-VL uses multiple EOG tokens
        if (new_token == 151645 || new_token == 151643 || new_token == 128247) {
            break;
        }

        int n_piece = llama_token_to_piece(m_vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece < 0) break;
        response.append(piece_buf, n_piece);

        llama_batch batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(m_ctx, batch) != 0) {
            LOGe("Decode failed at token %d", i);
            break;
        }
        n_past++;
    }

    llama_sampler_reset(m_smpl);
    return response;
}

// ---------------------------------------------------------------------------
// LLMInference implementation
// ---------------------------------------------------------------------------
bool LLMInference::init(const char* modelPath, const char* mmprojPath,
                        const char* backendPath, int contextSize, bool useVulkan) {

    llama_log_set(llamaAndroidLogCallback, nullptr);
    LOGi("Init: model=%s mmproj=%s ctx=%d vulkan=%d", modelPath, mmprojPath, contextSize, useVulkan ? 1 : 0);

    long long modelSize = getFileSize(modelPath);
    long long mmprojSize = getFileSize(mmprojPath);
    if (modelSize <= 0 || mmprojSize <= 0) {
        LOGe("PREFLIGHT FAIL: model or mmproj file missing/unreadable");
        return false;
    }
    char header[4] = {0};
    if (!readFileHeader(modelPath, header, 4) ||
        (uint8_t)header[0] != 0x47 || (uint8_t)header[1] != 0x47 ||
        (uint8_t)header[2] != 0x55 || (uint8_t)header[3] != 0x46) {
        LOGe("PREFLIGHT FAIL: invalid GGUF header");
        return false;
    }

    ggml_backend_load_all();
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_mlock = false;
    model_params.n_gpu_layers = 0; // Forced CPU for Mali-G57 stability
    m_gpuUsed = false;

    m_model = llama_model_load_from_file(modelPath, model_params);
    if (!m_model) {
        LOGe("Failed to load model from %s", modelPath);
        return false;
    }

    const int n_threads = std::max(2, std::min(4, (int)sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = 64;
    ctx_params.n_ubatch = 64;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    m_ctx = llama_init_from_model(m_model, ctx_params);
    if (!m_ctx) {
        LOGe("llama_init_from_model failed");
        llama_model_free(m_model);
        m_model = nullptr;
        return false;
    }
    m_vocab = llama_model_get_vocab(m_model);

    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;
    mtmd_params.n_threads = n_threads;
    mtmd_params.print_timings = false;

    m_mtmd_ctx = mtmd_init_from_file(mmprojPath, m_model, mtmd_params);
    if (!m_mtmd_ctx) {
        LOGe("mtmd_init_from_file failed");
        llama_free(m_ctx);
        llama_model_free(m_model);
        m_ctx = nullptr;
        m_model = nullptr;
        return false;
    }

    auto sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    m_smpl = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(m_smpl, llama_sampler_init_greedy());

    LOGi("Initialization complete. Backend: CPU (Vulkan disabled for stability)");
    return true;
}

std::string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt) {
    if (!m_mtmd_ctx || !m_ctx) return "";

    std::vector<uint8_t> rgb = bitmapToRGB(env, bitmap);
    if (rgb.empty()) return "";

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    mtmd_bitmap* bmp = mtmd_bitmap_init(info.width, info.height, rgb.data());
    if (!bmp) return "";

    string full_prompt = buildPrompt(m_systemPrompt, prompt);

    mtmd_input_text input_text;
    input_text.text = full_prompt.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const mtmd_bitmap* bitmaps[] = { bmp };
    int res = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bmp);

    if (res != 0) {
        LOGe("mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, 0, 0, 64, true, &n_past) != 0) {
        LOGe("Chunk evaluation failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    return generateTokens(n_past, 512);
}

std::string LLMInference::processTextOnly(const char* prompt) {
    if (!m_ctx) return "";

    string full_prompt = buildPrompt(m_systemPrompt, prompt);

    mtmd_input_text input_text;
    input_text.text = full_prompt.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    int res = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, nullptr, 0);
    if (res != 0) {
        LOGe("mtmd_tokenize (text) failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, 0, 0, 64, true, &n_past) != 0) {
        LOGe("Text chunk evaluation failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    return generateTokens(n_past, 512);
}

std::string LLMInference::getBackendInfo() {
    return m_gpuUsed ? "GPU (Vulkan) ON" : "CPU only";
}

void LLMInference::setSystemPrompt(const std::string& sysPrompt) {
    m_systemPrompt = sysPrompt;
}

void LLMInference::resetContext() {
    if (m_ctx) {
        // Modern llama.cpp (late 2024+): llama_kv_cache_clear is the standard API.
        // Older versions: llama_kv_cache_seq_rm. If both are missing, the build
        // is too old for Qwen2-VL and will fail at tensor loading anyway.
        #if defined(LLAMA_KV_CACHE_CLEAR)
                llama_kv_cache_clear(m_ctx);
        #elif defined(LLAMA_KV_CACHE_SEQ_RM)
                llama_kv_cache_seq_rm(m_ctx, -1, 0, -1);
        #else
                LOGw("KV cache API missing. Update llama.cpp submodule to >= Nov 2024.");
                return;
        #endif

        LOGi("KV cache cleared");
    }
}

void LLMInference::release() {
    if (m_smpl) { llama_sampler_free(m_smpl); m_smpl = nullptr; }
    if (m_mtmd_ctx) { mtmd_free(m_mtmd_ctx); m_mtmd_ctx = nullptr; }
    if (m_ctx) { llama_free(m_ctx); m_ctx = nullptr; }
    if (m_model) { llama_model_free(m_model); m_model = nullptr; }
    llama_backend_free();
}

static bool readFileHeader(const char* path, char* out, size_t len) {
    if (!path || !out || len == 0) return false;
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;
    file.read(out, len);
    return file.gcount() == static_cast<std::streamsize>(len);
}

static long long getFileSize(const char* path) {
    if (!path || path[0] == '\0') return -1;
    struct stat st {};
    if (stat(path, &st) != 0) return -1;
    return static_cast<long long>(st.st_size);
}
