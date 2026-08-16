plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.userexec.soneme.recorder"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.userexec.soneme.recorder"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    val keystorePath = System.getenv("SONEME_KEYSTORE")
    val storePasswordValue = System.getenv("SONEME_STORE_PASSWORD")
    val keyPasswordValue = System.getenv("SONEME_KEY_PASSWORD")
    val releaseSigning = if (!keystorePath.isNullOrBlank() &&
        !storePasswordValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = storePasswordValue
            keyAlias = "soneme"
            keyPassword = keyPasswordValue
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
