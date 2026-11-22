package com.wae.core.memory

import com.wae.core.config.Config
import com.wae.core.config.FileName
import com.wae.core.frame.FrameLedger
import com.wae.core.receipt.ReceiptRecorder
import com.wae.core.routing.CapabilityIndex
import com.wae.core.routing.CapabilityStore
import com.wae.core.time.Clock
import com.wae.core.worldmodel.TransitionRecorder
import java.io.File

/**
 * 記憶體的**單一組裝點**（D2）。
 *
 * 以前是「每個模組各自 new 一個 store、各自決定檔名與流程」；
 * 現在只有這裡知道「有哪些庫、放哪裡、什麼時候存」。
 *
 * 四庫一 schema 的目標在這裡落實：
 *  - `frames.tsv`      畫面帳本
 *  - `contracts`       契約（目前由 TSV 規則推導，尚未落盤）
 *  - `refutations.tsv` 反例
 *  - `programs.tsv`    已證明路徑（尚未啟用）
 * 另有兩個輔助檔：`receipts.tsv`（收據）與 `transitions.tsv`（世界模型資料）。
 */
class MemoryBundle(
    dir: File,
    config: Config,
    clock: Clock
) {

    val memory: FileMemory = FileMemory(dir, config, clock)
    val ledger: FrameLedger = FrameLedger(File(dir, FileName.FRAMES_TSV), clock)
    val receipts: ReceiptRecorder = ReceiptRecorder(File(dir, FileName.RECEIPTS), clock)
    val capabilityStore: CapabilityStore = CapabilityStore(File(dir, CAPABILITY_FILE))
    val capabilities: CapabilityIndex = CapabilityIndex()
    val transitions: TransitionRecorder = TransitionRecorder(File(dir, TRANSITIONS_FILE), clock)
    val programs: ProgramCache = ProgramCache(File(dir, PROGRAM_FILE))

    init {
        runCatching { capabilities.seed(capabilityStore.load()) }
    }

    /** 有沒有任何「永久性」的東西需要落地（frames 由 ledger 自己 append）。 */
    fun save() {
        runCatching { memory.flush() }
        runCatching { capabilityStore.save(capabilities.all()) }
        runCatching { programs.save() }
    }

    fun summary(): String = "frames=" + ledger.size() +
        " caps=" + capabilities.all().size +
        " programs=" + programs.size() +
        " silent=" + memory.silentFailures() +
        " transitions=" + transitions.count()

    companion object {
        const val CAPABILITY_FILE = "capabilities.tsv"
        const val TRANSITIONS_FILE = "transitions.tsv"
        const val PROGRAM_FILE = "programs.tsv"
    }
}
