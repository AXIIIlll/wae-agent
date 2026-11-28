"""Gen5Runtime：15 處 runCatching → Degrade（開頭＋尾巴成對）。"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\gen5\Gen5Runtime.kt")
src = P.read_text(encoding="utf-8")
n = 0


def sub(old: str, new: str) -> None:
    global src, n
    if old not in src:
        print("MISS:", repr(old.splitlines()[0][:70]))
        return
    src = src.replace(old, new, 1)
    n += 1


# 1. 剪貼簿寫入
sub(
    "        return runCatching {\n"
    "            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)\n"
    "                as android.content.ClipboardManager\n"
    '            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))\n'
    "            true\n"
    "        }.getOrDefault(false)",
    '        return com.wae.core.util.Degrade.orElse("gen5:copyClipboard", false) {\n'
    "            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)\n"
    "                as android.content.ClipboardManager\n"
    '            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))\n'
    "            true\n"
    "        }",
)

# 2. 指令記錄
sub(
    "        runCatching {\n"
    '            java.io.File(context.filesDir, "commands.jsonl").appendText(',
    '        com.wae.core.util.Degrade.orNull("gen5:logCommand") {\n'
    '            java.io.File(context.filesDir, "commands.jsonl").appendText(',
)

# 3. 規則展開時的 service 取值
sub(
    "                        val service = runCatching {\n"
    "                            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
    "                        }.getOrNull()",
    '                        val service = com.wae.core.util.Degrade.orNull("gen5:activeService") {\n'
    "                            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
    "                        }",
)

# 4. 內容數量
sub(
    "                    val count = runCatching {\n"
    "                        val svc = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
    "                        if (svc == null) 0 else StepActor.contentCount(svc, context)\n"
    "                    }.getOrDefault(0)",
    '                    val count = com.wae.core.util.Degrade.orElse("gen5:contentCount", 0) {\n'
    "                        val svc = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
    "                        if (svc == null) 0 else StepActor.contentCount(svc, context)\n"
    "                    }",
)

# 5. App 內搜尋
sub(
    "                val ok = runCatching {\n"
    "                    val svc = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
    "                    svc != null && query.isNotEmpty() && StepActor.searchHere(svc, query, context)\n"
    "                }.getOrDefault(false)",
    '                val ok = com.wae.core.util.Degrade.orElse("gen5:inAppSearch", false) {\n'
    "                    val svc = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
    "                    svc != null && query.isNotEmpty() && StepActor.searchHere(svc, query, context)\n"
    "                }",
)

# 6. overlay 顯示
sub(
    "            runCatching {\n"
    "                if (text.isEmpty()) overlay?.hide() else overlay?.show(text)\n"
    "            }",
    '            com.wae.core.util.Degrade.orNull("gen5:overlay") {\n'
    "                if (text.isEmpty()) overlay?.hide() else overlay?.show(text)\n"
    "            }",
)

# 7. 權限頁（一般）
sub(
    "            runCatching {\n"
    "                val intent = android.content.Intent(\n"
    "                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS\n"
    "                )",
    '            com.wae.core.util.Degrade.orNull("gen5:openAppSettings") {\n'
    "                val intent = android.content.Intent(\n"
    "                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS\n"
    "                )",
)

# 8. 權限頁（寫入設定）
sub(
    "            runCatching {\n"
    "                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)",
    '            com.wae.core.util.Degrade.orNull("gen5:openWriteSettings") {\n'
    "                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)",
)

# 9. agent.run（動態錯誤訊息）
sub(
    "        val outcome = runCatching { agent.run(command) }.getOrElse { t ->\n"
    '            Log.e(TAG, "run failed", t)\n'
    '            Outcome.Unknown("exception:" + t.message)\n'
    "        }",
    '        val outcome = com.wae.core.util.Degrade.orElse("gen5:agentRun", Outcome.Unknown("exception:degraded")) {\n'
    "            agent.run(command)\n"
    "        }",
)

# 10. 計畫 dryrun log
sub(
    "        runCatching {\n"
    "            val probeFrame = rawSource.capture()",
    '        com.wae.core.util.Degrade.orNull("gen5:dryRunPlan") {\n'
    "            val probeFrame = rawSource.capture()",
)

# 11/12. 已安裝 App（兩處相同）
apps_old = (
    "        val apps = runCatching {\n"
    "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
    "        }.getOrDefault(emptyList())"
)
apps_new = (
    '        val apps = com.wae.core.util.Degrade.orEmpty("gen5:installedApps") {\n'
    "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
    "        }"
)
sub(apps_old, apps_new)
sub(apps_old, apps_new)

# 13. uriCapable
sub(
    "    private fun uriCapable(pkg: String): Boolean = runCatching {\n"
    "        BundledData.uriSearchLines().any { line ->\n"
    "            !line.startsWith(\"#\") && line.split('\\t').firstOrNull()?.trim() == pkg\n"
    "        }\n"
    "    }.getOrDefault(false)",
    '    private fun uriCapable(pkg: String): Boolean =\n'
    '        com.wae.core.util.Degrade.orElse("gen5:uriCapable", false) {\n'
    "            BundledData.uriSearchLines().any { line ->\n"
    "                !line.startsWith(\"#\") && line.split('\\t').firstOrNull()?.trim() == pkg\n"
    "            }\n"
    "        }",
)

# 14. tunables 讀取
sub(
    "        runCatching {\n"
    "            context.assets.open(ASSET_TUNABLES).bufferedReader(Charsets.UTF_8).use { reader ->",
    '        com.wae.core.util.Degrade.orNull("gen5:loadTunables") {\n'
    "            context.assets.open(ASSET_TUNABLES).bufferedReader(Charsets.UTF_8).use { reader ->",
)

# 15. 角色詞庫
sub(
    "        val fromAssets = runCatching {\n"
    "            context.assets.open(ASSET_LEXICON).bufferedReader(Charsets.UTF_8).use { reader ->\n"
    "                RoleLexicon.parse(reader.readLines().asSequence())\n"
    "            }\n"
    "        }.getOrNull()",
    '        val fromAssets = com.wae.core.util.Degrade.orNull("gen5:roleLexicon") {\n'
    "            context.assets.open(ASSET_LEXICON).bufferedReader(Charsets.UTF_8).use { reader ->\n"
    "                RoleLexicon.parse(reader.readLines().asSequence())\n"
    "            }\n"
    "        }",
)

P.write_text(src, encoding="utf-8")
print("applied:", n)
print("remaining:", src.count("runCatching"))
