package com.wae.core.worldmodel

/**
 * 轉移紀錄的編碼（E1 的資料格式）。
 *
 * 一行一筆，欄位固定：
 * `pkg  appVersion  actionKey  beforeFp  afterFp  beforePkg  afterPkg  reported`
 *
 * 只存指紋與結構，不存畫面內容——所以這份檔案可以直接拿來訓練，不需要脫敏。
 */
object TransitionCodec {

    const val HEADER =
        "# pkg\tapp_version\taction\tbefore_fp\tafter_fp\tbefore_pkg\tafter_pkg\treported"

    private const val COLUMNS = 8

    fun encode(t: Transition): String = buildString {
        append(t.pkg).append('\t')
        append(t.appVersion).append('\t')
        append(t.actionKey.replace('\t', ' ')).append('\t')
        append(t.before.fp).append('\t')
        append(t.after.fp).append('\t')
        append(t.before.pkg).append('\t')
        append(t.after.pkg).append('\t')
        append(if (t.reportedSuccess) '1' else '0')
    }

    fun decode(line: String): Transition? {
        val c = line.split('\t')
        if (c.size < COLUMNS) return null
        val before = Observation(
            fp = c[3],
            pkg = c[5],
            controlCount = 0,
            structureHash = ""
        )
        val after = Observation(
            fp = c[4],
            pkg = c[6],
            controlCount = 0,
            structureHash = ""
        )
        return Transition(
            pkg = c[0],
            appVersion = c[1].toLongOrNull() ?: 0L,
            actionKey = c[2],
            before = before,
            after = after,
            reportedSuccess = c[7] == "1"
        )
    }
}
