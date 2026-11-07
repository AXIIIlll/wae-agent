plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.newandroidapp"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.localai.visionassistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "2.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden")
            }
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/release-upload-key.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "LocalAi2026!"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "upload_key"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "LocalAi2026!"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        noCompress += listOf("cact", "bin", "gguf")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseKeystore = rootProject.file("keystore/release-upload-key.jks")
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // 100% 純原創自研代碼架構：零第三方 AI/OCR/JSON/UI 套件依賴，僅使用 Kotlin 標準協程與單元測試框架
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

// 每次執行 assembleDebug / assembleRelease / bundleRelease 後，自動將最新 APK 與 Google Play AAB 同步至專案根目錄、E:\apk\、Downloads
tasks.register("syncBuiltApk") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val eApkDir = file("E:/apk")
        if (!eApkDir.exists()) eApkDir.mkdirs()
        val userDownloadsDir = file("C:/Users/User/Downloads")

        val debugApk = file("$buildDir/outputs/apk/debug/app-debug.apk")
        if (debugApk.exists()) {
            debugApk.copyTo(file("${rootProject.projectDir}/NewAndroidApp.apk"), overwrite = true)
            debugApk.copyTo(file("E:/apk/NewAndroidApp.apk"), overwrite = true)
            if (userDownloadsDir.exists()) {
                debugApk.copyTo(file("C:/Users/User/Downloads/NewAndroidApp.apk"), overwrite = true)
            }
        }

        val releaseApk = file("$buildDir/outputs/apk/release/app-release.apk")
        if (releaseApk.exists()) {
            releaseApk.copyTo(file("${rootProject.projectDir}/NewAndroidApp-release.apk"), overwrite = true)
            releaseApk.copyTo(file("E:/apk/NewAndroidApp-release.apk"), overwrite = true)
            if (userDownloadsDir.exists()) {
                releaseApk.copyTo(file("C:/Users/User/Downloads/NewAndroidApp-release.apk"), overwrite = true)
            }
        }

        val releaseAab = file("$buildDir/outputs/bundle/release/app-release.aab")
        if (releaseAab.exists()) {
            releaseAab.copyTo(file("${rootProject.projectDir}/NewAndroidApp-release.aab"), overwrite = true)
            releaseAab.copyTo(file("E:/apk/NewAndroidApp-release.aab"), overwrite = true)
            if (userDownloadsDir.exists()) {
                releaseAab.copyTo(file("C:/Users/User/Downloads/NewAndroidApp-release.aab"), overwrite = true)
            }
        }
    }
}

tasks.matching { it.name in setOf("assembleDebug", "assembleRelease", "bundleRelease") }.configureEach {
    finalizedBy("syncBuiltApk")
}
