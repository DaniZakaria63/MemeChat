plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "fun.walawe.memechat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        testInstrumentationRunnerArguments += mapOf()
        applicationId = "fun.walawe.memechat"
        minSdk = 27
        targetSdk = 36
        versionCode = Secrets.versionCode(project)
        versionName = Secrets.versionName(project)

        testInstrumentationRunner = "fun.walawe.memechat.CustomTestRunner"
        testInstrumentationRunnerArguments["targetApp"] = "dagger.hilt.android.testing.HiltTestApplication"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/meme_chat_upload.keystore")
            storePassword = Secrets.get(project, "KEYSTORE_PASSWORD")
            keyAlias = Secrets.get(project, "KEY_ALIAS")
            keyPassword = Secrets.get(project, "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            pickFirsts += listOf("**/libomp.so")
        }
    }
}

dependencies {
    implementation(project(":constant"))
    implementation(project(":modelpull"))
    implementation(project(":memelm"))
    implementation(project(":local"))
    implementation(project(":mcp"))

    implementation(libs.androidx.coroutine)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.hilt)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
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
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui.material3)

    implementation(libs.androidx.google.fonts)
    implementation(libs.google.accompanist.permission)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.material3)

    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.jetbrains.coroutine.test)
}
