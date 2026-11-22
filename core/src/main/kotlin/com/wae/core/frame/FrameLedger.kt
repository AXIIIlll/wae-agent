package com.wae.core.frame

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlState
import com.wae.core.model.Frame
import com.wae.core.model.RectI
import com.wae.core.time.Clock
import java.io.File

/**
 * 畫面帳本（A6）：把「看過的畫面」變成資產。
 *
 * 為什麼要落盤：同一個畫面重進時，指紋可以直接命中，不必重新推導型別與候選；
 * 這是「成本與不同畫面數成正比」而不是「與執行次數成正比」的關鍵。
 *
 * 格式刻意用 TSV 而不是 JSON：
 *  - core 沒有 JSON 依賴
 *  - 人可以讀、可以 diff、可以手動刪
 *  - 壞行直接跳過，不會讓整個檔失效
 */
class FrameLedger(
    private val file: File,
    private val clock: Clock,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 檔案超過這個大小就壓縮（只留最近的 KEEP_LINES 行）。 */
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val keepLines: Int = DEFAULT_KEEP_LINES
) {

    private val byFp = LinkedHashMap<String, Frame>()

    init {
        file.parentFile?.mkdirs()
        load()
    }

    fun remember(frame: Frame) {
        if (frame.fp.isEmpty()) return
        byFp[frame.fp] = frame
        if (byFp.size > maxEntries) {
            val oldest = byFp.keys.firstOrNull() ?: return
            byFp.remove(oldest)
        }
        append(frame)
    }

    fun find(fp: String): Frame? = byFp[fp]

    fun size(): Int = byFp.size

    fun clear() {
        byFp.clear()
        runCatching { file.delete() }
    }

    // ── 序列化 ───────────────────────────────────────────────────────

    private fun append(frame: Frame) {
        runCatching {
            // 記憶體有上限，檔案也必須有：實測 frames.tsv 會長到 12 MB（8,700 行），
            // 而且只增不減——手機上這是慢性病（使用者不會知道要去刪）。
            if (file.exists() && file.length() > maxFileBytes) compact()
            file.appendText(encode(frame) + "\n", Charsets.UTF_8)
        }
    }

    /** 只留最近幾行；TSV 壞行本來就會被跳過，所以截斷是安全的。 */
    private fun compact() {
        runCatching {
            val kept = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.toList().takeLast(keepLines)
            }
            file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), Charsets.UTF_8)
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    if (raw.isBlank() || raw.startsWith("#")) continue
                    val frame = decode(raw) ?: continue
                    byFp[frame.fp] = frame
                }
            }
        }
    }

    private fun encode(frame: Frame): String = buildString {
        append(frame.fp).append('\t')
        append(frame.pkg).append('\t')
        append(frame.appVersion).append('\t')
        append(frame.atMs).append('\t')
        append(frame.controls.joinToString(SEP_CONTROL.toString()) { encodeControl(it) })
    }

    private fun decode(line: String): Frame? {
        val cells = line.split('\t')
        if (cells.size < COLUMNS) return null
        val controls = if (cells[COL_CONTROLS].isEmpty()) emptyList() else {
            cells[COL_CONTROLS].split(SEP_CONTROL).mapNotNull { decodeControl(it) }
        }
        return Frame(
            fp = cells[COL_FP],
            pkg = cells[COL_PKG],
            appVersion = cells[COL_VERSION].toLongOrNull() ?: 0L,
            controls = controls,
            atMs = cells[COL_AT].toLongOrNull() ?: 0L
        )
    }

    private fun encodeControl(c: Control): String = buildString {
        append(escape(c.key)).append(FIELD)
        append(c.kind.name).append(FIELD)
        append(escape(c.label)).append(FIELD)
        append(c.bounds.left).append(',').append(c.bounds.top).append(',')
        append(c.bounds.right).append(',').append(c.bounds.bottom).append(FIELD)
        append(if (c.enabled) '1' else '0')
        append(if (c.scrollable) '1' else '0')
    }

    private fun decodeControl(text: String): Control? {
        val parts = text.split(FIELD)
        if (parts.size < FIELD_COUNT) return null
        val rect = parts[3].split(',').mapNotNull { it.toIntOrNull() }
        if (rect.size != RECT_FIELDS) return null
        val kind = runCatching { ControlKind.valueOf(parts[1]) }.getOrNull() ?: ControlKind.UNKNOWN
        val flags = parts[4]
        return Control(
            key = unescape(parts[0]),
            kind = kind,
            label = unescape(parts[2]),
            bounds = RectI(rect[0], rect[1], rect[2], rect[3]),
            enabled = flags.isNotEmpty() && flags[0] == '1',
            scrollable = flags.length > 1 && flags[1] == '1',
            state = ControlState()
        )
    }

    private fun escape(text: String): String = text
        .replace(ESCAPE_NL, SPACE)
        .replace(ESCAPE_TAB, SPACE)

    private fun unescape(text: String): String = text

    companion object {
        /** 帳本檔超過 4 MB 就壓縮。 */
        const val DEFAULT_MAX_FILE_BYTES = 4L * 1024 * 1024
        /** 壓縮後保留的行數。 */
        const val DEFAULT_KEEP_LINES = 1_000
        const val DEFAULT_MAX_ENTRIES = 500

        private const val COL_FP = 0
        private const val COL_PKG = 1
        private const val COL_VERSION = 2
        private const val COL_AT = 3
        private const val COL_CONTROLS = 4
        private const val COLUMNS = 5

        private const val FIELD = '\u001F'
        private const val FIELD_COUNT = 5
        private const val RECT_FIELDS = 4
        private const val SEP_CONTROL = '\u001E'
        private const val ESCAPE_NL = '\n'
        private const val ESCAPE_TAB = '\t'
        private const val SPACE = ' '
    }
}
