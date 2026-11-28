"""Service：第二批（剩下的 12 處，開頭替換＋少數尾巴）。"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\WaeVisionAccessibilityService.kt")
src = P.read_text(encoding="utf-8")
n = 0


def sub(old: str, new: str, count: int = 1) -> None:
    global src, n
    if old not in src:
        print("MISS:", repr(old.splitlines()[0][:70]))
        return
    src = src.replace(old, new, count)
    n += count


# 收據讀取（尾巴 getOrDefault(emptySet()) ＋ 內含非局部 return）
sub(
    "    private fun knownPackagesFromReceipts(): Set<String> = runCatching {\n"
    '        val file = java.io.File(filesDir, "mem/receipts.tsv")\n'
    "        if (!file.exists()) return emptySet()",
    '    private fun knownPackagesFromReceipts(): Set<String> =\n'
    '        com.wae.core.util.Degrade.orElse("service:knownPkgs", emptySet()) {\n'
    '        val file = java.io.File(filesDir, "mem/receipts.tsv")\n'
    "        if (!file.exists()) return@orElse emptySet()",
)
sub(
    "                .toSet()\n"
    "        }\n"
    "    }.getOrDefault(emptySet())",
    "                .toSet()\n"
    "        }\n"
    "    }",
)

# 充電狀態
sub(
    "            runCatching {\n"
    "                val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager\n"
    "                bm.isCharging\n"
    "            }.getOrDefault(false)",
    '            com.wae.core.util.Degrade.orElse("service:charging", false) {\n'
    "                val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager\n"
    "                bm.isCharging\n"
    "            }",
)

# 以下 9 處都是「結果值不使用」的觀測／telemetry 區塊：開頭 1:1 替換
sub(
    "            runCatching {\n"
    "                val sigs = optionSetW0.pageSpineSigs.toList()",
    '            com.wae.core.util.Degrade.orNull("service:onLoopStart") {\n'
    "                val sigs = optionSetW0.pageSpineSigs.toList()",
)
sub(
    "                runCatching {\n"
    "                    val dynTexts = optionSetW0.selectableOptions",
    '                com.wae.core.util.Degrade.orNull("service:dynamicUiClassify") {\n'
    "                    val dynTexts = optionSetW0.selectableOptions",
)
sub(
    "                runCatching {\n"
    "                    val pkgNow = optionSetW0.packageName\n"
    "                    val auto = automatonFor(pkgNow)",
    '                com.wae.core.util.Degrade.orNull("service:stateSnapshot") {\n'
    "                    val pkgNow = optionSetW0.packageName\n"
    "                    val auto = automatonFor(pkgNow)",
)
sub(
    "                                    runCatching {\n"
    "                                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onDispatch(",
    '                                    com.wae.core.util.Degrade.orNull("service:onDispatch") {\n'
    "                                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onDispatch(",
)
sub(
    "                        runCatching {\n"
    "                            performGlobalAction(\n"
    "                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK",
    '                        com.wae.core.util.Degrade.orNull("service:backRecovery") {\n'
    "                            performGlobalAction(\n"
    "                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK",
)
sub(
    "        runCatching {\n"
    "            val deadline = SystemClock.uptimeMillis() + 900L",
    '        com.wae.core.util.Degrade.orNull("service:waitForResponse") {\n'
    "            val deadline = SystemClock.uptimeMillis() + 900L",
)
sub(
    "        runCatching {\n"
    "            val selKey = WaeV26PipelineOrchestrator.candidateSelectorKey(chosen)",
    '        com.wae.core.util.Degrade.orNull("service:recordNegativeEdge") {\n'
    "            val selKey = WaeV26PipelineOrchestrator.candidateSelectorKey(chosen)",
)
sub(
    "        runCatching {\n"
    "            for (idx in rawList.indices) {",
    '        com.wae.core.util.Degrade.orNull("service:pairCheckables") {\n'
    "            for (idx in rawList.indices) {",
)
sub(
    "            runCatching {\n"
    "                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }",
    '            com.wae.core.util.Degrade.orNull("service:collectText") {\n'
    "                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }",
)

# 內層 stateId
sub(
    "            val stateId = runCatching {\n"
    "                automatonFor(optionSet.packageName)?.matchState(",
    '            val stateId = com.wae.core.util.Degrade.orElse("service:matchStateForEdge", "") {\n'
    "                automatonFor(optionSet.packageName)?.matchState(",
)
sub(
    "                )?.id ?: \"\"\n"
    "            }.getOrDefault(\"\")",
    "                )?.id ?: \"\"\n"
    "            }",
)

P.write_text(src, encoding="utf-8")
print("applied:", n)
print("remaining:", src.count("runCatching"))
