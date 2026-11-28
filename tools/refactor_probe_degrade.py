"""Gen5Probe：把 runCatching 改成 Degrade（明確成對替換，找不到就報錯）。"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\gen5\Gen5Probe.kt")
src = P.read_text(encoding="utf-8")

PAIRS = [
    # 版本號：runCatching { ... }.getOrDefault("?")
    (
        '            sb.appendLine("version=" + runCatching {\n'
        '                context.packageManager.getPackageInfo(context.packageName, 0).versionName\n'
        '            }.getOrDefault("?"))',
        '            sb.appendLine("version=" + com.wae.core.util.Degrade.orElse("probe:version", "?") {\n'
        '                context.packageManager.getPackageInfo(context.packageName, 0).versionName\n'
        '            })',
    ),
    # twin 步數：runCatching { ... }.getOrDefault(0)
    (
        '            sb.appendLine("  twin=" + runCatching {\n'
        '                val f = java.io.File(context.filesDir, "twin/steps.jsonl")\n'
        '                if (f.exists()) f.readLines().count { it.isNotBlank() } else 0\n'
        '            }.getOrDefault(0))',
        '            sb.appendLine("  twin=" + com.wae.core.util.Degrade.orElse("probe:twin", 0) {\n'
        '                val f = java.io.File(context.filesDir, "twin/steps.jsonl")\n'
        '                if (f.exists()) f.readLines().count { it.isNotBlank() } else 0\n'
        '            })',
    ),
    # 最近收據：裸 runCatching（結果沒用到）
    (
        '            runCatching {\n'
        '                val f = java.io.File(dir, "receipts.tsv")',
        '            com.wae.core.util.Degrade.orNull("probe:recentReceipts") {\n'
        '                val f = java.io.File(dir, "receipts.tsv")',
    ),
    # 剪貼簿寫入
    (
        '            runCatching {\n'
        '                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)\n'
        '                    as android.content.ClipboardManager\n'
        '                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))\n'
        '                "COPIED chars=" + text.length\n'
        '            }.getOrElse { t -> "ERROR:" + t.message }',
        '            com.wae.core.util.Degrade.orElse("probe:copyToClipboard", "ERROR") {\n'
        '                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)\n'
        '                    as android.content.ClipboardManager\n'
        '                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))\n'
        '                "COPIED chars=" + text.length\n'
        '            }',
    ),
    # 剪貼簿讀取
    (
        '          runCatching {\n'
        '            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)',
        '          com.wae.core.util.Degrade.orElse("probe:readClipboard", "ERROR") {\n'
        '            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)',
    ),
    (
        '          }.getOrElse { t -> "ERROR:" + t.message }',
        '          }',
    ),
    # 匯出約束
    (
        '    private fun exportConstraints(context: Context): String {\n'
        '        return runCatching {',
        '    private fun exportConstraints(context: Context): String {\n'
        '        return com.wae.core.util.Degrade.orElse("probe:exportConstraints", "ERROR") {',
    ),
    # 匯入約束（有三個 return@runCatching，要一起改）
    (
        '    private fun importConstraints(context: Context): String {\n'
        '        return runCatching {',
        '    private fun importConstraints(context: Context): String {\n'
        '        return com.wae.core.util.Degrade.orElse("probe:importConstraints", "ERROR") {',
    ),
    # App 報表
    (
        '    private fun appsReport(context: Context, command: String): String {\n'
        '        return runCatching {',
        '    private fun appsReport(context: Context, command: String): String {\n'
        '        return com.wae.core.util.Degrade.orElse("probe:appsReport", "ERROR") {',
    ),
    # 分享診斷包
    (
        '        return runCatching {\n'
        '            val contentUri = android.net.Uri.parse(',
        '        return com.wae.core.util.Degrade.orElse("probe:shareDiagnostics", "ERROR") {\n'
        '            val contentUri = android.net.Uri.parse(',
    ),
    # 自我通知
    (
        '    private fun postSelfNotification(context: Context): String {\n'
        '        return runCatching {',
        '    private fun postSelfNotification(context: Context): String {\n'
        '        return com.wae.core.util.Degrade.orElse("probe:selfNotification", "ERROR") {',
    ),
]

for old, new in PAIRS:
    if old not in src:
        print("MISS:", old.splitlines()[0][:60])
        continue
    src = src.replace(old, new, 1)

# 所有剩下的 `.getOrElse { t -> "ERROR:" + t.message }` 尾巴 → 純 } (對應前面已換成 orElse)
src = src.replace('\n        }.getOrElse { t -> "ERROR:" + t.message }', '\n        }')
src = src.replace('\n            }.getOrElse { t -> "ERROR:" + t.message }', '\n            }')
# importConstraints 內的標籤
src = src.replace('return@runCatching "ERROR:no_file files/import/constraints.txt"',
                  'return@orElse "ERROR:no_file files/import/constraints.txt"')
src = src.replace('?: return@runCatching "ERROR:no_checksum"', '?: return@orElse "ERROR:no_checksum"')
src = src.replace('return@runCatching "ERROR:checksum_mismatch"', 'return@orElse "ERROR:checksum_mismatch"')
# appsReport 內的標籤
src = src.replace('return@runCatching "APPS category=$wanted count=0"',
                  'return@orElse "APPS category=$wanted count=0"')
src = src.replace('return@runCatching "APPS category=$wanted count=" + inCat.size + " → " +',
                  'return@orElse "APPS category=$wanted count=" + inCat.size + " → " +')

P.write_text(src, encoding="utf-8")
print("remaining runCatching:", src.count("runCatching"))
