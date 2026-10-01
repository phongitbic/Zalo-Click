plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "vn.quickquote.zalo"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.quickquote.zalo"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "2.7.2"
    }

    // Keystore cố định: các bản build sau cài đè được lên bản trước, không phải gỡ app
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ký bằng debug key để cài trực tiếp (dùng nội bộ, không đưa lên Play Store)
            signingConfig = signingConfigs.getByName("debug")
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
