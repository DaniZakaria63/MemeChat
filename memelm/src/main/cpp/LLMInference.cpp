//
// Created by dani on 5/6/26.
//
#include "LLMInference.h"
#include "logging.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <android/bitmap.h>
#include <vector>
#include <cstring>

using namespace std;

static std::vector<uint8_t> bitmapToRGB(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Only RGBA_8888 supported");
        return {};
    }
    void* pixels;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);

    int w = info.width, h = info.height;
    std::vector<uint8_t> rgb(w * h * 3);
    uint8_t* src = (uint8_t*)pixels;
    for (int i = 0; i < w * h; i++) {
        rgb[i * 3 + 0] = src[i * 4 + 0];
        rgb[i * 3 + 1] = src[i * 4 + 1];
        rgb[i * 3 + 2] = src[i * 4 + 2];
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return rgb;
}

static const char* TOK_IM_START  = "<|im_start|>";
static const char* TOK_IM_END    = "<|im_end|>";
static const char* TOK_SYSTEM    = "system\n";
static const char* TOK_USER      = "user\n";
static const char* TOK_ASSISTANT = "assistant\n";
static const char* MEDIA_TOKEN   = "<__media__>\n";

static string buildPrompt(const string& systemPrompt, const string& userPrompt) {
    string result;
    result += TOK_IM_START;
    result += TOK_SYSTEM;
    result += systemPrompt;
    result += TOK_IM_END;
    result += "\n";
    result += TOK_IM_START;
    result += TOK_USER;
    result += MEDIA_TOKEN;
    result += userPrompt;
    result += TOK_IM_END;
    result += "\n";
    result += TOK_IM_START;
    result += TOK_ASSISTANT;
    return result;
}
string LLMInference::generateTokens(int n_past, int max_new_tokens) {
    string response;
    char piece_buf[256];
    llama_token eos = llama_vocab_eos(m_vocab);

    for (int i = 0; i < max_new_tokens; i++) {
        llama_token new_token = llama_sampler_sample(m_smpl, m_ctx, -1);
        if (new_token == eos) break;

        int n_piece = llama_token_to_piece(m_vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece < 0) break;
        response.append(piece_buf, n_piece);

        // Build a one‑token batch manually
        llama_batch batch;
        batch.n_tokens = 1;
        batch.token    = &new_token;

        llama_pos pos = n_past;
        batch.pos      = &pos;

        int32_t n_seq = 1;
        batch.n_seq_id = &n_seq;

        llama_seq_id seq_id_val = 0;
        llama_seq_id* seq_ids[1] = { &seq_id_val };
        batch.seq_id   = seq_ids;

        int8_t logit_val = 1;
        batch.logits   = &logit_val;

        if (llama_decode(m_ctx, batch) != 0) {
            LOGE("Decode failed");
            break;
        }
        n_past++;
    }

    llama_sampler_reset(m_smpl);
    return response;
}

std::vector<llama_token> LLMInference::tokenize(const string& text, bool add_special, bool parse_special) {
    // Get max tokens from context size (safe upper bound)
    int n_ctx = llama_n_ctx(m_ctx);
    std::vector<llama_token> tokens(std::max(1, n_ctx));
    int n = llama_tokenize(m_vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), add_special, parse_special);
    if (n < 0) {
        LOGE("Tokenization failed");
        return {};
    }
    tokens.resize(n);
    return tokens;
}

// ---------------------------------------------------------------------------
// LLMInference implementation
// ---------------------------------------------------------------------------
bool LLMInference::init(const char* modelPath, const char* mmprojPath,
                        int contextSize, bool useVulkan) {
    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);

    // 1. Load LLM
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = useVulkan ? 99 : 0;
    m_model = llama_model_load_from_file(modelPath, model_params);
    if (!m_model) return false;

    // Context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = 64;
    ctx_params.n_ubatch = 64;
    m_ctx = llama_init_from_model(m_model, ctx_params);
    if (!m_ctx) {
        llama_model_free(m_model);
        return false;
    }

    m_vocab = llama_model_get_vocab(m_model);

    // 2. Load multimodal projector (mmproj)
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = useVulkan;
    mtmd_params.n_threads = 4;
    mtmd_params.print_timings = false;
    m_mtmd_ctx = mtmd_init_from_file(mmprojPath, m_model, mtmd_params);
    if (!m_mtmd_ctx) {
        llama_free(m_ctx);
        llama_model_free(m_model);
        return false;
    }

    m_gpuUsed = useVulkan;
    LOGI("Model loaded. GPU layers requested, Vulkan %s", m_gpuUsed ? "active" : "off");

    // 3. Create greedy sampler
    auto sparams = llama_sampler_chain_default_params();
    m_smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(m_smpl, llama_sampler_init_greedy());

    return true;
}

std::string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt) {
    if (!m_mtmd_ctx || !m_ctx) return "";

    // 1. Get raw RGB bytes
    vector<uint8_t> rgb = bitmapToRGB(env, bitmap);
    if (rgb.empty()) return "";

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    int w = info.width, h = info.height;

    // 2. Create mtmd bitmap
    mtmd_bitmap* bmp = mtmd_bitmap_init(w, h, rgb.data());
    if (!bmp) return "";

    // 3. Build prompt
    string full_prompt = buildPrompt(m_systemPrompt, prompt);

    // 4. Tokenize with mtmd
    mtmd_input_text input_text;
    input_text.text = full_prompt.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const mtmd_bitmap* bitmaps[] = { bmp };
    int res = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bmp);
    if (res != 0) {
        LOGE("mtmd_tokenize failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    // 5. Evaluate all chunks (text + image)
    llama_pos n_past;
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, 0, 0, 64, true, &n_past) != 0) {
        LOGE("Chunk evaluation failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    // 6. Generate response
    return generateTokens(512);
}

std::string LLMInference::processTextOnly(const char* prompt) {
    if (!m_ctx) return "";

    string full_prompt;
    if (!m_systemPrompt.empty()) {
        full_prompt += TOK_IM_START;
        full_prompt += TOK_SYSTEM;
        full_prompt += m_systemPrompt;
        full_prompt += TOK_IM_END;
        full_prompt += "\n";
    }
    full_prompt += TOK_IM_START;
    full_prompt += TOK_USER;
    full_prompt += "\n";
    full_prompt += prompt;
    full_prompt += TOK_IM_END;
    full_prompt += "\n";
    full_prompt += TOK_IM_START;
    full_prompt += TOK_ASSISTANT;

    // Use mtmd_tokenize with 0 bitmaps
    mtmd_input_text input_text;
    input_text.text = full_prompt.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    int res = mtmd_tokenize(m_mtmd_ctx, chunks, &input_text, nullptr, 0);
    if (res != 0) {
        LOGE("mtmd_tokenize (text) failed (%d)", res);
        mtmd_input_chunks_free(chunks);
        return "";
    }

    // Evaluate the text‑only chunks – mtmd handles KV cache automatically
    llama_pos n_past;
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, 0, 0, 64, true, &n_past) != 0) {
        LOGE("Text chunk evaluation failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    // Generate response
    return generateTokens(n_past, 512);
}

std::string LLMInference::getBackendInfo() {
    return m_gpuUsed ? "GPU (Vulkan) ON" : "CPU only";
}

void LLMInference::setSystemPrompt(const std::string& sysPrompt) {
    m_systemPrompt = sysPrompt;
}


void LLMInference::release() {
    if (m_smpl) {
        llama_sampler_free(m_smpl);
        m_smpl = nullptr;
    }
    if (m_mtmd_ctx) {
        mtmd_free(m_mtmd_ctx);
        m_mtmd_ctx = nullptr;
    }
    if (m_ctx) {
        llama_free(m_ctx);
        m_ctx = nullptr;
    }
    if (m_model) {
        llama_model_free(m_model);
        m_model = nullptr;
    }
    llama_backend_free();
}