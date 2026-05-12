plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "fun.walawe.constant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "TAG", "\"WALAWE_MODEL_PULL\"")
        buildConfigField("String", "BASE_URL",project.properties["BASE_URL"].toString())
        buildConfigField("String", "MODEL_FILENAME_QWEN", project.properties["FILENAME_QWEN3_VL_EMBEDDING"].toString())
        buildConfigField("String", "MODEL_FILENAME_PALIGEMMA",project.properties["FILENAME_PALIGEMMA_MIX_224"].toString())
        buildConfigField("String", "MODEL_FILENAME_MINICPM",project.properties["FILENAME_MINICPM_V2_Q4_KM"].toString())
        buildConfigField("String", "BASENAME_QWEN3_VL_MODEL",project.properties["BASENAME_QWEN3_VL_MODEL"].toString())
        buildConfigField("String", "BASENAME_MINICPM_V2_MODEL",project.properties["BASENAME_MINICPM_V2_MODEL"].toString())

        buildConfigField("String", "DEFAULT_SYSTEM_PROMPT", project.properties["DEFAULT_SYSTEM_PROMPT"].toString())
        buildConfigField("String", "DEFAULT_MEDIA_MARKER", project.properties["DEFAULT_MEDIA_MARKER"].toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}