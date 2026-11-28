"""Service：第一批 19 處（開頭 1:1 替換，帶尾巴的成對處理）。"""
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


# ── 開頭 1:1（結果值沒被使用）────────────────────────────────────────────
sub(
    "                runCatching {\n"
    '                    val memoFile = java.io.File(filesDir, "decision_memo.json")',
    '                com.wae.core.util.Degrade.orNull("service:loadDecisionMemo") {\n'
    '                    val memoFile = java.io.File(filesDir, "decision_memo.json")',
)
sub(
    "                runCatching {\n"
    "                    val src = event.source",
    '                com.wae.core.util.Degrade.orNull("service:incrementalPerceive") {\n'
    "                    val src = event.source",
)
sub(
    "            runCatching {\n"
    "                if (voiceProgress == null) voiceProgress = com.wae.agent.core.VoiceProgressOverlay(this)",
    '            com.wae.core.util.Degrade.orNull("service:voiceProgressInit") {\n'
    "                if (voiceProgress == null) voiceProgress = com.wae.agent.core.VoiceProgressOverlay(this)",
)
sub(
    "        runCatching {\n"
    "            val opts = optionSet.selectableOptions\n"
    "            if (opts.isEmpty()) return",
    '        com.wae.core.util.Degrade.orNull("service:groundingTrace") {\n'
    "            val opts = optionSet.selectableOptions\n"
    "            if (opts.isEmpty()) return@orNull",
)
sub(
    "                        runCatching {\n"
    "                            com.wae.agent.core.runtime.ExtremeRuntimeHooks.onGoalDone(",
    '                        com.wae.core.util.Degrade.orNull("service:onGoalDone") {\n'
    "                            com.wae.agent.core.runtime.ExtremeRuntimeHooks.onGoalDone(",
)
sub(
    "                    runCatching {\n"
    "                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onGoalFailed(",
    '                    com.wae.core.util.Degrade.orNull("service:onGoalFailed") {\n'
    "                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onGoalFailed(",
)
sub(
    "                runCatching {\n"
    "                    val n = com.wae.agent.core.grounding.GroundingTraceRecorder.bufferedCount()",
    '                com.wae.core.util.Degrade.orNull("service:groundingFlush") {\n'
    "                    val n = com.wae.agent.core.grounding.GroundingTraceRecorder.bufferedCount()",
)
sub(
    "                    runCatching {\n"
    '                        voiceProgress?.update(if (finalOk) "完成" else "失敗")',
    '                    com.wae.core.util.Degrade.orNull("service:voiceProgressDone") {\n'
    '                        voiceProgress?.update(if (finalOk) "完成" else "失敗")',
)
sub(
    "                runCatching {\n"
    "                    com.wae.agent.voice.SessionHistory.markLastResult(this, finalOk)",
    '                com.wae.core.util.Degrade.orNull("service:historyResult") {\n'
    "                    com.wae.agent.voice.SessionHistory.markLastResult(this, finalOk)",
)
sub(
    "                runCatching {\n"
    "                    com.wae.agent.core.action.ActionTelemetry.persist(this@WaeVisionAccessibilityService)",
    '                com.wae.core.util.Degrade.orNull("service:persistTelemetry") {\n'
    "                    com.wae.agent.core.action.ActionTelemetry.persist(this@WaeVisionAccessibilityService)",
)
sub(
    "                runCatching {\n"
    "                    val ctx = this@WaeVisionAccessibilityService\n"
    "                    val lowEnd = com.wae.agent.core.automaton.GraphBudget.isLowEndDevice(ctx)",
    '                com.wae.core.util.Degrade.orNull("service:compileGraph") {\n'
    "                    val ctx = this@WaeVisionAccessibilityService\n"
    "                    val lowEnd = com.wae.agent.core.automaton.GraphBudget.isLowEndDevice(ctx)",
)
sub(
    "                        runCatching {\n"
    "                            val neg = com.wae.agent.core.action.NegativeEdgeStore",
    '                        com.wae.core.util.Degrade.orNull("service:graphHealth") {\n'
    "                            val neg = com.wae.agent.core.action.NegativeEdgeStore",
)
sub(
    "                    runCatching {\n"
    "                        for (p in pkgs.take(3)) {",
    '                    com.wae.core.util.Degrade.orNull("service:buildIndex") {\n'
    "                        for (p in pkgs.take(3)) {",
)

# ── 帶尾巴（成對）────────────────────────────────────────────────────────
sub(
    "            val stepKind = runCatching { ActionKind.valueOf(step.action) }.getOrDefault(goal.actionKind)",
    '            val stepKind = com.wae.core.util.Degrade.orElse("service:stepKind", goal.actionKind) {\n'
    "                ActionKind.valueOf(step.action)\n"
    "            }",
)
sub(
    "        automatonCache = runCatching {\n"
    "            com.wae.agent.core.automaton.AutomatonStore.load(this, pkg)\n"
    "        }.getOrNull()",
    '        automatonCache = com.wae.core.util.Degrade.orNull("service:loadAutomaton") {\n'
    "            com.wae.agent.core.automaton.AutomatonStore.load(this, pkg)\n"
    "        }",
)
sub(
    "        return runCatching {\n"
    "            overlay.show()\n"
    "            floatingOverlay = overlay\n"
    '            Log.i("WAE_OVERLAY", "toggled on")\n'
    "            true\n"
    "        }.getOrDefault(false)",
    '        return com.wae.core.util.Degrade.orElse("service:showOverlay", false) {\n'
    "            overlay.show()\n"
    "            floatingOverlay = overlay\n"
    '            Log.i("WAE_OVERLAY", "toggled on")\n'
    "            true\n"
    "        }",
)
sub(
    "                    val toggleGoalDetected = runCatching {\n"
    "                        val ti = com.wae.agent.core.nlu.ToggleIntent.parseForToggle(goal.goalText, goal.actionKind)",
    '                    val toggleGoalDetected = com.wae.core.util.Degrade.orElse("service:toggleDetected", false) {\n'
    "                        val ti = com.wae.agent.core.nlu.ToggleIntent.parseForToggle(goal.goalText, goal.actionKind)",
)
sub(
    "                        ti != null\n"
    "                    }.getOrDefault(false)",
    "                        ti != null\n"
    "                    }",
)
sub(
    "                    val onLauncher = runCatching {\n"
    "                        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)",
    '                    val onLauncher = com.wae.core.util.Degrade.orElse("service:onLauncher", false) {\n'
    "                        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)",
)
sub(
    "                            .any { it.activityInfo.packageName == (rootInActiveWindow?.packageName?.toString() ?: \"\") }\n"
    "                    }.getOrDefault(false)",
    "                            .any { it.activityInfo.packageName == (rootInActiveWindow?.packageName?.toString() ?: \"\") }\n"
    "                    }",
)

P.write_text(src, encoding="utf-8")
print("applied:", n)
print("remaining:", src.count("runCatching"))
