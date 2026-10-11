plugins {
    id("com.android.application")
}

android {
    namespace = "app.ft.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ft.server"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
