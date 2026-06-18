package `fun`.walawe.local.data

data class PreprocessSentence(
    val original: String,
    val normalized: String,
    val sentences: List<String>,
    val tokens: List<String>,
)
