package com.example.newandroidapp.vision

import com.example.newandroidapp.stability.SafetyGateManager

/**
 * 【螢幕文字擷取器 (`ScreenTextCapture`)】
 *
 * 從當前視覺管線報告中擷取畫面上「使用者看得到」的文字，依閱讀順序（由上到下、由左到右）整理成純文字。
 * 全程在本機處理，不連網、不寫入剪貼簿、不寫入日誌或記憶。
 *
 * 文字來源（依優先序）：
 * 1. 無障礙節點樹的 `text` 與 `contentDescription`（最快、最準）
 * 2. ROI 視覺 OCR 補漏節點（自繪畫面、圖片內文字）
 *
 * 隱私保護：
 * - 若畫面被判定為需要人工接管（驗證碼、銀行密碼、生物辨識等），一律拒絕擷取。
 * - 密碼輸入欄位的內容永遠不納入結果。
 * - 被上層遮擋而剔除的節點，以及本 App 自己的懸浮窗，不納入結果。
 */
object ScreenTextCapture {

    /** 本 App 自身的套件名稱，擷取時排除自己的懸浮球與面板文字。 */
    private const val OWN_PACKAGE = "com.localai.visionassistant"

    /** 同一水平列的 Y 座標容許誤差（像素），用於把同一行的文字合併排序。 */
    private const val ROW_TOLERANCE_PX = 24

    sealed class Result {
        /** 擷取成功。[text] 為整理後的完整文字，[lines] 為依閱讀順序的每一段。 */
        data class Captured(val text: String, val lines: List<String>) : Result()

        /** 因隱私保護拒絕擷取（例如驗證碼、銀行密碼畫面）。 */
        data class Blocked(val reason: String) : Result()

        /** 畫面上沒有可讀取的文字。 */
        object Empty : Result()
    }

    private data class Piece(val text: String, val top: Int, val left: Int)

    private const val CAPTURE_TTL_MS = 5 * 60_000L

    @Volatile
    private var lastCaptureInternal: Result.Captured? = null

    @Volatile
    private var lastCaptureTimestampMs: Long = 0L

    /** 最近一次成功擷取的結果（超過 5 分鐘自動過期清空；遇到敏感畫面時亦立即清空）。 */
    var lastCapture: Result.Captured?
        get() {
            val cur = lastCaptureInternal ?: return null
            if (System.currentTimeMillis() - lastCaptureTimestampMs > CAPTURE_TTL_MS) {
                lastCaptureInternal = null
                return null
            }
            return cur
        }
        private set(value) {
            lastCaptureInternal = value
            lastCaptureTimestampMs = if (value != null) System.currentTimeMillis() else 0L
        }

    fun capture(report: VisionPipelineReport?): Result {
        if (report == null) {
            lastCapture = null
            return Result.Empty
        }

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        if (handover != null && handover.first != SafetyGateManager.HandoverCategory.NONE) {
            lastCapture = null
            return Result.Blocked("這是${handover.first.displayLabel}畫面，為了保護你的隱私，我不讀取。")
        }

        val pieces = ArrayList<Piece>()

        for (node in report.stage1AllNodes) {
            if (node.isCulled) continue
            val raw = node.raw
            if (raw.packageName == OWN_PACKAGE) continue
            if (isPasswordLike(raw)) continue

            val bounds = node.visibleBoundingBox
            val text = raw.text.trim()
            val desc = raw.contentDescription.trim()
            if (text.isNotEmpty()) {
                pieces.add(Piece(text, bounds.top, bounds.left))
            }
            // 只有在沒有 text 或內容描述與文字不同時，才補上內容描述，避免重複
            if (desc.isNotEmpty() && !desc.equals(text, ignoreCase = true)) {
                pieces.add(Piece(desc, bounds.top, bounds.left))
            }
        }

        for (node in report.finalNodes) {
            if (node.sourceType != NodeSourceType.ROI_OCR_TEXT) continue
            if (node.packageName == OWN_PACKAGE) continue
            val label = cleanOcrLabel(node.label)
            if (label.isEmpty()) continue
            pieces.add(Piece(label, node.convergedBounds.top, node.convergedBounds.left))
        }

        val ordered = orderByReading(pieces)
        if (ordered.isEmpty()) {
            lastCapture = null
            return Result.Empty
        }

        val captured = Result.Captured(text = ordered.joinToString("\n"), lines = ordered)
        lastCapture = captured
        return captured
    }

    private fun isPasswordLike(raw: RawVisionNode): Boolean {
        if (raw.isPassword) return true
        val type = raw.inputType and 0xFFF
        if (type == 0x81 || type == 0x91 || type == 0xe1 || type == 0x12) return true
        val id = raw.viewIdResourceName.lowercase()
        return raw.isEditable && (id.contains("password") || id.contains("pwd"))
    }

    /** 移除 OCR 節點標籤上的 `[視覺OCR]`、`[~動態]` 等管線裝飾，只留文字本身。 */
    private fun cleanOcrLabel(label: String): String {
        return label
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .trim()
            .takeIf { it != "視覺文字項" }
            .orEmpty()
    }

    /** 依閱讀順序排序：先把 Y 座標接近的歸為同一行，行內由左到右；並去除重複內容。 */
    private fun orderByReading(pieces: List<Piece>): List<String> {
        if (pieces.isEmpty()) return emptyList()
        val byTop = pieces.sortedWith(compareBy({ it.top }, { it.left }))

        val rows = ArrayList<MutableList<Piece>>()
        for (piece in byTop) {
            val lastRow = rows.lastOrNull()
            if (lastRow != null && kotlin.math.abs(piece.top - lastRow.first().top) <= ROW_TOLERANCE_PX) {
                lastRow.add(piece)
            } else {
                rows.add(mutableListOf(piece))
            }
        }

        val seen = LinkedHashSet<String>()
        val result = ArrayList<String>()
        for (row in rows) {
            for (piece in row.sortedBy { it.left }) {
                val key = piece.text.lowercase()
                if (seen.add(key)) {
                    result.add(piece.text)
                }
            }
        }
        return result
    }

    /** 判斷一句話是不是在要求「擷取畫面文字」。 */
    fun isCaptureRequest(goal: String): Boolean {
        val q = goal.trim()
        if (q.isEmpty()) return false
        val verbs = listOf("擷取", "截取", "抓取", "讀取", "取得", "讀出", "顯示")
        val targets = listOf("螢幕文字", "畫面文字", "螢幕上的文字", "畫面上的文字", "螢幕上的字", "畫面上的字", "螢幕內容", "畫面內容")
        if (targets.any { q.contains(it) } && (verbs.any { q.contains(it) } || q.contains("文字"))) return true
        return q.contains("上寫什麼") && (q.contains("螢幕") || q.contains("畫面"))
    }

    /** 把擷取結果轉成要顯示給使用者的回覆文字（保留完整內容，最多 [maxChars] 字）。 */
    fun toReplyText(result: Result, maxChars: Int = 800): String {
        return when (result) {
            is Result.Blocked -> result.reason
            Result.Empty -> "這個畫面上讀不到文字。"
            is Result.Captured -> {
                val body = result.text
                if (body.length <= maxChars) body else body.take(maxChars) + "…（內容過長，已截斷）"
            }
        }
    }
}
