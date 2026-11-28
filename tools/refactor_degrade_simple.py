"""DecisionHead ＋ A11yScreenGateway：先轉「單行、成對」的 runCatching，再處理多行區塊。"""
from pathlib import Path

D = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\decision\DecisionHead.kt")
G = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\predicate\A11yScreenGateway.kt")

DH = [
    (
        'runCatching { f["flag:isNavigationRow"] = if (ContentItemEvidence.isNavigationRow(c)) 1f else 0f }',
        'com.wae.core.util.Degrade.orNull("decisionHead:flagNav") { f["flag:isNavigationRow"] = if (ContentItemEvidence.isNavigationRow(c)) 1f else 0f }',
    ),
    (
        'runCatching { f["flag:isContentItem"] = if (ContentItemEvidence.isContentItem(c)) 1f else 0f }',
        'com.wae.core.util.Degrade.orNull("decisionHead:flagContent") { f["flag:isContentItem"] = if (ContentItemEvidence.isContentItem(c)) 1f else 0f }',
    ),
    (
        "val f = runCatching { candidateFeatures(goal, c) }.getOrNull() ?: return 0f",
        'val f = com.wae.core.util.Degrade.orNull("decisionHead:candFeatures") { candidateFeatures(goal, c) } ?: return 0f',
    ),
    (
        "val bonus = runCatching { score(candidateModel, f) }.getOrDefault(0f)",
        'val bonus = com.wae.core.util.Degrade.orElse("decisionHead:candScore", 0f) { score(candidateModel, f) }',
    ),
    (
        "val bonus = runCatching { score(edgeModel, f) }.getOrDefault(0f)",
        'val bonus = com.wae.core.util.Degrade.orElse("decisionHead:edgeScore", 0f) { score(edgeModel, f) }',
    ),
    (
        "return runCatching { java.io.File(ctx.filesDir, SAMPLES_FILE).delete() }.getOrDefault(false)",
        'return com.wae.core.util.Degrade.orElse("decisionHead:deleteSamples", false) { java.io.File(ctx.filesDir, SAMPLES_FILE).delete() }',
    ),
]

GW = [
    (
        "runCatching { dumpWindows() }",
        'com.wae.core.util.Degrade.orNull("gateway:dumpWindows") { dumpWindows() }',
    ),
    (
        "val ws = runCatching { service.windows }.getOrNull() ?: return",
        'val ws = com.wae.core.util.Degrade.orNull("gateway:windows") { service.windows } ?: return',
    ),
    (
        "runCatching { w.root?.recycle() }",
        'com.wae.core.util.Degrade.orNull("gateway:recycleWindow") { w.root?.recycle() }',
    ),
    (
        "runCatching { DeviceIO.perform(editNode, AccessibilityNodeInfo.ACTION_FOCUS) }",
        'com.wae.core.util.Degrade.orNull("gateway:focusEdit") { DeviceIO.perform(editNode, AccessibilityNodeInfo.ACTION_FOCUS) }',
    ),
    (
        "val parent = runCatching { cur!!.parent }.getOrNull() ?: break",
        'val parent = com.wae.core.util.Degrade.orNull("gateway:parent") { cur!!.parent } ?: break',
    ),
    (
        "container = runCatching { container!!.parent }.getOrNull()",
        'container = com.wae.core.util.Degrade.orNull("gateway:containerUp") { container!!.parent }',
    ),
]


def apply(path: Path, pairs) -> int:
    src = path.read_text(encoding="utf-8")
    n = 0
    for old, new in pairs:
        if old not in src:
            print("MISS:", path.name, old[:60])
            continue
        src = src.replace(old, new, 1)
        n += 1
    path.write_text(src, encoding="utf-8")
    print(f"{path.name}: applied {n}, remaining {src.count('runCatching')}")
    return n


apply(D, DH)
apply(G, GW)
