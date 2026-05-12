#include "GGUFReader.h"     // we’ll define the class here, or in separate header
#include "llama.h"
#include "clip.h"           // from llava
#include "llava.h"
#include "logging.h"

struct ModelContext {
    llama_model*    model   = nullptr;
    llama_context*  ctx     = nullptr;
    clip_ctx*       clip    = nullptr;
    llava_context*  llava   = nullptr;
    bool            gpuUsed = false;
    int             gpuLayers = 0;
};

static ModelContext g_mctx;

bool loadModel(const char* modelPath, int n_ctx, bool useVulkan) {
    // 1. backend init
    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);

    // 2. model parameters
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = useVulkan ? 99 : 0;   // offload all possible layers

    g_mctx.model = llama_load_model_from_file(modelPath, mparams);
    if (!g_mctx.model) {
        LOGE("Failed to load GGUF model");
        return false;
    }

    // 3. check if model is multimodal (contains clip.model metadata)
    int clip_idx = llama_model_meta_val_str(g_mctx.model, "clip.model", nullptr, 0);
    if (clip_idx < 0) {
        LOGE("Model is not a multimodal GGUF (missing clip.model)");
        llama_free_model(g_mctx.model);
        g_mctx.model = nullptr;
        return false;
    }

    // 4. context parameters
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = n_ctx;
    cparams.n_batch = 64;
    cparams.n_ubatch = 64;
    cparams.flash_attn = true;   // usually supported, reduces memory spikes

    g_mctx.ctx = llama_new_context_with_model(g_mctx.model, cparams);
    if (!g_mctx.ctx) {
        LOGE("Failed to create LLM context");
        llama_free_model(g_mctx.model);
        g_mctx.model = nullptr;
        return false;
    }

    // 5. load CLIP vision encoder (built into the same GGUF)
    g_mctx.clip = clip_model_load(modelPath, /*verbosity=*/0);
    if (!g_mctx.clip) {
        LOGE("Failed to load CLIP from GGUF");
        llama_free(g_mctx.ctx);
        llama_free_model(g_mctx.model);
        g_mctx.ctx = nullptr;
        g_mctx.model = nullptr;
        return false;
    }

    // 6. create llava connector
    g_mctx.llava = llava_init(g_mctx.ctx, g_mctx.clip);
    if (!g_mctx.llava) {
        LOGE("Failed to init llava context");
        clip_free(g_mctx.clip);
        llama_free(g_mctx.ctx);
        llama_free_model(g_mctx.model);
        g_mctx.clip = nullptr;
        g_mctx.ctx = nullptr;
        g_mctx.model = nullptr;
        return false;
    }

    // 7. check GPU usage
    g_mctx.gpuLayers = llama_model_n_gpu_layers(g_mctx.model);
    g_mctx.gpuUsed   = (g_mctx.gpuLayers > 0);

    LOGI("Model loaded. GPU layers offloaded: %d (Vulkan: %s)",
         g_mctx.gpuLayers, g_mctx.gpuUsed ? "YES" : "NO");

    return true;
}

void releaseModel() {
    if (g_mctx.llava)  llava_free(g_mctx.llava);
    if (g_mctx.clip)   clip_free(g_mctx.clip);
    if (g_mctx.ctx)    llama_free(g_mctx.ctx);
    if (g_mctx.model)  llama_free_model(g_mctx.model);
    llama_backend_free();

    g_mctx = ModelContext{};
    LOGI("Resources released");
}

// Accessor for inference class
const ModelContext& getModelContext() { return g_mctx; }