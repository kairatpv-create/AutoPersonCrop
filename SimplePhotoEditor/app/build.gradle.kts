plugins {
    id("com.android.application")
}

android {
    namespace = "kz.kairat.simplephotoeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "kz.kairat.simplephotoeditor"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
