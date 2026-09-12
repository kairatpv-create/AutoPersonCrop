plugins {
    id("com.android.application")
}

android {
    namespace = "kz.autopersoncrop"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "kz.autopersoncrop"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Keep the first Play build transparent and easy to diagnose while device testing is ongoing.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/NOTICE", "META-INF/LICENSE")
    }

    androidResources {
        noCompress += "tflite"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.google.ai.edge.litert:litert:2.1.5")
}
