// spamshield — the CORE Android library (AAR) of SMS Spam Shield: the public `SpamShield`
// facade over the `spam_shield` Rust engine (offline L0 political heuristic + L2/L3 feeds +
// crowd feed), plus the self-starting refresh worker. The optional L1 AI layer is a separate
// module (`:spamshield-ai`). See README "Quick start" and src/main/java/com/spamshield/.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spamshield"
    compileSdk = 34 // matches AGP 8.5.2

    defaultConfig {
        minSdk = 26
        // The engine's UniFFI-generated Kotlin bindings expect the native lib on these ABIs;
        // build it with cargo-ndk and drop the .so files into src/main/jniLibs/<abi>/.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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
    // Self-starting periodic feed/crowd refresh (no per-boot manual step).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // UniFFI runtime: the generated `uniffi.spam_shield` bindings call the native lib via JNA.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // NOTE: the UniFFI-generated Kotlin bindings (`uniffi/spam_shield/…`) and the compiled
    // `libspam_shield.so` (per ABI) are produced from the Rust crate:
    //   cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
    //   cargo run --bin uniffi-bindgen -- generate --library <libspam_shield.so> \
    //       --language kotlin --out-dir src/main/java
    // and are placed under src/main/java (bindings) + src/main/jniLibs/<abi> (.so). They are
    // not committed here (build artifacts); see the engine README.
}
