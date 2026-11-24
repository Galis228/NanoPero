plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nanopero.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nanopero.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.8"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")

    // Gemini Nano через ML Kit GenAI Prompt API
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    // Локальная модель-файл (Gemma / Qwen .task) через MediaPipe
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    // Модели .litertlm (Gemma 4, Qwen 3) через LiteRT-LM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
}
