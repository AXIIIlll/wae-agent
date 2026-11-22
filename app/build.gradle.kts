import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wae.agent"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.wae.agent"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "3.0.0-wae"

        ndk {
            // 真機為 arm64；加 -PwaeIncludeX86=true 可建 x86_64 供 emulator 驗證
            val includeX86 = (project.findProperty("waeIncludeX86") as? String) == "true"
            abiFilters += if (includeX86) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                // llama.cpp 原始碼位置（不進 repo；可用 -PwaeLlamaDir 覆寫）
                val llamaDir = (project.findProperty("waeLlamaDir") as? String)
                    ?: System.getenv("WAE_LLAMA_DIR")
                    ?: "E:/WAE/temp/llama.cpp"
                arguments += listOf("-DANDROID_STL=c++_static", "-DLLAMA_DIR=$llamaDir")
            }
        }
    }

    signingConfigs {
        create("release") {
            // 金鑰與密碼不進 repo：從 local.properties（gitignored）或環境變數讀取。
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun secret(key: String, env: String): String? =
                (System.getenv(env) ?: props.getProperty(key))?.takeIf { it.isNotBlank() }
            val ksPath = secret("wae.keystore.path", "WAE_KEYSTORE_PATH")
            val ksFile = ksPath?.let { File(it) }
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = secret("wae.keystore.storePassword", "WAE_STORE_PASSWORD")
                keyAlias = secret("wae.keystore.keyAlias", "WAE_KEY_ALIAS") ?: "upload_key"
                keyPassword = secret("wae.keystore.keyPassword", "WAE_KEY_PASSWORD")
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
        noCompress += listOf("bin", "onnx", "gguf")
    }

    // 兩個 flavor：
    //   full = 內建 Qwen3-0.6B GGUF（assets/models/…gguf 放在 src/full/）→ 大 APK，Agent 可用
    //   lite = 不含 GGUF（~212MB）→ 規則路徑、語音、OCR 全部可用，只有「目標式 LLM Agent」會回報不可用
    // 預設是 full，所以既有的 `assembleDebug` / `assembleRelease` 行為不變；
    // lite 用 `assembleLiteDebug` / `assembleLiteRelease` 建置。
    flavorDimensions += "model"
    productFlavors {
        create("full") {
            dimension = "model"
            isDefault = true
        }
        create("lite") {
            dimension = "model"
        }
    }

    buildTypes {
        debug {
            // 真機上已安裝的是 release 簽章的舊版；要「保留 App 資料」覆蓋安裝時用
            //   -PwaeDebugWithReleaseKey=true
            // 這樣 APK 仍是 debuggable（可收 grounding trace、可用 debug_command），
            // 但簽章與既有安裝一致，adb install -r 不會 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
            if (project.hasProperty("waeDebugWithReleaseKey")) {
                val rel = signingConfigs.getByName("release")
                if (rel.storeFile != null) signingConfig = rel
            }
        }
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) {
                rel
            } else {
                logger.warn("WARNING: release keystore 未設定（local.properties wae.keystore.* 或環境變數），改用 debug key 簽章")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 新加入的簡繁/同音正規化會載入 26k 行的 OpenCC 表（TextNormalizer），
        // 加上 Robolectric 的 Android 資源，預設 512MB heap 會 OOM（VoiceRobolectricTest 已踩到）。
        unitTests.all { it.maxHeapSize = "2g" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Gen-5 純 JVM 核心（感知／型別／效果／手段／執行／記憶／安全）。
    // 依賴方向固定：app → core，core 不得反向依賴 Android（由 CorePurityTest 守門）。
    implementation(project(":core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 端側真實推理引擎（MiniLM 語意編碼；後續 NanoDet / PaddleOCR 共用同一引擎）
    // 注意：1.21.0 起官方 so 才符合 Android 15 的 16KB page size 對齊要求，
    // 用 1.19.x 會被系統判定「16 KB ELF 對齊檢查失敗」而彈窗阻止執行。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0") // 全域共用一份 ORT（16KB 對齊）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    // 無需裝置的 Android 依賴測試（assets / Context 行為）
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    // P1 #9：instrumentation 測試（需裝置/模擬器執行；CI 可先編譯）
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.register("syncBuiltApk") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val eApkDir = file("E:/apk")
        if (!eApkDir.exists()) eApkDir.mkdirs()
        val userDownloadsDir = file("C:/Users/User/Downloads")

        fun safeOverwriteCopy(src: File, dst: File) {
            try {
                dst.parentFile?.mkdirs()
                src.inputStream().use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val debugApk = file("$buildDir/outputs/apk/debug/app-debug.apk")
        if (debugApk.exists()) {
            safeOverwriteCopy(debugApk, file("${rootProject.projectDir}/WAE.apk"))
            safeOverwriteCopy(debugApk, file("E:/apk/WAE.apk"))
            if (userDownloadsDir.exists()) {
                safeOverwriteCopy(debugApk, file("C:/Users/User/Downloads/WAE.apk"))
            }
        }

        val releaseApk = file("$buildDir/outputs/apk/release/app-release.apk")
        if (releaseApk.exists()) {
            safeOverwriteCopy(releaseApk, file("${rootProject.projectDir}/WAE-release.apk"))
            safeOverwriteCopy(releaseApk, file("E:/apk/WAE-release.apk"))
        }
    }
}


// P1 #10：每次 release 建置都保留一份帶版本號的 APK，方便回滾
tasks.register("archiveReleaseApk") {
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (apk.exists()) {
            val dir = file("E:/WAE/apk-history").apply { mkdirs() }
            val ver = android.defaultConfig.versionName ?: "unknown"
            apk.copyTo(File(dir, "WAE-$ver.apk"), overwrite = true)
            println("archived APK: WAE-$ver.apk")
        }
    }
}

tasks.matching { it.name in setOf("assembleDebug", "assembleRelease") }.configureEach {
    finalizedBy("syncBuiltApk")
}
