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

namespace {

// Helper: convert Android Bitmap to clip_image_u8
    clip_image_u8* bitmapToClipImage(JNIEnv* env, jobject bitmap) {
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("getInfo failed");
            return nullptr;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Only RGBA_8888 bitmaps are supported");
            return nullptr;
        }

        void* pixels;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("lockPixels failed");
            return nullptr;
        }

        clip_image_u8* img = clip_image_u8_init();
        if (!img) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return nullptr;
        }

        img->nx = info.width;
        img->ny = info.height;
        img->buf.resize(3 * info.width * info.height);

        uint8_t* src = (uint8_t*)pixels;
        uint8_t* dst = img->buf.data();
        for (int y = 0; y < info.height; y++) {
            for (int x = 0; x < info.width; x++) {
                int src_idx = (y * info.width + x) * 4;
                int dst_idx = (y * info.width + x) * 3;
                dst[dst_idx + 0] = src[src_idx + 0]; // R
                dst[dst_idx + 1] = src[src_idx + 1]; // G
                dst[dst_idx + 2] = src[src_idx + 2]; // B
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        return img;
    }

    static const char* TOK_IM_START  = "<|im_start|>";
    static const char* TOK_IM_END    = "<|im_end|>";
    static const char* TOK_SYSTEM    = "system\n";
    static const char* TOK_USER      = "user\n";
    static const char* TOK_ASSISTANT = "assistant\n";
    static const char* IMAGE_TOKEN   = "<image>";

    static string buildPrompt(const string& systemPrompt, const string& userPrompt) {
        string result;
        result += TOK_IM_START;
        result += TOK_SYSTEM;
        result += systemPrompt;
        result += TOK_IM_END;
        result += "\n";
        result += TOK_IM_START;
        result += TOK_USER;
        result += IMAGE_TOKEN;
        result += "\n";
        result += userPrompt;
        result += TOK_IM_END;
        result += "\n";
        result += TOK_IM_START;
        result += TOK_ASSISTANT;
        return result;
    }

    static bool evalTokensWithImageEmbed(llama_context* ctx,const vector<llama_token>& tokens,const llava_image_embed* embed,llama_batch& batch,int& n_past) {
        // find the <image> placeholder token
        auto image_tokens = llama_tokenize(ctx, IMAGE_TOKEN, false, true);
        if (image_tokens.size() != 1) {
            LOGE("Could not tokenize '<image>'");
            return false;
        }
        llama_token image_tok = image_tokens[0];

        int img_pos = -1;
        for (int i = 0; i < (int)tokens.size(); i++) {
            if (tokens[i] == image_tok) {
                img_pos = i;
                break;
            }
        }
        if (img_pos == -1) {
            LOGE("No <image> token found in prompt");
            return false;
        }

        // evaluate tokens BEFORE the image
        int pre = img_pos;
        for (int i = 0; i < pre; ) {
            int chunk = min((int)batch.n_tokens, pre - i);
            llama_batch_clear(batch);
            for (int j = 0; j < chunk; j++) {
                llama_batch_add(batch, tokens[i + j], n_past, {0}, i + j == pre - 1);
            }
            if (llama_decode(ctx, batch) != 0) {
                LOGE("Decode failed (pre-image)");
                return false;
            }
            n_past += chunk;
            i += chunk;
        }

        // insert image embedding (this function advances n_past)
        if (!llava_eval_image_embed(ctx, embed, batch.n_tokens, &n_past)) {
            LOGE("Failed to evaluate image embedding");
            return false;
        }

        // evaluate tokens AFTER the image
        int post_start = img_pos + 1;
        int remaining = tokens.size() - post_start;
        for (int i = 0; i < remaining; ) {
            int chunk = min((int)batch.n_tokens, remaining - i);
            llama_batch_clear(batch);
            for (int j = 0; j < chunk; j++) {
                llama_batch_add(batch, tokens[post_start + i + j], n_past, {0}, i + j == chunk - 1);
            }
            if (llama_decode(ctx, batch) != 0) {
                LOGE("Decode failed (post-image)");
                return false;
            }
            n_past += chunk;
            i += chunk;
        }
        return true;
    }

    // Helper: run the LLM generation loop on a tokenized prompt
    // (prompt_tokens already includes image embedding if needed)
    std::string generateTokens(llama_context* ctx, llama_model* model,
                               std::vector<llama_token>& prompt_tokens) {
        string response;
        llama_batch batch = llama_batch_init(1, 0, 1);
        llama_token eos = llama_token_eos(model);

        for (int i = 0; i < max_new_tokens; i++) {
            float* logits = llama_get_logits_ith(ctx, -1);
            llama_token new_token = llama_sample_token_greedy(ctx, nullptr);
            if (new_token == eos) break;

            response += llama_token_to_piece(ctx, new_token);

            llama_batch_clear(batch);
            llama_batch_add(batch, new_token, n_past, {0}, true);
            if (llama_decode(ctx, batch) != 0) break;
            n_past++;
        }
        llama_batch_free(batch);
        return response;
    }

} // anonymous namespace

// ---------------------------------------------------------------------------
// LLMInference implementation
// ---------------------------------------------------------------------------

bool LLMInference::init(const char* modelPath, int contextSize, bool useVulkan) {
    return loadModel(modelPath, contextSize, useVulkan);
}

void LLMInference::setSystemPrompt(const std::string& sysPrompt) {
    m_systemPrompt = sysPrompt;
}

std::string LLMInference::processImageAndText(JNIEnv* env, jobject bitmap, const char* prompt) {
    const auto& m = getModelContext();
    if (!m.ctx || !m.clip || !m.llava) {
        LOGE("Multimodal components not loaded");
        return "";
    }

    // 1. Convert bitmap -> clip_image
    clip_image_u8* img = bitmapToClipImage(env, bitmap);
    if (!img) return "";

    // Resize to 448x448 (MiniCPM‑V‑2B expectation)
    clip_image_u8* resized = clip_image_u8_init();
    clip_image_u8_resize(img, resized, 448, 448);
    clip_image_u8_free(img);

    // 2. Create image embedding
    llava_image_embed* embed = llava_image_embed_make_with_image_u8(m.clip, 0, resized);
    clip_image_u8_free(resized);
    if (!embed) {
        LOGE("Image embedding failed");
        return "";
    }

    // 3. Build full prompt with system prompt and <image> placeholder
    string full_prompt = buildPrompt(m_systemPrompt, prompt);
    LOGI("Full prompt: %s", full_prompt.c_str());

    // 4. Tokenize the full prompt
    vector<llama_token> tokens = llama_tokenize(m.ctx, full_prompt, true, true);
    if (tokens.empty()) {
        LOGE("Empty tokenization");
        llava_image_embed_free(embed);
        return "";
    }

    // 5. Evaluate tokens with image embedding insertion
    int n_past = 0;
    llama_batch batch = llama_batch_init(256, 0, 1); // batch size for prompt ingestion
    if (!evalTokensWithImageEmbed(m.ctx, tokens, embed, batch, n_past)) {
        llama_batch_free(batch);
        llava_image_embed_free(embed);
        return "";
    }
    llama_batch_free(batch);
    llava_image_embed_free(embed);

    // 6. Generate response
    string result = generateTokens(m.ctx, m.model, n_past);
    return result;
}

std::string LLMInference::processTextOnly(const char* prompt) {
    const auto& m = getModelContext();
    if (!m.ctx) return "";

    // Clear KV cache for a fresh conversation
    llama_kv_cache_clear(m.ctx);

    // Build prompt with system prompt (no <image>)
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

    vector<llama_token> tokens = llama_tokenize(m.ctx, full_prompt, true, true);
    if (tokens.empty()) return "";

    int n_past = 0;
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_batch_add(batch, tokens[i], n_past, {0}, i == tokens.size() - 1);
    }
    if (llama_decode(m.ctx, batch) != 0) {
        LOGE("Text-only initial decode failed");
        llama_batch_free(batch);
        return "";
    }
    n_past = tokens.size();
    llama_batch_free(batch);

    return generateTokens(m.ctx, m.model, n_past);
}

std::string LLMInference::getBackendInfo() {
    const auto& m = getModelContext();
    if (m.gpuUsed)
        return "GPU (Vulkan), layers offloaded: " + std::to_string(m.gpuLayers);
    else
        return "CPU only";
}

void LLMInference::release() {
    releaseModel();
}