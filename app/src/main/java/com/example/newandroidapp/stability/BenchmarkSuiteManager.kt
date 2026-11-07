package com.example.newandroidapp.stability

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.newandroidapp.ai.InstalledAppLauncher
import com.example.newandroidapp.ai.OpenSourceAiDatabaseManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 【第 11 項】大規模可自訂穩定度基準測試集管理器 (BenchmarkSuiteManager)
 *
 * 特點：
 * 1. 內建 38 項豐富基礎測試集（涵蓋各類系統與日常 App 場景），並自動融合手機上所有真實已安裝 App。
 * 2. 支援使用者完全自訂：可新增自訂測試指令、刪除單項、一鍵匯入全機已安裝 App、或重置回預設基礎測試集（JSON 持久化保存）。
 * 3. 一鍵啟動「至少 5 分鐘（300 秒）＋ 至少開啟 30 種以上 App/系統模組」的連續閉環壓力測試，實時統計成功率、平均耗時與記憶體穩定度。
 */
object BenchmarkSuiteManager {

    private const val SUITE_FILE_NAME = "custom_benchmark_suite.json"
    const val DEFAULT_MIN_DURATION_SECONDS = 300 // 至少 5 分鐘 (300 秒)
    const val DEFAULT_MIN_APPS_TARGET = 30       // 至少 30 種以上 App / 模組

    data class BenchmarkTestCase(
        val id: String,
        val title: String,
        val targetAppHint: String,
        val commandGoal: String,
        val fallbackSystemIntentAction: String? = null,
        val enabled: Boolean = true,
        val isCustomUserAdded: Boolean = false
    )

    data class BenchmarkLiveProgress(
        val isRunning: Boolean = false,
        val elapsedSeconds: Int = 0,
        val targetDurationSeconds: Int = DEFAULT_MIN_DURATION_SECONDS,
        val distinctAppsOpenedCount: Int = 0,
        val targetAppsCount: Int = DEFAULT_MIN_APPS_TARGET,
        val totalCasesCompleted: Int = 0,
        val passedCasesCount: Int = 0,
        val currentCaseIndex: Int = 0,
        val totalSuiteSize: Int = 0,
        val currentCaseTitle: String = "",
        val currentAppLabel: String = "",
        val avgVisionLatencyMs: Long = 0L,
        val avgAiLatencyMs: Long = 0L,
        val usedRamMb: Int = 0,
        val finalSummaryReport: String = ""
    ) {
        fun formatElapsedMmSs(): String {
            val m = elapsedSeconds / 60
            val s = elapsedSeconds % 60
            val tm = targetDurationSeconds / 60
            val ts = targetDurationSeconds % 60
            return "%02d:%02d / %02d:%02d".format(m, s, tm, ts)
        }
    }

    private val suiteCases = CopyOnWriteArrayList<BenchmarkTestCase>()
    @Volatile
    private var initialized = false

    @Volatile
    var latestProgress: BenchmarkLiveProgress = BenchmarkLiveProgress()

    private const val RUN_MARKER_FILE = "benchmark_run_in_progress.marker"

    /** 壓測開始時寫入標記；正常結束會刪除。若下次開啟 App 時標記還在，代表上次壓測被崩潰或系統殺掉而中斷。 */
    fun markRunStart(context: Context) {
        runCatching { File(context.filesDir, RUN_MARKER_FILE).writeText(System.currentTimeMillis().toString()) }
    }

    fun markRunFinish(context: Context) {
        runCatching { File(context.filesDir, RUN_MARKER_FILE).delete() }
    }

    fun wasLastRunInterrupted(context: Context): Boolean {
        return runCatching { File(context.filesDir, RUN_MARKER_FILE).exists() }.getOrDefault(false)
    }

    /**
     * 內建 38 個基礎測試集（涵蓋不同 App 與各類 UI 結構：長列表、開關、搜尋框、網格、Canvas、子設定頁等）
     */
    private val DEFAULT_FOUNDATION_CASES = listOf(
        BenchmarkTestCase("base_01", "1. 設定首頁與列表捲動", "設定", "打開 設定 然後 下滑", Settings.ACTION_SETTINGS),
        BenchmarkTestCase("base_02", "2. 網路與 Wi-Fi 設定", "設定", "打開 設定 點擊 網路", Settings.ACTION_WIRELESS_SETTINGS),
        BenchmarkTestCase("base_03", "3. 藍牙連線與裝置掃描", "設定", "打開 設定 點擊 藍牙", Settings.ACTION_BLUETOOTH_SETTINGS),
        BenchmarkTestCase("base_04", "4. 顯示與螢幕亮度設定", "設定", "打開 設定 點擊 顯示", Settings.ACTION_DISPLAY_SETTINGS),
        BenchmarkTestCase("base_05", "5. 音效與震動設定", "設定", "打開 設定 點擊 音效", Settings.ACTION_SOUND_SETTINGS),
        BenchmarkTestCase("base_06", "6. 電池與耗電量資訊", "設定", "打開 設定 點擊 電池", Intent.ACTION_POWER_USAGE_SUMMARY),
        BenchmarkTestCase("base_07", "7. 儲存空間狀態檢視", "設定", "打開 設定 點擊 儲存空間", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        BenchmarkTestCase("base_08", "8. 應用程式管理列表", "設定", "打開 設定 點擊 應用程式", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        BenchmarkTestCase("base_09", "9. 安全性與螢幕鎖定", "設定", "打開 設定 點擊 安全性", Settings.ACTION_SECURITY_SETTINGS),
        BenchmarkTestCase("base_10", "10. 隱私權管理中心", "設定", "打開 設定 點擊 隱私", Settings.ACTION_PRIVACY_SETTINGS),
        BenchmarkTestCase("base_11", "11. 定位服務設定", "設定", "打開 設定 點擊 定位", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        BenchmarkTestCase("base_12", "12. 無障礙設定列表", "設定", "打開 設定 點擊 無障礙", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        BenchmarkTestCase("base_13", "13. 語言與輸入法設定", "設定", "打開 設定 點擊 語言", Settings.ACTION_INPUT_METHOD_SETTINGS),
        BenchmarkTestCase("base_14", "14. 日期與時間設定", "設定", "打開 設定 點擊 日期", Settings.ACTION_DATE_SETTINGS),
        BenchmarkTestCase("base_15", "15. 關於手機與版本資訊", "設定", "打開 設定 點擊 關於手機", Settings.ACTION_DEVICE_INFO_SETTINGS),
        BenchmarkTestCase("base_16", "16. 開發人員選項檢視", "設定", "打開 設定 然後 下滑", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        BenchmarkTestCase("base_17", "17. 帳號與同步設定", "設定", "打開 設定 點擊 帳號", Settings.ACTION_SYNC_SETTINGS),
        BenchmarkTestCase("base_18", "18. 預設應用程式設定", "設定", "打開 設定 點擊 預設", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        BenchmarkTestCase("base_19", "19. 通知存取與狀態設定", "設定", "打開 設定 點擊 通知", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        BenchmarkTestCase("base_20", "20. 省電與電池最佳化清單", "設定", "打開 設定 點擊 電池最佳化", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        BenchmarkTestCase("base_21", "21. Chrome / 瀏覽器啟動與視覺解析", "Chrome", "打開 Chrome 然後 下滑"),
        BenchmarkTestCase("base_22", "22. YouTube 影音首頁與搜尋框定位", "YouTube", "打開 YouTube 然後 下滑"),
        BenchmarkTestCase("base_23", "23. Google Play 商店列表折疊測試", "Play 商店", "打開 Play 商店 然後 下滑"),
        BenchmarkTestCase("base_24", "24. Google 地圖 Canvas 雙軌補盲測試", "地圖", "打開 地圖"),
        BenchmarkTestCase("base_25", "25. Gmail 郵件列表掃描測試", "Gmail", "打開 Gmail 然後 下滑"),
        BenchmarkTestCase("base_26", "26. 相簿 / Google 相片網格測試", "相簿", "打開 相簿"),
        BenchmarkTestCase("base_27", "27. 相機取景窗與圖示拓樸辨識", "相機", "打開 相機"),
        BenchmarkTestCase("base_28", "28. 時鐘與鬧鐘分頁切換測試", "時鐘", "打開 時鐘"),
        BenchmarkTestCase("base_29", "29. 日曆月曆視圖解析測試", "日曆", "打開 日曆"),
        BenchmarkTestCase("base_30", "30. 計算機按鈕熱區收斂測試", "計算機", "打開 計算機"),
        BenchmarkTestCase("base_31", "31. 電話撥號盤視覺掃描測試", "電話", "打開 電話"),
        BenchmarkTestCase("base_32", "32. 聯絡人列表檢視與捲動", "聯絡人", "打開 聯絡人 然後 下滑"),
        BenchmarkTestCase("base_33", "33. 訊息 / 簡訊對話列表解析", "訊息", "打開 訊息"),
        BenchmarkTestCase("base_34", "34. 檔案管理 / Files 儲存空間瀏覽", "檔案", "打開 檔案 然後 下滑"),
        BenchmarkTestCase("base_35", "35. 音樂 / YT Music 播放介面解析", "音樂", "打開 音樂"),
        BenchmarkTestCase("base_36", "36. Google 搜尋首頁與輸入框定位", "Google", "打開 Google"),
        BenchmarkTestCase("base_37", "37. 跨App聯動：Gmail擷取地鐵站至Google地圖規劃路線", "Gmail", "先去Gmail找到一封最近提到聚餐地點的信，把地鐵抓出來，然後直接打開Google地圖搜尋並規劃路線"),
        BenchmarkTestCase("base_38", "38. 跨App聯動：Chrome查匯率並寫入備忘錄", "Chrome", "去Chrome查今日美金匯率，然後存到備忘錄")
    )

    /**
     * 執行 12 萬筆百科與 7 大開源數據庫 + 跨 App 場景聯動之實機延遲與準確率驗證
     */
    fun runOpenSourceDatabaseAndCrossAppBenchmark(
        context: Context? = null
    ): OpenSourceAiDatabaseManager.DatabaseAndCrossAppBenchmarkReport {
        return OpenSourceAiDatabaseManager.runDatabaseAndCrossAppBenchmark(context)
    }

    fun ensureLoaded(context: Context): List<BenchmarkTestCase> {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    loadFromDiskOrInitDefault(context)
                    initialized = true
                }
            }
        }
        return suiteCases.toList()
    }

    private fun loadFromDiskOrInitDefault(context: Context) {
        val file = File(context.filesDir, SUITE_FILE_NAME)
        if (file.exists() && file.length() > 10L) {
            val loaded = runCatching {
                val arr = JSONArray(file.readText())
                val list = ArrayList<BenchmarkTestCase>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        BenchmarkTestCase(
                            id = obj.optString("id", "case_$i"),
                            title = obj.optString("title", "測試項 #${i + 1}"),
                            targetAppHint = obj.optString("targetAppHint", ""),
                            commandGoal = obj.optString("commandGoal", ""),
                            fallbackSystemIntentAction = obj.optString("fallbackSystemIntentAction", "").takeIf { it.isNotBlank() },
                            enabled = obj.optBoolean("enabled", true),
                            isCustomUserAdded = obj.optBoolean("isCustomUserAdded", false)
                        )
                    )
                }
                list
            }.getOrNull()

            if (!loaded.isNullOrEmpty()) {
                suiteCases.clear()
                suiteCases.addAll(loaded)
                autoMergeInstalledDeviceApps(context, saveAfter = false)
                return
            }
        }

        suiteCases.clear()
        suiteCases.addAll(DEFAULT_FOUNDATION_CASES)
        autoMergeInstalledDeviceApps(context, saveAfter = true)
    }

    /**
     * 自動掃描手機上所有已安裝的可啟動 App，將尚未在測試集內的真實 App 加入測試集，確保輕鬆超過 30+ 種真實 App
     */
    fun autoMergeInstalledDeviceApps(context: Context, saveAfter: Boolean = true): Int {
        val installedApps = InstalledAppLauncher.getInstalledLaunchableApps(context, forceRefresh = true)
        val existingHints = suiteCases.map { it.targetAppHint.lowercase().trim() }.toMutableSet()
        var added = 0

        for (app in installedApps) {
            val norm = app.label.lowercase().trim()
            if (norm.isNotEmpty() && !existingHints.contains(norm) && !existingHints.contains(app.packageName.lowercase())) {
                existingHints.add(norm)
                val idx = suiteCases.size + 1
                suiteCases.add(
                    BenchmarkTestCase(
                        id = "dev_app_${app.packageTail}_$idx",
                        title = "$idx. [本機App] ${app.label} 視覺與互動測試",
                        targetAppHint = app.label,
                        commandGoal = "打開 ${app.label} 然後 下滑",
                        fallbackSystemIntentAction = null,
                        enabled = true,
                        isCustomUserAdded = false
                    )
                )
                added++
            }
        }
        if (saveAfter && added > 0) {
            saveToDisk(context)
        }
        return added
    }

    fun addCustomTestCase(
        context: Context,
        customTitle: String,
        targetAppHint: String,
        commandGoal: String
    ): BenchmarkTestCase {
        ensureLoaded(context)
        val idx = suiteCases.size + 1
        val cleanGoal = commandGoal.trim().ifEmpty { "打開 $targetAppHint 然後 下滑" }
        val cleanApp = targetAppHint.trim().ifEmpty {
            cleanGoal.removePrefix("打開").removePrefix("開啟").trim().substringBefore(" ")
        }
        val cleanTitle = customTitle.trim().ifEmpty { "$idx. [自訂] $cleanGoal" }

        val newCase = BenchmarkTestCase(
            id = "custom_${System.currentTimeMillis()}",
            title = cleanTitle,
            targetAppHint = cleanApp,
            commandGoal = cleanGoal,
            enabled = true,
            isCustomUserAdded = true
        )
        // 新增的自訂測試排在最前面優先執行
        suiteCases.add(0, newCase)
        saveToDisk(context)
        return newCase
    }

    fun removeTestCase(context: Context, id: String) {
        ensureLoaded(context)
        suiteCases.removeAll { it.id == id }
        saveToDisk(context)
    }

    fun resetToDefaultSuite(context: Context): List<BenchmarkTestCase> {
        suiteCases.clear()
        suiteCases.addAll(DEFAULT_FOUNDATION_CASES)
        autoMergeInstalledDeviceApps(context, saveAfter = true)
        return suiteCases.toList()
    }

    private fun saveToDisk(context: Context) {
        runCatching {
            val arr = JSONArray()
            for (c in suiteCases) {
                val obj = JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("targetAppHint", c.targetAppHint)
                    put("commandGoal", c.commandGoal)
                    put("fallbackSystemIntentAction", c.fallbackSystemIntentAction ?: "")
                    put("enabled", c.enabled)
                    put("isCustomUserAdded", c.isCustomUserAdded)
                }
                arr.put(obj)
            }
            File(context.filesDir, SUITE_FILE_NAME).writeText(arr.toString(2))
        }
    }
}
