package com.wae.core.memory

import java.security.MessageDigest

/**
 * 約束（反例）的可攜格式（G9）。
 *
 * 為什麼放 core：這是**格式與驗證**，跟 Android 無關；
 * 放在 core 才能用單元測試證明「被改過一定驗不過」——用 adb 很難製造那種情況。
 *
 * 格式（純文字，可手改、可 diff）：
 * ```
 * # WAE 約束匯出 v1
 * # checksum=<body 的 sha256 前 16 碼>
 * scope\tframe_fp\teffect\tmeans\tverdict\tattribution\tcount\tlast_ts\tapp_version
 * ```
 *
 * 安全邊界：checksum 只防**手滑與傳輸損壞**，不是簽章——真正防篡改要靠簽章，
 * 那需要金鑰管理，尚未做（能力清單會標 ）。
 */
object ConstraintBundle {

    const val HEADER_PREFIX = "# WAE 約束匯出 v1"
    const val CHECKSUM_PREFIX = "# checksum="
    const val COLUMNS = 9

    /** body 的 sha256 前 16 碼（body = 不含註解的行，以 \n 結尾）。 */
    fun checksum(body: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(body.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }.take(16)
    }

    /** 把資料列接成 checksum 用的 body（空清單 → 空字串）。 */
    fun bodyOf(rows: List<String>): String =
        if (rows.isEmpty()) "" else rows.joinToString("\n") + "\n"

    /** 驗證 checksum；不對就整批拒絕。 */
    fun verify(rows: List<String>, claimed: String): Boolean =
        checksum(bodyOf(rows)).equals(claimed.trim(), ignoreCase = true)

    /** 從檔案內容取出資料列（去掉註解與空行）。 */
    fun rowsOf(lines: List<String>): List<String> =
        lines.filter { it.isNotBlank() && !it.startsWith("#") }

    /** 從檔案內容取出聲稱的 checksum。 */
    fun claimedChecksumOf(lines: List<String>): String? =
        lines.firstOrNull { it.startsWith(CHECKSUM_PREFIX) }
            ?.removePrefix(CHECKSUM_PREFIX)
            ?.trim()
            ?.ifEmpty { null }

    /** 只保留欄位數正確的列（避免半行造成後續解析爆炸）。 */
    fun validRows(rows: List<String>): List<String> =
        rows.filter { it.split('\t').size >= COLUMNS }
}
