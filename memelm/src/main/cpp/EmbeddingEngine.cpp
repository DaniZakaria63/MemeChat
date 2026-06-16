#include "EmbeddingEngine.h"
#include "logging.h"
#include <cmath>
#include <cstring>

bool EmbeddingEngine::init(llama_model* model, int contextSize) {
    if (model == nullptr) {
        LOGe("EmbeddingEngine: received null model pointer");
        return false;
    }

    // Ensure any previous context is cleaned up before re-init.
    release();

    m_model = model;
    n_embd  = llama_model_n_embd(m_model);
    n_batch = contextSize;

    // Build the embedding-specific context params.
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx           = contextSize;
    ctxParams.n_batch         = n_batch;
    ctxParams.n_ubatch        = n_batch;
    ctxParams.embeddings      = true;
    ctxParams.pooling_type    = LLAMA_POOLING_TYPE_MEAN;
    ctxParams.flash_attn      = true;
    ctxParams.offload_kqv     = true;
    ctxParams.no_kv_leftovers = true;

    m_ctx = llama_new_context_with_model(m_model, ctxParams);
    if (m_ctx == nullptr) {
        LOGe("EmbeddingEngine: failed to create llama_context for embeddings");
        n_embd = 0;
        return false;
    }

    LOGi("EmbeddingEngine initialized | n_embd=%d n_ctx=%d", n_embd, contextSize);
    return true;
}

/* -----------------------------------------------------------------------
 * embed()
 *
 * Converts a text string into a dense float vector through the LLM.
 *
 * Steps:
 *   1. Tokenise the input text (llama_tokenize with BOS, allow special).
 *   2. Build a single-sequence llama_batch from the token array.
 *   3. Call llama_encode to produce per-token hidden states.
 *   4. Read the pooled embedding from llama_get_embeddings_seq().
 *      (Mean pooling was configured in init() via params.pooling_type.)
 *   5. L2-normalise the vector for cosine-similarity with FAISS.
 *
 * Returns an empty vector on any failure (bad tokenisation, encode error).
 * ----------------------------------------------------------------------- */
std::vector<float> EmbeddingEngine::embed(const std::string& text) {
    if (m_ctx == nullptr || m_model == nullptr) {
        LOGe("EmbeddingEngine::embed — engine not initialised");
        return {};
    }

    std::vector<llama_token> tokens(n_embd);
    int nTokens = llama_tokenize(
        m_model,
        text.c_str(),
        text.size(),
        tokens.data(),
        static_cast<int>(tokens.size()),
        true,   // add_bos
        false   // special tokens
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

    // ---- 2. Build batch -----------------------------------------------
    llama_batch batch = llama_batch_get_one(tokens.data(), nTokens);
    // The batch's position array is already filled by the helper.

    // ---- 3. Encode ----------------------------------------------------
    if (llama_encode(m_ctx, batch) != 0) {
        LOGe("EmbeddingEngine::embed — llama_encode failed");
        return {};
    }

    // ---- 4. Read pooled embedding -------------------------------------
    const float* embData = llama_get_embeddings_seq(m_ctx, 0);
    if (embData == nullptr) {
        LOGe("EmbeddingEngine::embed — embedding data is null (seq 0)");
        return {};
    }

    std::vector<float> result(embData, embData + n_embd);

    // ---- 5. L2 normalise ----------------------------------------------
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
        LOG("EmbeddingEngine::embedBatch [%zu/%zu] — embedding...",
            i + 1, texts.size());
        results.push_back(embed(texts[i]));
    }

    return results;
}

/* -----------------------------------------------------------------------
 * dimension()
 *
 * Returns the model's native embedding dimension.  This value is used
 * by Kotlin to initialise the FAISS index with the correct vector size.
 * ----------------------------------------------------------------------- */
int EmbeddingEngine::dimension() const {
    return n_embd;
}

/* -----------------------------------------------------------------------
 * release()
 *
 * Destroys the embedding context and resets the stored model pointer
 * and dimension.  After a call to release(), the engine is in the same
 * state as before init() — embed() will return empty vectors.
 * ----------------------------------------------------------------------- */
void EmbeddingEngine::release() {
    if (m_ctx) {
        llama_free(m_ctx);
        m_ctx = nullptr;
    }
    m_model = nullptr;
    n_embd  = 0;
}

/* -----------------------------------------------------------------------
 * l2Normalize()
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
