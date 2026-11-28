// Gen-5 純 JVM 核心模組：不得依賴 Android。
//
// 為什麼用 buildscript classpath 而不是 plugins { kotlin("jvm") }：
// 這台機器的 Gradle 快取（E:\WAE\gradle-home）只有 kotlin-android 的外掛標記，
// 沒有 org.jetbrains.kotlin.jvm 的 marker；離線環境下 plugins 區塊會解析失敗。
// kotlin-gradle-plugin:1.9.24 本體已在快取，用 buildscript + apply 可完全離線。
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "java-library")

dependencies {
    // Kotlin DSL 的型別安全 accessor 只在 plugins {} 區塊套用時產生；
    // 這裡用 apply(plugin=…)，所以要用字串形式的 add。
    "testImplementation"("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // 與 app 模組一致：測試需要較大 heap（26k 行語料在部分測試會載入）
    maxHeapSize = "1g"
}
