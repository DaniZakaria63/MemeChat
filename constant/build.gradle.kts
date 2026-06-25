plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "fun.walawe.constant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {

        minSdk = 27
        buildConfigField("String", "TAG", "\"WALAWE_MODEL_PULL\"")
        buildConfigField("String", "MODEL_FILENAME_EMBEDDING", "\"${Secrets.get(project, "FILENAME_EMBEDDINGGEMMA")}\"")
        buildConfigField("String", "MODEL_FILENAME_MINICPM", "\"${Secrets.get(project, "FILENAME_MINICPM_V2_Q4_KM")}\"")
        buildConfigField("String", "MODEL_FILENAME_MINICPM_MMPROJ", "\"${Secrets.get(project, "FILENAME_MINICPM_MMPROJ")}\"")
        buildConfigField("String", "DEFAULT_SYSTEM_PROMPT", "\"${Secrets.get(project, "DEFAULT_SYSTEM_PROMPT")}\"")
        buildConfigField("String", "HUGGINGFACE_API_KEY", "\"${Secrets.get(project, "HUGGINGFACE_API_KEY")}\"")
        buildConfigField("String", "MCP_KEENABLE_API_KEY", "\"${Secrets.get(project, "MCP_KEENABLE_API_KEY")}\"")

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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config.ktx)
    implementation(libs.firebase.analytics)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
