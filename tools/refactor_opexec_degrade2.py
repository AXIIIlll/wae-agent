"""OpExecutor 第二批：8 處 runCatching → Degrade（開頭＋尾巴成對）。"""
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


# clickByText 相關的兩處 click（內容相同，分開命名）
click_old = (
    "        return runCatching {\n"
    "            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK) ||\n"
    "                (node.parent?.let { DeviceIO.perform(it, AccessibilityNodeInfo.ACTION_CLICK) } ?: false)\n"
    "        }.getOrDefault(false)"
)
click_new = (
    '        return com.wae.core.util.Degrade.orElse("opExec:click", false) {\n'
    "            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK) ||\n"
    "                (node.parent?.let { DeviceIO.perform(it, AccessibilityNodeInfo.ACTION_CLICK) } ?: false)\n"
    "        }"
)
sub(click_old, click_new)

# 兩處 SET_TEXT 驗證（typeIntoSearch / setText）
set_old = (
    "        return runCatching {\n"
    "            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args) &&\n"
    "                (node.text?.toString()?.contains(text) == true)\n"
    "        }.getOrDefault(false)"
)
set_new = (
    '        return com.wae.core.util.Degrade.orElse("opExec:setText", false) {\n'
    "            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args) &&\n"
    "                (node.text?.toString()?.contains(text) == true)\n"
    "        }"
)
sub(set_old, set_new)
sub(set_old, set_new)

# 快速設定面板
sub(
    "    private fun openPanel(service: AccessibilityService?): Boolean =\n"
    "        runCatching {\n"
    "            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false\n"
    "        }.getOrDefault(false)",
    '    private fun openPanel(service: AccessibilityService?): Boolean =\n'
    '        com.wae.core.util.Degrade.orElse("opExec:openPanel", false) {\n'
    "            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false\n"
    "        }",
)

# 音量
sub(
    '            "volume" -> runCatching {',
    '            "volume" -> com.wae.core.util.Degrade.orElse("opExec:volume", false) {',
)
sub(
    "                true\n"
    "            }.getOrDefault(false)\n"
    '            "brightness" -> runCatching {',
    "                true\n"
    "            }\n"
    '            "brightness" -> com.wae.core.util.Degrade.orElse("opExec:brightness", false) {',
)
sub(
    "                true\n"
    "            }.getOrDefault(false)\n"
    "            else -> false",
    "                true\n"
    "            }\n"
    "            else -> false",
)

# App 名稱
sub(
    '            out["pkg_label"] = runCatching {',
    '            out["pkg_label"] = com.wae.core.util.Degrade.orElse("opExec:pkgLabel", pkg) {',
)
sub(
    "                ).toString()\n"
    "            }.getOrDefault(pkg)",
    "                ).toString()\n"
    "            }",
)

# 已安裝清單
sub(
    "        val installed = runCatching {\n"
    "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
    "        }.getOrDefault(emptyList())",
    '        val installed = com.wae.core.util.Degrade.orEmpty("opExec:installedApps") {\n'
    "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
    "        }",
)

P.write_text(src, encoding="utf-8")
print("applied:", n)
print("remaining:", src.count("runCatching"))
