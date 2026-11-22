package com.wae.agent.core.component

/**
 * 運行期語意訊號（不含 Android 型別，可在 JVM 測試）。
 *
 * 這些欄位全部對應 `AccessibilityNodeInfo` 的**公開 API**（綠區）：
 *  - 布林能力旗標：`isClickable` / `isCheckable` / `isEditable` / `isScrollable` / `isLongClickable`
 *  - 動作清單：`getActionList()`（含 ACTION_SET_PROGRESS / ACTION_EXPAND / ACTION_SELECT）
 *  - 數值範圍：`getRangeInfo()`（min / max / current / type）
 *  - 集合資訊：`getCollectionInfo()`（rows / columns / scrollable）
 *  - 輸入型別：`getInputType()`
 */
data class RuntimeSignals(
    val className: String = "",
    val isClickable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val inputTypeText: Boolean = false,
    val hasRange: Boolean = false,
    val rangeMin: Float = 0f,
    val rangeMax: Float = 0f,
    val rangeCurrent: Float = 0f,
    val rangeIsPercent: Boolean = false,
    val hasSetProgressAction: Boolean = false,
    val hasExpandAction: Boolean = false,
    val hasSelectAction: Boolean = false,
    val collectionRows: Int = 0,
    val collectionColumns: Int = 0,
    val collectionScrollable: Boolean = false,
) {
    val hasCollection: Boolean get() = collectionRows > 0 || collectionColumns > 0
    val rangeSpan: Float get() = (rangeMax - rangeMin)
}

/**
 * 融合結果：把「元件家族知識（先驗）」與「運行期訊號（實證）」合成一組能力。
 *
 * 優先序：**運行期訊號永遠壓過先驗**。
 *  - 先驗說 READ_ONLY，但節點可點 → 實測為準，拿掉 READ_ONLY
 *  - 先驗說不可調數值，但節點有 RangeInfo / ACTION_SET_PROGRESS → 加上 SET_VALUE
 * 這條規則讓「自訂元件（Compose、Flutter、自繪 SeekBar）」也能正確歸類，
 * 因為它們的類別名一律是 android.view.View，只有運行期訊號說得出真相。
 */
data class Affordances(
    val familyId: String,
    val capabilities: Set<Capability>,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val adjustable: Boolean,
    val evidence: String,
    val confidence: Float
) {
    fun has(c: Capability): Boolean = capabilities.contains(c)

    /** 只會顯示、不該互動 */
    val readOnlyOnly: Boolean
        get() = capabilities.contains(Capability.READ_ONLY) &&
            capabilities.none { it != Capability.READ_ONLY }

    fun summary(): String =
        "family=${familyId.ifEmpty { "?" }} caps=${capabilities.joinToString("/")} evidence=$evidence conf=${"%.2f".format(confidence)}"
}

object AffordanceFusion {

    /** 未知元件（例如自繪 View）的起始能力集合：只知道「可點/可長按/可捲動」 */
    private val UNKNOWN_BASE: Set<Capability> = emptySet()

    fun resolve(signals: RuntimeSignals): Affordances {
        val family = WidgetOntology.familyOfClass(signals.className)
        // 通用容器（android.view.View / Compose 根節點）的類別名本身不帶能力資訊，
        // 這種家族的先驗一律忽略，完全交給運行期訊號判定。
        val priorUsable = family != null && family.strongPrior
        val caps = LinkedHashSet<Capability>(if (priorUsable) family!!.capabilities else UNKNOWN_BASE)
        val evidence = StringBuilder()
        var conf = if (priorUsable) 0.85f else 0.45f

        fun note(s: String) {
            if (evidence.isNotEmpty()) evidence.append(',')
            evidence.append(s)
        }
        if (family != null) note("family:${family.id}${if (priorUsable) "" else "(weak)"}")

        // ── 1. 數值範圍：最強的「可調」證據 ────────────────────────────
        if (signals.hasRange) {
            caps.add(Capability.SET_VALUE)
            caps.add(Capability.DRAG)
            // 進度條（不可點、無 SET_PROGRESS）常也有 RangeInfo，但不應被當成滑桿操作
            val interactive = signals.isClickable || signals.hasSetProgressAction ||
                caps.contains(Capability.STEP_VALUE)
            if (interactive) {
                caps.remove(Capability.READ_ONLY)
                note("range(${signals.rangeMin}~${signals.rangeMax}@${signals.rangeCurrent})")
                conf = maxOf(conf, 0.92f)
            } else {
                caps.add(Capability.READ_ONLY)
                note("range-readonly")
            }
        }
        if (signals.hasSetProgressAction) {
            caps.add(Capability.SET_VALUE)
            caps.remove(Capability.READ_ONLY)
            note("action:SET_PROGRESS")
            conf = maxOf(conf, 0.95f)
        }

        // ── 2. 開關 / 勾選 ────────────────────────────────────────────
        if (signals.isCheckable) {
            caps.add(Capability.TOGGLE)
            note(if (signals.isChecked) "checkable:on" else "checkable:off")
        }

        // ── 3. 輸入 ──────────────────────────────────────────────────
        if (signals.isEditable || signals.inputTypeText) {
            caps.add(Capability.TEXT_INPUT)
            caps.remove(Capability.READ_ONLY)
            note("text-input")
        }

        // ── 4. 捲動 / 集合 ────────────────────────────────────────────
        if (signals.isScrollable) {
            caps.add(Capability.SCROLL_V)
            note("scrollable")
        }
        if (signals.hasCollection) {
            if (signals.collectionColumns > 1 && signals.collectionRows <= 1) {
                caps.add(Capability.SCROLL_H)
                note("collection:horizontal")
            } else {
                caps.add(Capability.SCROLL_V)
                note("collection:vertical")
            }
        }

        // ── 5. 動作清單 ──────────────────────────────────────────────
        if (signals.hasExpandAction) {
            caps.add(Capability.EXPAND)
            note("action:EXPAND")
        }
        if (signals.hasSelectAction) {
            caps.add(Capability.PICK_ONE)
            note("action:SELECT")
        }

        // ── 6. 點擊類 ────────────────────────────────────────────────
        if (signals.isClickable) {
            caps.add(Capability.TAP)
            caps.remove(Capability.READ_ONLY)
            note("clickable")
        }
        if (signals.isLongClickable) caps.add(Capability.LONG_HOLD)

        // ── 7. 收尾：完全沒有能力資訊 → 依可否點擊給最小假設 ─────────────
        if (caps.isEmpty()) {
            caps.add(if (signals.isClickable) Capability.TAP else Capability.READ_ONLY)
            note("fallback")
            conf = 0.35f
        } else if (caps.size == 1 && caps.contains(Capability.READ_ONLY)) {
            conf = minOf(conf, 0.5f)
        }

        return Affordances(
            familyId = family?.id ?: "",
            capabilities = caps,
            adjustable = caps.contains(Capability.SET_VALUE) && !caps.contains(Capability.READ_ONLY),
            evidence = evidence.toString(),
            confidence = conf,
            isCheckable = signals.isCheckable,
            isChecked = signals.isChecked
        )
    }
}
