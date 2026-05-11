plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fun.walawe.memechat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "fun.walawe.memechat"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "DEFAULT_SYSTEM_PROMPT", project.properties["DEFAULT_SYSTEM_PROMPT"].toString())
        buildConfigField("String", "DEFAULT_MEDIA_MARKER", project.properties["DEFAULT_MEDIA_MARKER"].toString())

        testInstrumentationRunner = "fun.walawe.memechat.CustomTestRunner"
        testInstrumentationRunnerArguments["targetApp"] = "dagger.hilt.android.testing.HiltTestApplication"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(project(":modelpull"))
    implementation(project(":memelm"))

    implementation(libs.androidx.coroutine)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.hilt)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.google.fonts)
    implementation(libs.google.accompanist.permission)
    implementation(libs.coil.compose)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.material3)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.jetbrains.coroutine.test)
}