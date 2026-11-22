package com.wae.agent.core.predicate

import android.content.Context
import java.io.File

/**
 * **模型複本回收**。
 *
 * 每個 ONNX loader 都會把 asset 複製到私有目錄才能載入（ORT Java 只吃檔案路徑）。
 * 載入完成後檔案就用不到了，但沒人刪 → 實測佔了 191 MB。
 *
 * 策略：
 *  - 啟動時把「上次留下的」複本清掉（不是正在用的，因為載入是即時的）
 *  - loader 需要時會自己重新複製（`ensureAssetCopy` 有 size 校驗）
 *  - 只清模型檔，不動詞表與資料
 */
object ModelCache {

    /** 大模型才值得回收（> 5 MB）。 */
    private const val MIN_SIZE = 5L * 1024 * 1024

    /**
     * 清掉殘留的模型複本。
     * @return 回收的位元組數
     */
    fun sweep(context: Context): Long {
        var freed = 0L
        val dirs = listOf(context.filesDir, File(context.filesDir, "models"))
        for (d in dirs) {
            val files = d.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                if (!f.name.endsWith(".onnx")) continue
                if (f.length() < MIN_SIZE) continue
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    freed += len
                }
            }
        }
        android.util.Log.i("WAE_MODEL", "sweep freed=${freed / 1024 / 1024} MB")
        return freed
    }
}
