#pragma once
#include <string>
#include <vector>
#include "llama.h"

/**
 * @EmbeddingEngine generates dense vector embeddings from text.
 * Output vectors are L2-normalized so that FAISS IndexFlatIP
 * (inner product) yields cosine similarity values in [0, 1].
 */
class EmbeddingEngine {
public:
    /**
     * Initialise the embedding engine with its own model.
     * @param modelPath   Path to a GGUF embedding model (e.g. Gemma embedder).
     * @param contextSize Maximum token window for a single embed call.
     *                    Default 512 — most RAG queries fit easily.
     * @return true on success, false if model loading or context creation fails.
     */
    bool init(const char* modelPath, int contextSize = 512);

    /**
     * Embed a single text string into a dense float vector.
     * @param text  UTF-8 input text (e.g. a preprocessed user query).
     * @return      Float vector of dimension n_embd (2048 for LLaMA 3B/8B).
     *              Returns empty vector on failure.
     */
    std::vector<float> embed(const std::string& text);

    /**
     * Batch-embed multiple texts.
     * @param texts  Vector of input strings.
     * @return       Vector of embedding vectors, one per input.
     *               Failed entries return an empty vector.
     */
    std::vector<std::vector<float>> embedBatch(
        const std::vector<std::string>& texts);

    /**
     * Query the model's native embedding dimension.
     * Delegates to llama_n_embd(model).  Valid only after init().
     * @return Embedding dimension (e.g. 2048, 4096), or 0 if uninitialised.
     */
    int dimension() const;

    /**
     * Release all native resources.
     */
    void release();

private:
    llama_model*             m_model = nullptr;
    const struct llama_vocab* m_vocab = nullptr;
    llama_context*            m_ctx   = nullptr;
    int n_embd  = 0;
    int n_batch = 512;

    /**
     * L2-normalise a vector in-place.
     *
     * Each element is divided by sqrt(sum(vec[i]^2)).
     * Vectors with near-zero magnitude are left unchanged.
     */
    void l2Normalize(std::vector<float>& vec) const;
};
