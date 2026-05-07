plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fun.walawe.modelpull"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "TAG", "\"WALAWE_MODEL_PULL\"")
        buildConfigField("String", "BASE_URL",project.properties["BASE_URL"].toString())
        buildConfigField("String", "QWEN_MODEL_FILENAME", project.properties["FILENAME_QWEN3_VL_EMBEDDING"].toString())
        buildConfigField("String", "PALIGEMMA_MODEL_FILENAME",project.properties["FILENAME_PALIGEMMA_MIX_224"].toString())

        testInstrumentationRunner = "fun.walawe.modelpull.CustomTestRunner"
        testInstrumentationRunnerArguments["targetApp"] = "dagger.hilt.android.testing.HiltTestApplication"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.scalars)
    implementation(libs.retrofit.serialization.json)
    // Timber
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.jetbrains.coroutine.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.jetbrains.coroutine.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}