package com.wae.core.effect

import com.wae.core.config.Config
import com.wae.core.contract.Contract
import com.wae.core.exec.PostConditions
import com.wae.core.means.MeansSolver
import com.wae.core.model.Constraints
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.Effect
import com.wae.core.model.Frame
import com.wae.core.model.Means
import com.wae.core.model.Op
import com.wae.core.model.Plan
import com.wae.core.model.Point
import com.wae.core.model.Risk
import com.wae.core.model.Step
import com.wae.core.util.TextHash

/**
 * 效果 → 計畫（L2/L3，純函式）。
 *
 * 輸入：效果、當前畫面、契約、反例約束
 * 輸出：`Plan`（每個效果挑一條最省且未被禁用的手段鏈；挑不到 → `unsatisfied`）
 *
 * 這一層**不看 App 名稱**，只看型別與文字 hash。
 */
class Planner(
    private val solver: MeansSolver,
    private val resolver: com.wae.core.match.TargetResolver = com.wae.core.match.TargetResolver(),
    private val appResolver: com.wae.core.pipeline.AppResolver? = null
) {

    fun plan(
        effects: List<Effect>,
        frame: Frame,
        contracts: List<Contract>,
        constraints: Constraints,
        preferred: Map<String, String> = emptyMap()
    ): Plan {
        val chosen = ArrayList<Step>()
        val unsatisfied = ArrayList<Effect>()
        val notes = ArrayList<String>()

        for (effect in effects) {
            val chains = candidates(effect, frame, contracts)
            if (chains.isEmpty()) {
                unsatisfied.add(effect)
                continue
            }
            val ranked = chains
                .map { chain -> chain to solver.costOf(chain.first().means.kind) }
                .filter { (chain, _) -> chain.none { constraints.isBanned(it.effect.key(), it.means.describe()) } }

            // 路徑快取（C7）：上次在這台機器成功的手段優先；不在候選裡就照原排序
            val hint = preferred[effect.key()]
            val hinted = if (hint == null) null else ranked.firstOrNull { (chain, _) ->
                chain.first().means.describe() == hint
            }
            val bestChain = (hinted ?: ranked.minByOrNull { it.second })?.first

            if (bestChain == null) {
                unsatisfied.add(effect)
                notes.add("all_banned:" + effect.key())
            } else {
                chosen.addAll(bestChain)
            }
        }
        return Plan(steps = chosen, unsatisfied = unsatisfied, notes = notes)
    }

    // ── 手段鏈 ─────────────────────────────────────────────────────────

    private fun candidates(effect: Effect, frame: Frame, contracts: List<Contract>): List<List<Step>> {
        val target = effect.target
        return when {
            target.startsWith(IntentCompiler.PREFIX_APP) -> appChain(effect, target.removePrefix(IntentCompiler.PREFIX_APP), frame)
            target.startsWith(IntentCompiler.PREFIX_PAGE) -> pageChain(effect, target.removePrefix(IntentCompiler.PREFIX_PAGE), frame)
            target.startsWith(IntentCompiler.PREFIX_TOGGLE) -> toggleChain(effect, target.removePrefix(IntentCompiler.PREFIX_TOGGLE), frame, contracts)
            target.startsWith(IntentCompiler.PREFIX_QUERY) -> queryChain(effect, target.removePrefix(IntentCompiler.PREFIX_QUERY), frame, contracts)
            target.startsWith(IntentCompiler.PREFIX_VOLUME) -> systemChain(effect, API_VOLUME_PREFIX + valueOrUnknown(effect.value), frame)
            target.startsWith(IntentCompiler.PREFIX_MEDIA) -> systemChain(effect, API_MEDIA_PREFIX + valueOrUnknown(effect.value), frame)
            // D10：系統設定。Wi-Fi／藍牙／飛航只能開面板（Android 10+ 不給第三方直接切）；
            // 亮度可以直接寫（需要 WRITE_SETTINGS，沒有就開顯示設定）
            target.startsWith(PREFIX_PANEL) -> systemChain(effect, API_PANEL_PREFIX + target.removePrefix(PREFIX_PANEL), frame)
            target.startsWith(PREFIX_GLOBAL) -> systemChain(effect, API_GLOBAL_PREFIX + target.removePrefix(PREFIX_GLOBAL), frame)
            target.startsWith(PREFIX_BRIGHTNESS) -> systemChain(effect, API_BRIGHTNESS_PREFIX + valueOrUnknown(effect.value), frame)
            target.startsWith(IntentCompiler.PREFIX_PICKER) -> pickerChain(effect, frame)
            target.startsWith(PREFIX_CAPTURE) -> captureChain(effect, frame)
            target.startsWith(PREFIX_SHARE) -> shareChain(effect, frame)
            target.startsWith(PREFIX_SHARE_IMAGE) -> shareChain(effect, frame)
            target.startsWith(PREFIX_SHARE_PHOTO) -> shareChain(effect, frame)
            target.startsWith(IntentCompiler.PREFIX_CONTACT) -> contactChain(effect, target.removePrefix(IntentCompiler.PREFIX_CONTACT), frame)
            else -> emptyList()
        }
    }

    private fun appChain(effect: Effect, name: String, frame: Frame): List<List<Step>> {
        if (name.isBlank()) return emptyList()

        // 能解析出套件名就做「精確驗證」；解析不出來才退回弱驗證。
        val pkg = appResolver?.resolve(name)
        val means = if (pkg != null) {
            Means.Intent(action = ACTION_MAIN, pkg = pkg, data = name)
        } else {
            Means.Intent(action = ACTION_MAIN, data = name)
        }
        val post = if (appResolver != null) {
            // App 層能判斷「同一個 App 的不同 package」時，用它（比 pkg 精確比對誠實得多）
            com.wae.core.model.PostCondition { f ->
                appResolver.matches(f.pkg, name) || (pkg != null && f.pkg != frame.pkg)
            }
        } else if (pkg != null) {
            PostConditions.pkgIsOrChanged(pkg, frame.pkg)
        } else {
            PostConditions.alwaysTrue()
        }

        val step = step(
            effect = effect,
            means = means,
            post = post,
            risk = Risk.LOW,
            index = 0
        )
        return listOf(listOf(step))
    }

    private fun pageChain(effect: Effect, page: String, frame: Frame): List<List<Step>> {
        if (page == IntentCompiler.PAGE_BACK) {
            val step = step(
                effect = effect,
                means = Means.SystemApi(api = API_GLOBAL_BACK),
                // 返回的後置條件要寬：可能只是同一支 App 換頁（畫面變），也可能退出 App（pkg 變）。
                post = com.wae.core.model.PostCondition { f ->
                    f.pkg != frame.pkg || f.fp != frame.fp
                },
                risk = Risk.LOW,
                index = 0
            )
            return listOf(listOf(step))
        }
        if (page != IntentCompiler.PAGE_HOME) return emptyList()
        val step = step(
            effect = effect,
            means = Means.SystemApi(api = API_GLOBAL_HOME),
            // 回首頁是**冪等**的全域動作：已經在桌布上時它不會改變任何東西，
            // 用 pkgChanged 會把「本來就已經在家」判成失敗（實測踩過）。
            // 誠實的驗證是系統回傳 true，不是畫面變了。
            post = PostConditions.alwaysTrue(),
            risk = Risk.LOW,
            index = 0
        )
        return listOf(listOf(step))
    }

    private fun toggleChain(
        effect: Effect,
        name: String,
        frame: Frame,
        contracts: List<Contract>
    ): List<List<Step>> {
        // 目標解析階梯（§4.7）：精確 → 別名 → 語意 → 結構 → 反問。
        // 解析不到（Missing／Ambiguous）就回空 → unsatisfied → ABSTAIN，不猜。
        val resolution = resolver.resolve(name, frame.controls, ControlKind.TOGGLE)
        val control = (resolution as? com.wae.core.match.Resolution.Found)?.control ?: return emptyList()
        val contract = contractOf(control, contracts)
        val before = control.state.on ?: control.state.checked
        val viaNode = step(
            effect = effect,
            means = Means.A11y(key = control.key, action = ACTION_CLICK),
            post = PostConditions.toggleFlipped(control.key, before),
            risk = contract?.risk ?: Risk.MEDIUM,
            index = 0
        )
        // 沒有節點（純 OCR／圖示）時改走座標手勢：同一效果的第二條路（B2）
        val viaGesture = step(
            effect = effect,
            means = gestureTap(control),
            post = PostConditions.frameChanged(frame.fp),
            risk = contract?.risk ?: Risk.MEDIUM,
            index = 0
        )
        return listOf(listOf(viaNode), listOf(viaGesture))
    }

    private fun queryChain(
        effect: Effect,
        text: String,
        frame: Frame,
        contracts: List<Contract>
    ): List<List<Step>> {
        if (text.isBlank()) return emptyList()

        val chains = ArrayList<List<Step>>()
        // 0) 先試「帶查詢字串的搜尋意圖」：最便宜、不需要輸入法。
        //    支援的 App 由 App 層資料表決定；不支援就 noEffect，這條路會被反例擋掉。
        chains.add(
            listOf(
                step(
                    effect = effect,
                    means = Means.Intent(action = ACTION_SEARCH, data = text),
                    // 後置只看「畫面真的換了」：搜尋頁可能載入較慢，用 textAppeared 會誤判失敗。
                    // 為什麼不用 pkgChanged：搜尋 Activity 可能就在同一個套件裡
                    // （實測：開完設定後前景已是 settings.intelligence），pkg 不會變。
                    // 真正的正確性由效果驗證（欄位裡有沒有這個字）負責。
                    post = PostConditions.frameChanged(frame.fp),
                    risk = Risk.LOW,
                    index = 0
                )
            )
        )

        val field = frame.controls.firstOrNull { it.kind == ControlKind.FIELD }
        if (field == null) {
            // 收合式搜尋列（設定頁最常見）：先點開它，下一輪再輸入。
            // 這不是猜——它的標籤就是「搜尋」，而且點開後畫面上才會出現可輸入欄位。
            val entry = frame.controls.firstOrNull { c ->
                c.enabled && SEARCH_HINTS.any { hint -> c.label.contains(hint, ignoreCase = true) }
            } ?: return chains.takeIf { it.isNotEmpty() } ?: emptyList()
            val open = step(
                effect = effect,
                means = Means.A11y(key = entry.key, action = ACTION_CLICK),
                post = PostConditions.frameChanged(frame.fp),
                risk = Risk.LOW,
                index = 0
            )
            chains.add(listOf(open))
            return chains
        }

        val contract = contractOf(field, contracts)
        val focus = step(
            effect = effect,
            means = Means.A11y(key = field.key, action = ACTION_FOCUS),
            // 聚焦常常會讓輸入框重建（key 變了），用 controlPresent 會誤判失敗。
            // 最終責任交給下一步的 textAppeared：真的沒聚焦就輸入不進去。
            post = PostConditions.alwaysTrue(),
            risk = contract?.risk ?: Risk.LOW,
            index = 0
        )
        val input = step(
            effect = effect,
            means = Means.Input(text = text),
            // 不綁定元素：輸入後 DOM 可能重建，綁 key 會驗不過
            post = PostConditions.textAppeared(text),
            risk = contract?.risk ?: Risk.LOW,
            index = 1
        )
        // 第二條路：直接點欄位（座標）再輸入，適合沒有節點的 WebView/Flutter
        val tapFocus = step(
            effect = effect,
            means = gestureTap(field),
            post = PostConditions.frameChanged(frame.fp),
            risk = contract?.risk ?: Risk.LOW,
            index = 0
        )
        chains.add(listOf(focus, input))
        chains.add(listOf(tapFocus, input.copy(idempotencyKey = input.idempotencyKey + GESTURE_SUFFIX)))
        return chains
    }

    /**
     * 選檔器（D15 半自動）：打開系統選圖介面，**選哪一張留給人**。
     *
     * 為什麼不自動選：相簿第一張不一定是使用者要的；亂選比不選更糟。
     * 後置條件用 frameChanged——只要選檔器真的出現就算這一步成功。
     */
    private fun pickerChain(effect: Effect, frame: Frame): List<List<Step>> {
        val step = step(
            effect = effect,
            means = Means.SystemApi(api = API_PICKER_PREFIX + "image"),
            post = PostConditions.frameChanged(frame.fp),
            risk = Risk.LOW,
            index = 0
        )
        return listOf(listOf(step))
    }

    private fun systemChain(effect: Effect, api: String, frame: Frame): List<List<Step>> {
        val step = step(
            effect = effect,
            means = Means.SystemApi(api = api),
            // 音量／媒體這種系統 API 不會改變畫面，用 frameChanged 會永遠驗不過
            // （實測：調低音量被誤判失敗，最後被彈窗守衛吃掉）。
            // 誠實的後置條件是「API 回報成功」——由 Actuator 負責，這裡不假裝看得到。
            post = PostConditions.alwaysTrue(),
            risk = Risk.LOW,
            index = 0
        )
        return listOf(listOf(step))
    }

    private fun valueOrUnknown(value: String): String =
        if (value.isBlank()) VALUE_UNKNOWN else value

    private fun contactChain(effect: Effect, name: String, frame: Frame): List<List<Step>> {
        if (name.isBlank()) return emptyList()
        val step = step(
            effect = effect,
            means = Means.Intent(action = ACTION_SEND, data = name),
            post = PostConditions.frameChanged(frame.fp),
            risk = Risk.HIGH,
            index = 0
        )
        return listOf(listOf(step))
    }

    /**
     * 拍照：先找**有標籤**的快門鍵；找不到就用「相機快門的通用位置」——畫面正下方中央。
     *
     * 為什麼可以用位置先驗：快門是全世界相機 App 最一致的元素（置中、貼底），
     * 而且這是**唯一**能處理「純圖示、沒有無障礙標籤」的相機介面的方法。
     * 風險標 MEDIUM，並且用「畫面變了」驗證（成功拍照會有縮圖／動畫）。
     */
    private fun captureChain(effect: Effect, frame: Frame): List<List<Step>> {
        val shutter = frame.controls.firstOrNull { c ->
            val hay = c.label.lowercase(java.util.Locale.ROOT)
            c.enabled && SHUTTER_HINTS.any { hay.contains(it) }
        }

        if (shutter != null) {
            val step = step(
                effect = effect,
                means = Means.A11y(key = shutter.key, action = ACTION_CLICK),
                post = PostConditions.frameChanged(frame.fp),
                risk = Risk.MEDIUM,
                index = 0
            )
            val gesture = step(
                effect = effect,
                means = gestureTap(shutter),
                post = PostConditions.frameChanged(frame.fp),
                risk = Risk.MEDIUM,
                index = 0
            )
            return listOf(listOf(step), listOf(gesture))
        }

        // A4：沒有標籤的快門——找底部的圖示候選（圖示通道產生的 Control）
        val frameW = frame.controls.maxOfOrNull { it.bounds.right } ?: 0
        val frameH = frame.controls.maxOfOrNull { it.bounds.bottom } ?: 0
        if (frameW > 0 && frameH > 0) {
            val iconShutter = frame.controls.firstOrNull { c ->
                c.source == com.wae.core.model.Channel.ICON &&
                    c.bounds.top > frameH * SHUTTER_REGION_TOP_RATIO &&
                    kotlin.math.abs(c.bounds.centerX() - frameW / 2) < frameW * SHUTTER_REGION_X_TOLERANCE
            }
            if (iconShutter != null) {
                val step = step(
                    effect = effect,
                    means = gestureTap(iconShutter),
                    post = PostConditions.frameChanged(frame.fp),
                    risk = Risk.MEDIUM,
                    index = 0
                )
                return listOf(listOf(step))
            }
        }

        // 位置先驗：需要在相機畫面（有可見元素）才敢用
        val screenW = frameW
        val screenH = frameH
        if (screenW <= 0 || screenH <= 0 || frame.controls.size < MIN_CONTROLS_FOR_PRIOR) return emptyList()

        val point = com.wae.core.model.Point(screenW / 2, (screenH * SHUTTER_Y_RATIO).toInt())
        val prior = step(
            effect = effect,
            means = Means.Gesture(gesture = com.wae.core.model.GestureKind.TAP, at = point),
            post = PostConditions.frameChanged(frame.fp),
            risk = Risk.MEDIUM,
            index = 0
        )
        return listOf(listOf(prior))
    }

    /**
     * 分享（D11）：用 `ACTION_SEND` 把文字直接丟給目標 App，不必先開它。
     * 有解析到套件名就用「前景變成那個 App」驗證；解析不到就只能驗「畫面變了」。
     */
    private fun shareChain(effect: Effect, frame: Frame): List<List<Step>> {
        val appLabel = com.wae.core.intent.ShareCommandParser.appLabelOf(effect) ?: return emptyList()
        val resolver = appResolver
        val pkg = resolver?.resolve(appLabel)
        val action = when {
            com.wae.core.intent.ShareCommandParser.isPhotoShare(effect) -> ACTION_SHARE_PHOTO
            com.wae.core.intent.ShareCommandParser.isImageShare(effect) -> ACTION_SHARE_IMAGE
            else -> ACTION_SEND
        }
        val means = Means.Intent(action = action, pkg = pkg, data = effect.value)
        val post = if (resolver != null) {
            com.wae.core.model.PostCondition { f ->
                resolver.matches(f.pkg, appLabel) || f.pkg != frame.pkg
            }
        } else {
            PostConditions.frameChanged(frame.fp)
        }
        val step = step(effect = effect, means = means, post = post, risk = Risk.MEDIUM, index = 0)
        return listOf(listOf(step))
    }

    /** 給上層（Agent）用的：把「先開某個 App」變成一條可執行的步驟。 */
    fun appLaunchStep(effect: Effect, appLabel: String, frame: Frame): Step? =
        appChain(effect, appLabel, frame).firstOrNull()?.firstOrNull()

    // ── 工具 ───────────────────────────────────────────────────────────

    private fun contractOf(control: Control, contracts: List<Contract>): Contract? =
        contracts.firstOrNull { it.controlKey == control.key }

    private fun step(
        effect: Effect,
        means: Means,
        post: com.wae.core.model.PostCondition,
        risk: Risk,
        index: Int
    ): Step = Step(
        effect = effect,
        means = means,
        post = post,
        risk = risk,
        idempotencyKey = effect.key() + KEY_SEP + means.describe() + KEY_SEP + index
    )

    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_CLICK = "click"
        const val ACTION_FOCUS = "focus"
        const val API_GLOBAL_HOME = "global_action:HOME"
        const val API_GLOBAL_BACK = "global_action:BACK"
        const val API_MEDIA_PREFIX = "media:"
        const val API_VOLUME_PREFIX = "volume:"
        const val API_PANEL_PREFIX = "panel:"
        const val API_GLOBAL_PREFIX = "global:"
        const val PREFIX_GLOBAL = "global:"
        const val API_BRIGHTNESS_PREFIX = "brightness:"
        const val PREFIX_PANEL = "panel:"
        const val PREFIX_BRIGHTNESS = "brightness:"
        const val VALUE_UNKNOWN = "unknown"
        private const val KEY_SEP = "#"
        private const val GESTURE_SUFFIX = "-g"

        /** 收合式搜尋列的辨識詞（跨 App 通用，不是某支 App 的專屬 id）。 */
        private val SEARCH_HINTS = listOf("搜尋", "搜索", "search", "find")

        /** 快門鍵的辨識詞（跨相機 App 通用）。 */
        private val SHUTTER_HINTS = listOf("拍照", "快門", "shutter", "capture", "take photo")

        const val PREFIX_CAPTURE = "capture:"

    /** 選檔器 API 前綴（Actuator 認得 `picker:`）。 */
    const val API_PICKER_PREFIX = "picker:"
        const val PREFIX_SHARE = "share:text:"
        const val PREFIX_SHARE_IMAGE = "share:image:"
        /** 自訂 action：由 Actuator 負責「截圖 → 存檔 → 用 content:// 分享」。 */
        const val ACTION_SHARE_IMAGE = "wae.action.SHARE_IMAGE"
        /** 自訂 action：帶查詢字串的搜尋意圖（App 層查表決定用哪個 action）。 */
        const val ACTION_SEARCH = "wae.action.SEARCH"
        /** 自訂 action：分享相簿裡最近一張照片。 */
        const val ACTION_SHARE_PHOTO = "wae.action.SHARE_PHOTO"
        const val PREFIX_SHARE_PHOTO = "share:photo:"

        /** 快門的通用位置：畫面寬度中央、高度 88% 處。 */
        private const val SHUTTER_Y_RATIO = 0.88

        /** 少於這個元素數就不敢用位置先驗（畫面可能還沒載入）。 */
        private const val MIN_CONTROLS_FOR_PRIOR = 3

        /** 快門在畫面的哪一帶（高度比例）。 */
        private const val SHUTTER_REGION_TOP_RATIO = 0.62

        /** 快門偏離畫面中央的容許比例。 */
        private const val SHUTTER_REGION_X_TOLERANCE = 0.25

        /** 手勢手段（點座標）：由 App 層在「找不到節點」時提供，Planner 只負責建立 Step。 */
        fun gestureTap(control: Control): Means.Gesture =
            Means.Gesture(
                gesture = com.wae.core.model.GestureKind.TAP,
                at = Point(control.bounds.centerX(), control.bounds.centerY())
            )
    }
}
