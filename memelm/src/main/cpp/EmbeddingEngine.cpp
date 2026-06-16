#include "EmbeddingEngine.h"
#include "logging.h"
#include <cmath>
#include <cstring>

bool EmbeddingEngine::init(const char* modelPath, int contextSize) {
    // Ensure any previous state is cleaned up before re-init.
    release();

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.use_mmap     = true;
    modelParams.use_mlock    = false;
    modelParams.n_gpu_layers = 0;

    m_model = llama_model_load_from_file(modelPath, modelParams);
    if (!m_model) {
        LOGe("EmbeddingEngine: failed to load model from %s", modelPath);
        return false;
    }

    m_vocab = llama_model_get_vocab(m_model);
    n_embd  = llama_model_n_embd(m_model);
    n_batch = contextSize;

    // Build the embedding-specific context params.
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx           = contextSize;
    ctxParams.n_batch         = n_batch;
    ctxParams.n_ubatch        = n_batch;
    ctxParams.embeddings      = true;
    ctxParams.pooling_type    = LLAMA_POOLING_TYPE_MEAN;
    ctxParams.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctxParams.offload_kqv      = true;

    m_ctx = llama_init_from_model(m_model, ctxParams);
    if (m_ctx == nullptr) {
        LOGe("EmbeddingEngine: failed to create llama_context for embeddings");
        llama_model_free(m_model);
        m_model = nullptr;
        m_vocab = nullptr;
        n_embd  = 0;
        return false;
    }

    LOGi("EmbeddingEngine initialized | n_embd=%d n_ctx=%d", n_embd, contextSize);
    return true;
}

std::vector<float> EmbeddingEngine::embed(const std::string& text) {
    if (m_ctx == nullptr || m_model == nullptr) {
        LOGe("EmbeddingEngine::embed — engine not initialised");
        return {};
    }

    std::vector<llama_token> tokens(n_embd);
    int nTokens = llama_tokenize(
        m_vocab,
        text.c_str(),
        text.size(),
        tokens.data(),
        static_cast<int>(tokens.size()),
        true,   // add_special
        false   // parse_special
    );

    if (nTokens < 0) {
        LOGe("EmbeddingEngine::embed — tokenization failed (input too long?)");
        return {};
    }
    if (nTokens == 0) {
        LOGw("EmbeddingEngine::embed — tokenized to zero tokens, returning zero vector");
        return std::vector<float>(n_embd, 0.0f);
    }
    tokens.resize(nTokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), nTokens);

    llama_memory_clear(llama_get_memory(m_ctx), true);

    if (llama_decode(m_ctx, batch) != 0) {
        LOGe("EmbeddingEngine::embed — llama_decode failed");
        return {};
    }

    // Pooling is hardcoded to MEAN in init(), so always one vector per sequence.
    const float* embData = llama_get_embeddings_seq(m_ctx, 0);

    if (embData == nullptr) {
        LOGe("EmbeddingEngine::embed — embedding data is null");
        return {};
    }

    std::vector<float> result(embData, embData + n_embd);
    l2Normalize(result);

    return result;
}

/* -----------------------------------------------------------------------
 * embedBatch()
 *
 * Processes multiple texts by calling embed() for each one.
 * The sequential loop is simple and avoids cross-sequence interference
 * in the llama_context's internal state.
 *
 * Future optimisation: for many short texts (<128 tokens each), pack
 * them into separate sequences in a single llama_batch to amortise
 * transformer overhead.  This requires multi-sequence support and
 * sequence-level embedding extraction.
 * ----------------------------------------------------------------------- */
std::vector<std::vector<float>> EmbeddingEngine::embedBatch(
    const std::vector<std::string>& texts) {

    std::vector<std::vector<float>> results;
    results.reserve(texts.size());

    for (size_t i = 0; i < texts.size(); ++i) {
        LOGi("EmbeddingEngine::embedBatch [%zu/%zu] — embedding...",
            i + 1, texts.size());
        results.push_back(embed(texts[i]));
    }

    return results;
}

int EmbeddingEngine::dimension() const {
    return n_embd;
}

void EmbeddingEngine::release() {
    if (m_ctx) {
        llama_free(m_ctx);
        m_ctx = nullptr;
    }
    if (m_model) {
        llama_model_free(m_model);
        m_model = nullptr;
    }
    m_vocab = nullptr;
    n_embd  = 0;
    llama_backend_free();
}

/* -----------------------------------------------------------------------
 *
 * In-place L2 normalisation: each element is divided by the Euclidean
 * norm of the vector.
 *
 *   vec[i] = vec[i] / sqrt(sum(vec[j]^2))
 *
 * The norm is clamped to 1e-12 to avoid division by zero.
 * After L2 normalisation, FAISS inner product (IndexFlatIP) becomes
 * cosine similarity, which is the standard similarity metric for
 * sentence embeddings.
 * ----------------------------------------------------------------------- */
void EmbeddingEngine::l2Normalize(std::vector<float>& vec) const {
    float sumSq = 0.0f;
    for (float v : vec) {
        sumSq += v * v;
    }

    float norm = std::sqrt(std::max(sumSq, 1e-12f));
    float invNorm = 1.0f / norm;

    for (float& v : vec) {
        v *= invNorm;
    }
}
