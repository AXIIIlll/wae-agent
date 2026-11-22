package com.wae.agent.core

/**
 * 動作原語（Action Primitive）——把整個動作空間收斂成 **3 類**。
 *
 * 為什麼要收斂：
 *  1. 降低 Twin/IR 的儲存與 token 成本（每步只存 primitive + 少量參數）
 *  2. 讓「跨 App 泛化」有共同語言：任何 App 的操作都只是這三類
 *  3. 策略/安全/重放都能用同一個粒度推理
 *
 * 三類的定義（與具體控件無關）：
 *  - [LAUNCH]   開啟目標：啟動 App、深鏈直達、回到前台
 *  - [INTERACT] 與畫面互動：點擊、長按、滑動、滾動、返回、等待
 *  - [INPUT]    輸入文字：鍵入內容到可編輯欄位（不含送出）
 *
 * 這三類是**互斥且窮盡**的：任何使用者操作都可歸入其一。
 */
enum class ActionPrimitive { LAUNCH, INTERACT, INPUT }

/** 互動的細分類（僅 INTERACT 有值），用於重放時選擇手勢 */
enum class InteractKind { TAP, SCROLL, BACK, WAIT, NONE }

/**
 * 細分動作 + 短碼：3 類原語負責「語意分類」，短碼負責「省 token 的具體手勢」。
 *
 * 命名規則：L:xxx = LAUNCH、I:xxx = INTERACT、N:xxx = INPUT（N 代表 iNput，避免與 I 混淆）。
 * 這樣每一步在 IR/Twin 裡只需要 5–14 bytes，而不是完整 enum 名稱。
 */
enum class FineOp(val code: String, val primitive: ActionPrimitive) {
    L_APP("L:APP", ActionPrimitive.LAUNCH),
    L_DEEPLINK("L:LINK", ActionPrimitive.LAUNCH),
    L_NOTIFICATION("L:NOTIF", ActionPrimitive.LAUNCH),

    I_TAP("I:TAP", ActionPrimitive.INTERACT),
    I_SCROLL_DOWN("I:SDN", ActionPrimitive.INTERACT),
    I_SCROLL_UP("I:SUP", ActionPrimitive.INTERACT),
    I_SWIPE_LR("I:SLR", ActionPrimitive.INTERACT),
    I_LONG_PRESS("I:LNG", ActionPrimitive.INTERACT),
    I_BACK("I:BAK", ActionPrimitive.INTERACT),
    I_WAIT("I:WAI", ActionPrimitive.INTERACT),
    I_SUBMIT("I:SUB", ActionPrimitive.INTERACT),
    I_SET_VALUE("I:VAL", ActionPrimitive.INTERACT),

    N_TEXT("N:TXT", ActionPrimitive.INPUT),
    N_CLEAR("N:CLR", ActionPrimitive.INPUT),
    N_REPLACE("N:RPL", ActionPrimitive.INPUT)
}

/**
 * 一個可重放/可編譯的動作描述。
 *
 * @param detail 具體語意（如 scroll_down、tap），僅供除錯與統計
 * @param slot   INPUT 時的參數槽（實際值由任務提供，絕不寫死）
 */
data class ActionOp(
    val primitive: ActionPrimitive,
    val fine: FineOp,
    val kind: InteractKind = InteractKind.NONE,
    val detail: String = "",
    val slot: String = ""
) {
    /** 精簡字串（Twin/IR 儲存用，降低 token）：只帶短碼，必要時附參數槽 */
    fun compact(): String = if (slot.isNotEmpty()) "${fine.code}($slot)" else fine.code
}

object ActionOps {

    fun of(kind: ActionKind, slot: String = ""): ActionOp = when (kind) {
        ActionKind.LAUNCH_APP -> ActionOp(ActionPrimitive.LAUNCH, FineOp.L_APP, detail = kind.name)
        ActionKind.DEEP_LINK -> ActionOp(ActionPrimitive.LAUNCH, FineOp.L_DEEPLINK, detail = kind.name)
        ActionKind.TYPE -> ActionOp(ActionPrimitive.INPUT, FineOp.N_TEXT, detail = kind.name, slot = slot)
        ActionKind.TAP -> ActionOp(ActionPrimitive.INTERACT, FineOp.I_TAP, InteractKind.TAP, kind.name)
        ActionKind.SCROLL_DOWN ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_SCROLL_DOWN, InteractKind.SCROLL, kind.name)
        ActionKind.BACK ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_BACK, InteractKind.BACK, kind.name)
        ActionKind.WAIT ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_WAIT, InteractKind.WAIT, kind.name)
        ActionKind.SCROLL_UP ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_SCROLL_UP, InteractKind.SCROLL, kind.name)
        ActionKind.SWIPE_HORIZONTAL ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_SWIPE_LR, InteractKind.SCROLL, kind.name)
        ActionKind.LONG_PRESS ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_LONG_PRESS, InteractKind.TAP, kind.name)
        ActionKind.SUBMIT ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_SUBMIT, InteractKind.TAP, kind.name)
        ActionKind.CLEAR_TEXT ->
            ActionOp(ActionPrimitive.INPUT, FineOp.N_CLEAR, detail = kind.name)
        ActionKind.REPLACE_TEXT ->
            ActionOp(ActionPrimitive.INPUT, FineOp.N_REPLACE, detail = kind.name, slot = slot)
        ActionKind.ADJUST_VALUE ->
            ActionOp(ActionPrimitive.INTERACT, FineOp.I_SET_VALUE, InteractKind.TAP, kind.name)
    }

    /** 尚未接線的細分動作（目前僅剩通知開啟，需 NotificationListenerService） */
    fun planned(): List<FineOp> = listOf(FineOp.L_NOTIFICATION)
}
