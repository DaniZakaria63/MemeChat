//
// Created by dani on 5/6/26.
//
#include "LLMInference.h"
#include "logging.h"
#include "GGUFReader.h"   // uses the global model context
#include "llava.h"
#include "clip.h"
#include "llama.h"
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

std::string LLMInference::generateTokens(int max_new_tokens) {
    string response;
    llama_batch batch = llama_batch_init(1, 0, 1);
    llama_token eos = llama_token_eos(m_model);
    int n_past = llama_n_ctx(m_ctx);   // rough – you should track n_past properly.
    // (For simplicity, we assume n_past = prompt length. In a full implementation
    //  you'd track it as described in the text-only function above.)

    for (int i = 0; i < max_new_tokens; i++) {
        float* logits = llama_get_logits_ith(m_ctx, -1);
        llama_token new_token = llama_sample_token_greedy(m_ctx, nullptr);
        if (new_token == eos) break;

        response += llama_token_to_piece(m_ctx, new_token);

        llama_batch_clear(batch);
        llama_batch_add(batch, new_token, n_past, {0}, true);
        if (llama_decode(m_ctx, batch) != 0) break;
        n_past++;
    }
    llama_batch_free(batch);
    return response;
}

// ---------------------------------------------------------------------------
// LLMInference implementation
// ---------------------------------------------------------------------------
bool LLMInference::init(const char* modelPath, const char* mmprojPath,
                        int contextSize, bool useVulkan) {
    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);

    // 1. LLM
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = useVulkan ? 99 : 0;
    m_model = llama_load_model_from_file(modelPath, model_params);
    if (!m_model) return false;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = 64;
    ctx_params.n_ubatch = 64;
    m_ctx = llama_new_context_with_model(m_model, ctx_params);
    if (!m_ctx) return false;

    // 2. Multimodal projector (replaces clip+llava)
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = useVulkan;
    mtmd_params.n_threads = 4;
    mtmd_params.print_timings = false;
    m_mtmd_ctx = mtmd_init_from_file(mmprojPath, m_model, mtmd_params);
    if (!m_mtmd_ctx) {
        LOGE("mtmd_init_from_file failed");
        llama_free(m_ctx);
        llama_free_model(m_model);
        return false;
    }

    m_gpuLayers = llama_model_n_gpu_layers(m_model);
    m_gpuUsed = (m_gpuLayers > 0);
    LOGI("Model loaded. GPU layers: %d (Vulkan: %s)", m_gpuLayers, m_gpuUsed ? "YES" : "NO");
    return true;
}

void LLMInference::setSystemPrompt(const std::string& sysPrompt) {
    m_systemPrompt = sysPrompt;
}

std::string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt) {
    if (!m_mtmd_ctx) {
        LOGE("MTMD not loaded");
        return "";
    }

    // 1. Convert Android Bitmap to raw RGB bytes
    vector<uint8_t> rgb = bitmapToRGB(env, bitmap);
    if (rgb.empty()) return "";

    // 2. Get bitmap dimensions from AndroidBitmapInfo (we could reuse from previous call)
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    int w = info.width, h = info.height;

    // 3. Create mtmd_bitmap (takes ownership of a copy)
    mtmd_bitmap* bmp = mtmd_bitmap_init(w, h, rgb.data());
    if (!bmp) return "";

    // 4. Build full prompt (system + <__media__> + user text)
    string full_prompt = buildPrompt(m_systemPrompt, prompt);

    // 5. Tokenize with mtmd – it splits the prompt around <__media__> and attaches the image
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

    // 6. Evaluate all chunks (text + image) in one go
    llama_pos new_n_past;
    if (mtmd_helper_eval_chunks(m_mtmd_ctx, m_ctx, chunks, 0, 0, 64, true, &new_n_past) != 0) {
        LOGE("Chunk evaluation failed");
        mtmd_input_chunks_free(chunks);
        return "";
    }
    mtmd_input_chunks_free(chunks);

    // 7. Generate response (uses internal m_ctx and m_model)
    return generateTokens(512);
}

std::string LLMInference::processTextOnly(const char* prompt) {
    if (!m_ctx) return "";

    llama_kv_cache_clear(m_ctx);

    // Build prompt (system + user → assistant)
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

    vector<llama_token> tokens = llama_tokenize(m_ctx, full_prompt, true, true);
    if (tokens.empty()) return "";

    // Decode the prompt
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++)
        llama_batch_add(batch, tokens[i], i, {0}, i == tokens.size()-1);

    if (llama_decode(m_ctx, batch) != 0) {
        LOGE("Initial decode failed");
        llama_batch_free(batch);
        return "";
    }
    llama_batch_free(batch);

    return generateTokens(512);
}

std::string LLMInference::getBackendInfo() {
    const auto& m = getModelContext();
    if (m.gpuUsed)
        return "GPU (Vulkan), layers offloaded: " + std::to_string(m.gpuLayers);
    else
        return "CPU only";
}

void LLMInference::release() {
    if (m_mtmd_ctx)   mtmd_free(m_mtmd_ctx);
    if (m_ctx)        llama_free(m_ctx);
    if (m_model)      llama_free_model(m_model);
    llama_backend_free();
}