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
        buildConfigField("String", "MODEL_FILENAME_EMBEDDING",project.properties["FILENAME_EMBEDDINGGEMMA"].toString())
        buildConfigField("String", "MODEL_FILENAME_MINICPM",project.properties["FILENAME_MINICPM_V2_Q4_KM"].toString())
        buildConfigField("String", "MODEL_FILENAME_MINICPM_MMPROJ", project.properties["FILENAME_MINICPM_MMPROJ"].toString())
        buildConfigField("String", "DEFAULT_SYSTEM_PROMPT", project.properties["DEFAULT_SYSTEM_PROMPT"].toString())
        buildConfigField("String", "HUGGINGFACE_API_KEY", project.properties["HUGGINGFACE_API_KEY"].toString())
        buildConfigField("String", "MCP_KEENABLE_API_KEY", project.properties["MCP_KEENABLE_API_KEY"].toString())

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