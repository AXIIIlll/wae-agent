package com.wae.core.memory

import com.wae.core.config.Config
import com.wae.core.config.FileName
import com.wae.core.model.Attribution
import com.wae.core.model.Frame
import com.wae.core.model.Refutation
import com.wae.core.model.Verdict
import com.wae.core.time.Clock
import java.io.File

/**
 * 落盤版記憶體。
 *
 * 紀律（§7）：
 *  1. 只有這個類別寫檔。
 *  2. 原子寫入：先寫 `.tmp` 再 rename（斷電不會留下半個檔）。
 *  3. 壞檔不讓 App 起不來：解析失敗就隔離成 `.bad` 並重建。
 *  4. 只存結構：畫面指紋、效果鍵、手段描述；**不存使用者輸入的文字**。
 *
 * S1 範圍：只落盤 `refutations.tsv`；Frame Ledger 先進記憶體（S3 再做 jsonl，
 * 因為那需要完整的 Frame 序列化與版本遷移）。
 */
class FileMemory(
    private val dir: File,
    config: Config,
    clock: Clock
) : Memory {

    private val delegate = InMemoryMemory(config, clock)

    private val refutationFile: File = File(dir, FileName.REFUTATIONS)

    init {
        dir.mkdirs()
        loadRefutations()
    }

    override fun rememberFrame(frame: Frame) = delegate.rememberFrame(frame)

    override fun frameOf(fp: String): Frame? = delegate.frameOf(fp)

    override fun recordFailure(
        step: com.wae.core.model.Step,
        outcome: com.wae.core.model.Outcome,
        attribution: Attribution,
        frame: Frame
    ): Refutation = delegate.recordFailure(step, outcome, attribution, frame)

    override fun noteStepSuccess(step: com.wae.core.model.Step, frame: Frame) =
        delegate.noteStepSuccess(step, frame)

    override fun constraintsFor(frame: Frame) = delegate.constraintsFor(frame)

    override fun refutations(): List<Refutation> = delegate.refutations()

    override fun ban(effectKey: String, means: String, frameFp: String): Boolean {
        if (effectKey.isEmpty() || means.isEmpty() || frameFp.isEmpty()) return false
        delegate.ban(effectKey, means, frameFp)
        flush()
        return true
    }

    override fun removeRefutation(key: String): Boolean {
        val removed = delegate.removeRefutation(key)
        if (removed) flush()
        return removed
    }

    override fun recordSilentFailure(step: com.wae.core.model.Step, frame: Frame) =
        delegate.recordSilentFailure(step, frame)

    override fun silentFailures(): Int = delegate.silentFailures()

    /** 原子寫入：tmp → rename。 */
    override fun flush() {
        dir.mkdirs()
        val tmp = File(dir, FileName.REFUTATIONS + EXT_TMP)
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (r in delegate.refutations()) {
            sb.append(r.scope).append('\t')
                .append(r.frameFp).append('\t')
                .append(r.effect).append('\t')
                .append(r.means).append('\t')
                .append(r.verdict.name).append('\t')
                .append(r.attribution.name).append('\t')
                .append(r.count).append('\t')
                .append(r.lastTs).append('\t')
                .append(r.appVersion).append('\n')
        }
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (refutationFile.exists()) refutationFile.delete()
        tmp.renameTo(refutationFile)
    }

    private fun loadRefutations() {
        if (!refutationFile.exists()) return
        val rows = ArrayList<Refutation>()
        try {
            refutationFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trimStart('\uFEFF').trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val c = line.split('\t')
                    if (c.size < COLUMNS) continue
                    val verdict = runCatching { Verdict.valueOf(c[4]) }.getOrNull() ?: continue
                    val attribution = runCatching { Attribution.valueOf(c[5]) }.getOrNull() ?: continue
                    val count = c[6].toIntOrNull() ?: continue
                    val lastTs = c[7].toLongOrNull() ?: 0L
                    val appVersion = c.getOrNull(COL_VERSION)?.toLongOrNull() ?: 0L
                    rows.add(
                        Refutation(
                            scope = c[0], frameFp = c[1], effect = c[2], means = c[3],
                            verdict = verdict, attribution = attribution, count = count, lastTs = lastTs,
                            appVersion = appVersion
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // 壞檔 → 隔離，不讓 App 起不來
            runCatching { refutationFile.renameTo(File(dir, FileName.REFUTATIONS + EXT_BAD)) }
            return
        }
        if (rows.isNotEmpty()) delegate.loadRefutations(rows)
    }

    companion object {
        private const val COLUMNS = 8
        private const val COL_VERSION = 8
        private const val EXT_TMP = ".tmp"
        private const val EXT_BAD = ".bad"
        private const val HEADER =
            "# scope\tframe_fp\teffect\tmeans\tverdict\tattribution\tcount\tlast_ts\tapp_version"
    }
}
