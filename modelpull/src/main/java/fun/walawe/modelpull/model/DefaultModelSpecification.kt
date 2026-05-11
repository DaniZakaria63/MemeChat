package `fun`.walawe.modelpull.model

import `fun`.walawe.modelpull.BuildConfig

const val DEFAULT_MODEL_DOWNLOADER_URI = BuildConfig.BASE_URL.plus(BuildConfig.QWEN_MODEL_FILENAME)
const val DEFAULT_LAST_MODEL_NAME = BuildConfig.QWEN_MODEL_FILENAME
const val EXPECTED_MODEL_BASENAME = "Qwen.Qwen3-VL-Embedding-2B.Q2_K"
const val DEFAULT_VERSION_MODEL = 1
val DEFAULT_MODEL_VL_DIMENSION = Pair(224, 224)
const val DEFAULT_HARDWARE_ACCELERATOR = 0
const val DEFAULT_DEVELOPER_MODE = false
const val DEFAULT_IS_MODEL_DOWNLOADED = false
const val DEFAULT_LAST_MODEL_SYNC_TIME = 0L
const val DEFAULT_LAST_MODEL_PATH = ""
const val DEFAULT_LAST_MODEL_FILENAME = ""
