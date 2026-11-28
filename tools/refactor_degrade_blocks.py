"""DecisionHead ＋ A11yScreenGateway 剩下的多行區塊（開頭與尾巴成對替換）。"""
from pathlib import Path

D = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\decision\DecisionHead.kt")
G = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\predicate\A11yScreenGateway.kt")

DH = [
    # 載入權重後的 log
    (
        "        runCatching {\n"
        "            android.util.Log.i(\n"
        "                TAG,\n"
        '                "loaded enabled=${active()} weights=${weightCount()} " +',
        '        com.wae.core.util.Degrade.orNull("decisionHead:logLoaded") {\n'
        "            android.util.Log.i(\n"
        "                TAG,\n"
        '                "loaded enabled=${active()} weights=${weightCount()} " +',
    ),
    # 候選加分 log
    (
        "            runCatching {\n"
        "                android.util.Log.i(\n"
        "                    TAG,\n"
        '                    "cand bonus=${"%.2f".format(bonus)} " +',
        '            com.wae.core.util.Degrade.orNull("decisionHead:logCand") {\n'
        "                android.util.Log.i(\n"
        "                    TAG,\n"
        '                    "cand bonus=${"%.2f".format(bonus)} " +',
    ),
    # 邊特徵
    (
        "        val f = runCatching { edgeFeatures(hint, goalOp, e, negativePenalty, currentState) }\n"
        "            .getOrNull() ?: return 0.0",
        '        val f = com.wae.core.util.Degrade.orNull("decisionHead:edgeFeatures") {\n'
        "            edgeFeatures(hint, goalOp, e, negativePenalty, currentState)\n"
        "        } ?: return 0.0",
    ),
    # 邊加分 log
    (
        "            runCatching {\n"
        "                android.util.Log.i(\n"
        "                    TAG,\n"
        '                    "edge bonus=${"%.2f".format(bonus)} selector=\'${e.selector.take(24)}\' " +',
        '            com.wae.core.util.Degrade.orNull("decisionHead:logEdge") {\n'
        "                android.util.Log.i(\n"
        "                    TAG,\n"
        '                    "edge bonus=${"%.2f".format(bonus)} selector=\'${e.selector.take(24)}\' " +',
    ),
    # 樣本傾印（內含 `return` → 要改成 return@orNull）
    (
        "        runCatching {\n"
        "            if (options.isEmpty()) return\n",
        '        com.wae.core.util.Degrade.orNull("decisionHead:dumpSamples") {\n'
        "            if (options.isEmpty()) return@orNull\n",
    ),
]

GW = [
    # 目標 App 解析
    (
        "            val resolved = runCatching {\n"
        "                WaeOpenSourceDatabaseRegistry.resolveTargetAppFromDatabaseOrDevice(context, target)\n"
        "            }.getOrNull()",
        '            val resolved = com.wae.core.util.Degrade.orNull("gateway:resolveTarget") {\n'
        "                WaeOpenSourceDatabaseRegistry.resolveTargetAppFromDatabaseOrDevice(context, target)\n"
        "            }",
    ),
    # 搜尋：第一次送出
    (
        "            runCatching {\n"
        "                DeviceIO.perform(editNode, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())\n"
        "            }\n"
        "            Thread.sleep(700)",
        '            com.wae.core.util.Degrade.orNull("gateway:imeEnter") {\n'
        "                DeviceIO.perform(editNode, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())\n"
        "            }\n"
        "            Thread.sleep(700)",
    ),
    # 搜尋：重試送出
    (
        "                runCatching {\n"
        "                    DeviceIO.perform(editNode, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())\n"
        "                }\n"
        "                Thread.sleep(600)",
        '                com.wae.core.util.Degrade.orNull("gateway:imeEnterRetry") {\n'
        "                    DeviceIO.perform(editNode, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())\n"
        "                }\n"
        "                Thread.sleep(600)",
    ),
    # 啟動 App
    (
        "        val launched = runCatching {\n"
        "            WaeOpenSourceDatabaseRegistry.launchAppByPackageName(context, pkg)\n"
        "        }.getOrDefault(false)",
        '        val launched = com.wae.core.util.Degrade.orElse("gateway:launchApp", false) {\n'
        "            WaeOpenSourceDatabaseRegistry.launchAppByPackageName(context, pkg)\n"
        "        }",
    ),
    # 回根頁：BACK
    (
        "                runCatching {\n"
        "                    service.performGlobalAction(\n"
        "                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK\n"
        "                    )\n"
        "                }\n"
        "                Thread.sleep(450)",
        '                com.wae.core.util.Degrade.orNull("gateway:back") {\n'
        "                    service.performGlobalAction(\n"
        "                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK\n"
        "                    )\n"
        "                }\n"
        "                Thread.sleep(450)",
    ),
    # 回根頁：取得 launch intent
    (
        "        val intent = runCatching {\n"
        "            context.packageManager.getLaunchIntentForPackage(pkg)\n"
        "        }.getOrNull()",
        '        val intent = com.wae.core.util.Degrade.orNull("gateway:launchIntent") {\n'
        "            context.packageManager.getLaunchIntentForPackage(pkg)\n"
        "        }",
    ),
    # 回根頁：重新啟動
    (
        "        val relaunched = runCatching {\n"
        "            intent.addFlags(",
        '        val relaunched = com.wae.core.util.Degrade.orElse("gateway:relaunch", false) {\n'
        "            intent.addFlags(",
    ),
    (
        "            context.startActivity(intent)\n"
        "            true\n"
        "        }.getOrDefault(false)",
        "            context.startActivity(intent)\n"
        "            true\n"
        "        }",
    ),
]


def apply(path: Path, pairs) -> int:
    src = path.read_text(encoding="utf-8")
    n = 0
    for old, new in pairs:
        if old not in src:
            print("MISS:", path.name, repr(old.splitlines()[0][:60]))
            continue
        src = src.replace(old, new, 1)
        n += 1
    path.write_text(src, encoding="utf-8")
    print(f"{path.name}: applied {n}, remaining {src.count('runCatching')}")
    return n


apply(D, DH)
apply(G, GW)
