plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobileremote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobileremote.app"
        minSdk = 30          // Android 11+ (S21 FE และ S25+ รองรับ)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ใช้ debug key เพื่อให้ติดตั้งเองได้ทันที
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

// ไม่ใช้ไลบรารีภายนอก ใช้เฉพาะ Android SDK + Kotlin stdlib
dependencies { }
