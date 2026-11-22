package com.wae.agent.gen5

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 開發用觸發器：不必碰 UI 就能跑 Gen-5 管線。
 *
 * 用法（adb）：
 * ```
 * adb push cmd.txt /sdcard/Android/data/com.wae.agent/files/gen5_command.txt
 * adb shell am start -n com.wae.agent/.MainActivity
 * adb pull /sdcard/Android/data/com.wae.agent/files/gen5_result.txt
 * ```
 *
 * 安全：只有這兩個檔存在時才會動；檔案讀完即刪；不影響正式使用者。
 */
object Gen5Probe {

    private const val TAG = "WAE_GEN5"
    private const val CMD_FILE = "gen5_command.txt"
    private const val RESULT_FILE = "gen5_result.txt"

    fun check(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val cmdFile = File(dir, CMD_FILE)
        if (!cmdFile.exists()) return

        val command = runCatching { cmdFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        cmdFile.delete()
        if (command.isEmpty()) return

        Thread {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            // 剪貼簿在 Android 10+ 只給「有焦點」的 App；剛啟動時焦點還沒到位，
            // 太早讀寫會被系統靜默丟棄（實測：寫入回 ok、讀回卻是空的）。
            runCatching { Thread.sleep(600) }
            val result = runCatching {
                when (command) {
                    ADMIN_CLEAR -> clearMemory(context)
                    ADMIN_SELFTEST -> SelfTest.runToText(context)
                    ADMIN_REFS -> refutationsToText(context)
                    ADMIN_NOTIFY -> postSelfNotification(context)
                    ADMIN_DIAG -> exportDiagnostics(context)
                    ADMIN_DIAG_SHARE -> shareDiagnostics(context)
                    ADMIN_EXPORT -> exportConstraints(context)
                    ADMIN_IMPORT -> importConstraints(context)
                    else -> {
                        if (command.startsWith(KB_PREFIX)) {
                            kbReport(context, command.removePrefix(KB_PREFIX).trim())
                        } else if (command.startsWith(NOUN_PREFIX)) {
                            nounReport(context, command.removePrefix(NOUN_PREFIX).trim())
                        } else if (command == FACTS_CMD) {
                            RuleEngineHost.factsText(context)
                        } else if (command == DIGEST_CMD) {
                            digestReport(context)
                        } else if (command.startsWith(LEXICON_PREFIX)) {
                            lexiconCommand(context, command.removePrefix(LEXICON_PREFIX).trim())
                        } else if (command.startsWith(RULES_PREFIX)) {
                            rulesCommand(context, command.removePrefix(RULES_PREFIX).trim())
                        } else if (command.startsWith(JUDGE_PREFIX)) {
                            judgeCommand(context, command.removePrefix(JUDGE_PREFIX).trim())
                        } else if (command.startsWith(ICONS_PREFIX)) {
                            iconsSwitch(command.removePrefix(ICONS_PREFIX).trim())
                        } else if (command == APPS_SUMMARY || command.startsWith(APPS_PREFIX)) {
                            appsReport(context, command)
                        } else if (command.startsWith(CLIP_COPY_PREFIX)) {
                            copyToClipboard(context, command.removePrefix(CLIP_COPY_PREFIX).trim())
                        } else if (command == CLIP_READ) {
                            readClipboard(context)
                        } else {
                            Gen5Runtime.run(context, command)
                        }
                    }
                }
            }.getOrElse { t ->
                Log.e(TAG, "probe failed", t)
                "ERROR:" + t.message
            }
            runCatching { File(dir, RESULT_FILE).writeText("$command\t$result\n", Charsets.UTF_8) }
            val tookMs = android.os.SystemClock.elapsedRealtime() - startedAt
            Log.i(TAG, "probe result=$result took_ms=$tookMs")
        }.start()
    }

    /** 開發用：清掉記憶體（反例、收據、能力索引、轉移）與通知收件箱。 */
    private fun clearMemory(context: Context): String {        val mem = File(context.filesDir, "mem")
        val files = mem.listFiles().orEmpty()
        val removed = files.count { it.delete() }
        NotificationInbox.clear()
        return "CLEARED files=$removed"
    }

    /** 開發用：列出目前的反例（H7 的資料來源，也是 adb 驗證用）。 */
    /**
     * 診斷包（H9）：把「它是誰、它現在什麼狀態、它最近做了什麼」寫成一個文字檔。
     *
     * 刻意**不含**畫面內容、指令原文或通知內容——只放結構與計數。
     * 使用者要回報問題時，把這個檔給出去就夠了。
     */
    private fun exportDiagnostics(context: Context): String {
        return runCatching {
            val dir = java.io.File(context.filesDir, "mem")
            fun lines(name: String): Int {
                val f = java.io.File(dir, name)
                if (!f.exists()) return 0
                return f.bufferedReader(Charsets.UTF_8).useLines { it.count { l -> l.isNotBlank() } }
            }

            val sb = StringBuilder()
            sb.appendLine("WAE 診斷包")
            sb.appendLine("time=" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()))
            sb.appendLine("pkg=" + context.packageName)
            sb.appendLine("version=" + runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("?"))
            sb.appendLine("android=" + android.os.Build.VERSION.RELEASE + " sdk=" + android.os.Build.VERSION.SDK_INT)
            sb.appendLine("device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            sb.appendLine("a11yBound=" + (com.wae.agent.core.WaeVisionAccessibilityService.activeInstance != null))
            sb.appendLine()
            sb.appendLine("[計數]")
            listOf("receipts.tsv", "refutations.tsv", "capabilities.tsv", "programs.tsv",
                "transitions.tsv", "frames.tsv").forEach { name ->
                sb.appendLine("  " + name + "=" + lines(name))
            }
            sb.appendLine("  twin=" + runCatching {
                val f = java.io.File(context.filesDir, "twin/steps.jsonl")
                if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
            }.getOrDefault(0))
            sb.appendLine()
            sb.appendLine("[最近 5 筆收據（只含效果與結果，不含指令原文）]")
            runCatching {
                val f = java.io.File(dir, "receipts.tsv")
                if (f.exists()) f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                    .takeLast(5)
                    .forEach { line ->
                        val c = line.split("\t")
                        if (c.size >= 7) sb.appendLine("  " + c[6] + "  " + c[4].take(70))
                    }
            }

            val shots = java.io.File(context.cacheDir, com.wae.agent.gen5.ShotProvider.DIR)
            shots.mkdirs()
            val out = java.io.File(shots, "diag.txt")
            out.writeText(sb.toString(), Charsets.UTF_8)
            "DIAG " + out.absolutePath + " bytes=" + out.length()
        }.getOrElse { t -> "ERROR:" + t.message }
    }

    /**
     * 約束可攜（G9）：把反例（禁令）匯出成可分享的純文字。
     *
     * 為什麼要有：使用者的「不要再這樣做」是自己的財產，換手機時不該重學一次。
     * 格式刻意簡單（TSV＋註解＋checksum）——不用 JSON，因為要能一眼看懂與手改。
     */
    private fun kbReport(context: Context, text: String): String {
        if (text.isEmpty()) return "ERROR:usage !kb <句子>"
        val kb = com.wae.agent.chat.KnowledgeHost.base(context)
        val hits = kb.retrieve(text, 3)
        if (hits.isEmpty()) return "KB text='$text' docs=" + kb.size() + " results=NONE"
        return "KB text='$text' docs=" + kb.size() + "\n" +
            hits.joinToString("\n") { "  " + it.id + " [" + it.kind + "] score=" + String.format("%.2f", it.score) }
    }

    private fun nounReport(context: Context, text: String): String {
        if (text.isEmpty()) return "ERROR:usage !noun <句子>"
        val r = NounHost.resolver(context).resolve(text) ?: return "NOUN text='$text' result=NONE（名詞與動詞都沒認得）"
        return "NOUN text='$text' type=" + r.type + " noun=" + r.noun +
            " verb=" + r.verb + " action=" + r.action +
            " categories=" + r.categories.joinToString(",") + " matched_by=" + r.matchedBy
    }

    private fun digestReport(context: Context): String {
        val service = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
            ?: return "DIGEST no_service"
        return runCatching { StepActor.digest(service, context) }
            .getOrElse { t -> "ERROR:" + t.message }
    }

    private fun lexiconCommand(context: Context, text: String): String {
        if (text.isEmpty()) return "ERROR:usage !lexicon <按鈕文字或 App 名稱>"
        val family = AppCategoryResolver.familyOfLabel(context, text)
        val lexicon = AppCategoryResolver.lexiconFor(context, "", text)
        val role = lexicon.roleOf(text)
        return "LEXICON text='$text' family=$family role=" + (role ?: "UNKNOWN") +
            " roles=" + lexicon.roles().size
    }

    private fun rulesCommand(context: Context, action: String): String = when (action) {
        "on" -> { RuleEngineHost.enabled = true; RuleEngineHost.status(context) }
        "off" -> { RuleEngineHost.enabled = false; RuleEngineHost.status(context) }
        "ops on" -> { RuleEngineHost.opsEnabled = true; RuleEngineHost.status(context) }
        "ops off" -> { RuleEngineHost.opsEnabled = false; RuleEngineHost.status(context) }
        else -> RuleEngineHost.status(context)
    }

    private fun judgeCommand(context: Context, command: String): String {
        if (command.isEmpty()) return "ERROR:usage !judge <句子>"
        val facts = RuleEngineHost.installedAppsFacts(context)
        val decision = RuleEngineHost.decide(context, command, facts)
        return "JUDGE " + RuleEngineHost.describe(decision)
    }

    private fun iconsSwitch(action: String): String = when (action) {
        "on" -> { IconChannel.enabled = true; "ICONS on" }
        "off" -> { IconChannel.enabled = false; "ICONS off（只認有文字的元件）" }
        else -> "ICONS " + if (IconChannel.enabled) "on" else "off"
    }

    /**
     * `!apps`：列出已安裝（可啟動）App 的數量與**類別分佈**，以及沒有類別的有幾個。
     *
     * 為什麼要這個：`InstalledAppCatalog` 的類別優先序是
     * metadata 表 → Android ApplicationInfo.category → 空字串。
     * 「空字串有幾個」直接決定「用類別挑 App」可不可靠。
     */
    private fun appsReport(context: Context, command: String): String {
        return runCatching {
            val apps = com.wae.agent.core.app.InstalledAppCatalog.load(context, force = true)
            val wanted = command.removePrefix(APPS_PREFIX).trim()
            if (wanted.isNotEmpty()) {
                val inCat = apps.filter { it.category.equals(wanted, ignoreCase = true) }
                if (inCat.isEmpty()) {
                    return@runCatching "APPS category=$wanted count=0"
                }
                return@runCatching "APPS category=$wanted count=" + inCat.size + " → " +
                    inCat.take(10).joinToString("、") { it.label + "(" + it.pkg + ")" }
            }
            val byCat = apps.groupBy { it.category.ifEmpty { "(未分類)" } }
                .mapValues { it.value.size }
                .toList().sortedByDescending { it.second }
            val unclassified = byCat.firstOrNull { it.first == "(未分類)" }?.second ?: 0
            val pct = if (apps.isEmpty()) 0 else (100 - unclassified * 100 / apps.size)
            "APPS total=" + apps.size + " categorized=" + pct + "% unclassified=" + unclassified +
                " | " + byCat.take(8).joinToString(", ") { it.first + "=" + it.second }
        }.getOrElse { t -> "ERROR:" + t.message }
    }

    /** 剪貼簿寫入（D14 探針）：`!copy 文字`。 */
    private fun copyToClipboard(context: Context, text: String): String {
        if (text.isEmpty()) return "ERROR:empty"
        return onMainThread(context) {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))
                "COPIED chars=" + text.length
            }.getOrElse { t -> "ERROR:" + t.message }
        }
    }

    /** 把動作丟到主執行緒做，等它完成（剪貼簿需要前景焦點）。 */
    private fun <T> onMainThread(context: Context, block: () -> T): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            result = block()
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return (result ?: "ERROR:timeout") as T
    }

    /** 剪貼簿讀取（D14 探針）：`!clip`。注意 Android 10+ 只讓前景 App 讀。 */
    private fun readClipboard(context: Context): String {
        return onMainThread(context) {
          runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                "CLIP empty"
            } else {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                "CLIP chars=" + text.length + " text=" + text.take(80)
            }
          }.getOrElse { t -> "ERROR:" + t.message }
        }
    }

    private fun exportConstraints(context: Context): String {
        return runCatching {
            val src = java.io.File(java.io.File(context.filesDir, "mem"), "refutations.tsv")
            val body = StringBuilder()
            if (src.exists()) {
                src.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank() && !line.startsWith("#")) body.append(line).append('\n')
                    }
                }
            }
            val rows = body.toString().split('\n').filter { it.isNotBlank() }
            val checksum = com.wae.core.memory.ConstraintBundle.checksum(
                com.wae.core.memory.ConstraintBundle.bodyOf(rows)
            )
            val header = buildString {
                appendLine("# WAE 約束匯出 v1")
                appendLine("# exported=" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()))
                appendLine("# device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                appendLine("# checksum=" + checksum)
                appendLine("# 每列：scope\t畫面指紋\t效果\t手段\t判決\t次數")
            }
            val shots = java.io.File(context.cacheDir, com.wae.agent.gen5.ShotProvider.DIR)
            shots.mkdirs()
            val out = java.io.File(shots, "constraints.txt")
            out.writeText(header + body.toString(), Charsets.UTF_8)
            "EXPORT rows=" + body.count { it == '\n' } + " checksum=" + checksum + " path=" + out.absolutePath
        }.getOrElse { t -> "ERROR:" + t.message }
    }

    /**
     * 匯入約束：讀 `files/import/constraints.txt`（使用者自己放進去），驗 checksum 後合併。
     *
     * 安全邊界：**只合併反例，不執行任何動作**；checksum 不對就整批拒絕。
     */
    private fun importConstraints(context: Context): String {
        return runCatching {
            val incoming = java.io.File(context.filesDir, "import/constraints.txt")
            if (!incoming.exists()) return@runCatching "ERROR:no_file files/import/constraints.txt"
            val lines = incoming.readLines(Charsets.UTF_8)
            val claimed = com.wae.core.memory.ConstraintBundle.claimedChecksumOf(lines)
                ?: return@runCatching "ERROR:no_checksum"
            val rows = com.wae.core.memory.ConstraintBundle.rowsOf(lines)
            if (!com.wae.core.memory.ConstraintBundle.verify(rows, claimed)) {
                return@runCatching "ERROR:checksum_mismatch"
            }
            val target = java.io.File(java.io.File(context.filesDir, "mem"), "refutations.tsv")
            target.parentFile?.mkdirs()
            val existing = if (target.exists()) {
                target.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.toMutableList()
            } else {
                mutableListOf()
            }
            val header = listOf(
                "# scope\tframe_fp\teffect\tmeans\tverdict\tattribution\tcount\tlast_ts\tapp_version"
            )
            var added = 0
            for (row in rows) {
                val cells = row.split('\t')
                if (cells.size < 9) continue
                val same = existing.any { e ->
                    val c = e.split('\t')
                    c.size >= 4 && c[1] == cells[1] && c[2] == cells[2] && c[3] == cells[3]
                }
                if (!same) {
                    existing.add(row)
                    added++
                }
            }
            target.writeText((header + existing).joinToString("\n") + "\n", Charsets.UTF_8)
            "IMPORT rows=" + rows.size + " added=" + added
        }.getOrElse { t -> "ERROR:" + t.message }
    }

    private fun sha256(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /** 分享診斷包（H9 完成版）：把 cache/shots/diag.txt 交給使用者選的 App。 */
    private fun shareDiagnostics(context: Context): String {
        val file = java.io.File(java.io.File(context.cacheDir, com.wae.agent.gen5.ShotProvider.DIR), "diag.txt")
        if (!file.exists()) {
            val built = exportDiagnostics(context)
            if (built.startsWith("ERROR")) return built
        }
        if (!file.exists()) return "ERROR:no diag file"
        return runCatching {
            val contentUri = android.net.Uri.parse(
                "content://" + com.wae.agent.gen5.ShotProvider.authority(context.packageName) + "/diag.txt"
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "WAE 診斷包")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = android.content.Intent.createChooser(send, "分享診斷包")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            "DIAG_SHARE " + contentUri
        }.getOrElse { t -> "ERROR:" + t.message }
    }

    /** 分享診斷包（H9 完成版）用的檔案（cache/shots 才給 provider 讀）。 */
    private fun postSelfNotification(context: Context): String {
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val channelId = "wae_probe"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "WAE probe", android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val notification = android.app.Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("WAE 測試通知")
                .setContentText("這是用來驗證通知收件箱的訊息")
                .build()
            manager.notify(9001, notification)
            "NOTIFIED"
        }.getOrElse { t -> "ERROR:" + t.message }
    }

    private fun refutationsToText(context: Context): String {
        val config = com.wae.core.config.MapConfig(emptyMap())
        val clock = object : com.wae.core.time.Clock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }
        val memory = com.wae.core.memory.FileMemory(File(context.filesDir, "mem"), config, clock)
        val entries = com.wae.core.memory.RefutationView.entries(memory.refutations())
        if (entries.isEmpty()) return "REFS 0"
        return "REFS " + entries.size + "\n" + entries.joinToString("\n") { it.text }
    }

    private const val ADMIN_CLEAR = "!clear"
    private const val ADMIN_SELFTEST = "!selftest"
    private const val ADMIN_REFS = "!refs"
    private const val ADMIN_NOTIFY = "!notify"
    private const val ADMIN_DIAG = "!diag"
    private const val ADMIN_DIAG_SHARE = "!diagshare"
    private const val ADMIN_EXPORT = "!export"
    private const val ADMIN_IMPORT = "!import"

    /** 剪貼簿探針（D14）：`!copy 文字` 寫入、`!clip` 讀回。 */
    private const val CLIP_COPY_PREFIX = "!copy "
    private const val CLIP_READ = "!clip"

    /** App 目錄探針（給「用類別挑 App」驗證用）。 */
    private const val APPS_SUMMARY = "!apps"
    private const val APPS_PREFIX = "!apps "

    /** 圖示偵測開關：`!icons on|off|status`（小模型可關，計畫 §12）。 */
    private const val ICONS_PREFIX = "!icons"

    /** 規則引擎：`!rules on|off|status`、`!judge <句子>`。 */
    private const val RULES_PREFIX = "!rules"
    private const val JUDGE_PREFIX = "!judge "

    /** 詞庫探針：`!lexicon 購物車` 看它會被歸到哪個角色／家族。 */
    private const val LEXICON_PREFIX = "!lexicon "

    /** 畫面摘要：`!digest` 看它現在認得哪些元素與角色。 */
    private const val DIGEST_CMD = "!digest"

    /** `!facts`：這支手機有什麼（App／分類／權限／硬體／能力表）。 */
    private const val FACTS_CMD = "!facts"

    /** `!noun 我想找AI資料`：看名詞被歸到哪個型別、會交給哪類 App。 */
    private const val NOUN_PREFIX = "!noun "

    /** `!kb 句子`：看 always-retrieve 會檢索到什麼（驗證「懂不懂換句話說」）。 */
    private const val KB_PREFIX = "!kb "
}
