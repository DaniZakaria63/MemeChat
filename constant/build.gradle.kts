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

        buildConfigField("String", "BASE_URL",project.properties["BASE_URL"].toString())
        buildConfigField("String", "FILENAME_QWEN2_VL_MODEL", project.properties["FILENAME_QWEN2_VL_MODEL"].toString())
        buildConfigField("String", "FILENAME_QWEN2_VL_MMPROJ", project.properties["FILENAME_QWEN2_VL_MMPROJ"].toString())
        buildConfigField("String", "BASENAME_QWEN2_VL_MODEL",project.properties["BASENAME_QWEN2_VL_MODEL"].toString())

        buildConfigField("String", "DEFAULT_SYSTEM_PROMPT", project.properties["DEFAULT_SYSTEM_PROMPT"].toString())

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