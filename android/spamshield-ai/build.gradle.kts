// spamshield-ai — the optional L1 AI layer of SMS Spam Shield (an Android library / AAR).
// Two independent, user-selectable AI backends: on-device Gemini Nano (ML Kit GenAI) and a
// developer-configured cloud LLM. See src/main/java/com/spamshield/ai/ and docs/AI_LAYER.md.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spamshield.ai"
    compileSdk = 34 // matches AGP 8.5.2 (compileSdk 35 would need AGP 8.6+)

    defaultConfig {
        minSdk = 26 // required by the ML Kit GenAI Prompt API (Gemini Nano)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // On-device Gemini Nano (the model itself is provisioned by AICore, not bundled here).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    // NOTE: the CloudAiClassifier uses only java.net + org.json (both bundled on Android) —
    // no HTTP/JSON third-party dependency is added.
}
