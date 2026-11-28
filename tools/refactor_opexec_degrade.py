"""OpExecutor：runCatching → Degrade（開頭與尾巴成對替換，避免上一輪的全域誤刪）。"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\gen5\OpExecutor.kt")
src = P.read_text(encoding="utf-8")
n = 0


def sub(old: str, new: str) -> None:
    global src, n
    if old not in src:
        print("MISS:", old.splitlines()[0][:70])
        return
    src = src.replace(old, new, 1)
    n += 1


# ── 1. dispatch（裸 runCatching，回傳值沒用到）────────────────────────────
sub(
    "        runCatching {\n            when (op.id) {",
    '        com.wae.core.util.Degrade.orNull("opExec:dispatch") {\n            when (op.id) {',
)

# ── 2. launchPkg ──────────────────────────────────────────────────────────
sub(
    "    fun launchPkg(context: Context, pkg: String): Boolean = runCatching {\n"
    "        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false\n"
    "        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "        context.startActivity(intent)\n"
    "        true\n"
    "    }.getOrDefault(false)",
    '    fun launchPkg(context: Context, pkg: String): Boolean =\n'
    '        com.wae.core.util.Degrade.orElse("opExec:launchPkg", false) {\n'
    "            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@orElse false\n"
    "            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "            context.startActivity(intent)\n"
    "            true\n"
    "        }",
)

# ── 3. 兩處 uriSearch template 讀取（同一段文字出現兩次，逐一處理）────────
tmpl_old = (
    "        val template = runCatching {\n"
    "            com.wae.core.config.BundledData.uriSearchLines().firstOrNull { line ->\n"
    "                !line.startsWith(\"#\") && line.split('\\t').firstOrNull()?.trim() == pkg\n"
    "            }?.split('\\t')?.getOrNull(1)?.trim()\n"
    "        }.getOrNull()"
)
tmpl_new = (
    '        val template = com.wae.core.util.Degrade.orNull("opExec:uriTemplate") {\n'
    "            com.wae.core.config.BundledData.uriSearchLines().firstOrNull { line ->\n"
    "                !line.startsWith(\"#\") && line.split('\\t').firstOrNull()?.trim() == pkg\n"
    "            }?.split('\\t')?.getOrNull(1)?.trim()\n"
    "        }"
)
sub(tmpl_old, tmpl_new)
sub(tmpl_old, tmpl_new)   # 第二次出現

# ── 4. 前景套件讀取 ───────────────────────────────────────────────────────
sub(
    "        val pkg = runCatching {\n"
    "            service?.rootInActiveWindow?.packageName?.toString()\n"
    "        }.getOrNull().orEmpty()",
    '        val pkg = com.wae.core.util.Degrade.orElse("opExec:foregroundPkg", "") {\n'
    "            service?.rootInActiveWindow?.packageName?.toString() ?: \"\"\n"
    "        }",
)

# ── 5. 兩處 startActivity(VIEW uri) 的布林結果 ───────────────────────────
view1_old = (
    "        return runCatching {\n"
    "            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))\n"
    "                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "            intent.setPackage(pkg)\n"
    "            context.startActivity(intent)\n"
    "            true\n"
    "        }.getOrDefault(false)"
)
view1_new = (
    '        return com.wae.core.util.Degrade.orElse("opExec:uriSearch", false) {\n'
    "            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))\n"
    "                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "            intent.setPackage(pkg)\n"
    "            context.startActivity(intent)\n"
    "            true\n"
    "        }"
)
sub(view1_old, view1_new)

view2_old = (
    "        return runCatching {\n"
    "            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))\n"
    "                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "            if (pkg.isNotEmpty()) intent.setPackage(pkg)\n"
    "            context.startActivity(intent)\n"
    "            true\n"
    "        }.getOrDefault(false)"
)
view2_new = (
    '        return com.wae.core.util.Degrade.orElse("opExec:openUri", false) {\n'
    "            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))\n"
    "                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "            if (pkg.isNotEmpty()) intent.setPackage(pkg)\n"
    "            context.startActivity(intent)\n"
    "            true\n"
    "        }"
)
sub(view2_old, view2_new)

P.write_text(src, encoding="utf-8")
print("applied:", n)
print("remaining:", src.count("runCatching"))
