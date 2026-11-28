"""RuleEngineHost（10）＋ AgentRunner（10）：runCatching → Degrade。"""
from pathlib import Path

R = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\gen5\RuleEngineHost.kt")
A = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\AgentRunner.kt")

RULES = [
    # decide：有 onFailure log
    (
        "        runCatching { engine(context).decide(command, facts, preset, allowOps = opsEnabled) }\n"
        '            .onFailure { Log.e(TAG, "decide failed", it) }\n'
        "            .getOrNull()",
        '        com.wae.core.util.Degrade.orNull("rules:decide") {\n'
        "            engine(context).decide(command, facts, preset, allowOps = opsEnabled)\n"
        "        }",
    ),
    # 類別路由
    (
        "        val categories = runCatching {\n"
        "            com.wae.core.intent.IntentRouter.route(command)?.categories\n"
        "        }.getOrNull() ?: return emptyMap()",
        '        val categories = com.wae.core.util.Degrade.orNull("rules:categories") {\n'
        "            com.wae.core.intent.IntentRouter.route(command)?.categories\n"
        "        } ?: return emptyMap()",
    ),
    # App 清單（含 category 過濾）
    (
        "        val apps = runCatching {\n"
        "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
        "                .filter { it.category in categories }\n"
        "        }.getOrDefault(emptyList())",
        '        val apps = com.wae.core.util.Degrade.orEmpty("rules:appsByCategory") {\n'
        "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
        "                .filter { it.category in categories }\n"
        "        }",
    ),
    # 路由表載入
    (
        "        runCatching { com.wae.core.intent.IntentRouter.loadFromLines(BundledData.intentRoutes()) }",
        '        com.wae.core.util.Degrade.orNull("rules:loadRoutes") {\n'
        "            com.wae.core.intent.IntentRouter.loadFromLines(BundledData.intentRoutes())\n"
        "        }",
    ),
    # service 取值
    (
        "        val service = runCatching {\n"
        "            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
        "        }.getOrNull()",
        '        val service = com.wae.core.util.Degrade.orNull("rules:activeService") {\n'
        "            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
        "        }",
    ),
    # 前景套件（第一處）
    (
        "        val pkg = runCatching {\n"
        "            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
        "                ?.rootInActiveWindow?.packageName?.toString()\n"
        "        }.getOrNull().orEmpty()",
        '        val pkg = com.wae.core.util.Degrade.orElse("rules:foregroundPkg", "") {\n'
        "            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
        "                ?.rootInActiveWindow?.packageName?.toString() ?: \"\"\n"
        "        }",
    ),
    # 已安裝 App（fact 統計用）
    (
        "        val apps = runCatching {\n"
        "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
        "        }.getOrDefault(emptyList())",
        '        val apps = com.wae.core.util.Degrade.orEmpty("rules:installedApps") {\n'
        "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
        "        }",
    ),
    # 前景套件（第二處）
    (
        "        val fgPkg = runCatching {\n"
        "            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
        "                ?.rootInActiveWindow?.packageName?.toString()\n"
        "        }.getOrNull().orEmpty()",
        '        val fgPkg = com.wae.core.util.Degrade.orElse("rules:fgPkg", "") {\n'
        "            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance\n"
        "                ?.rootInActiveWindow?.packageName?.toString() ?: \"\"\n"
        "        }",
    ),
    # 規則引擎自我檢查
    (
        "        val e = runCatching { engine(context) }.getOrNull() ?: return \"RULES error\"",
        '        val e = com.wae.core.util.Degrade.orNull("rules:engine") { engine(context) }\n'
        '            ?: return "RULES error"',
    ),
    # 已安裝 App 標籤
    (
        "        val labels = runCatching {\n"
        "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
        "                .map { it.label }\n"
        "        }.getOrDefault(emptyList())",
        '        val labels = com.wae.core.util.Degrade.orEmpty("rules:installedLabels") {\n'
        "            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)\n"
        "                .map { it.label }\n"
        "        }",
    ),
]

AGENT = [
    # 步驟回呼
    (
        "                runCatching { onStep(msg) }",
        '                com.wae.core.util.Degrade.orNull("agent:onStep") { onStep(msg) }',
    ),
    # 音樂播放狀態
    (
        "    private fun isAudioPlaying(): Boolean = runCatching {\n"
        "        val am = service.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager\n"
        "        am.isMusicActive\n"
        "    }.getOrDefault(false)",
        '    private fun isAudioPlaying(): Boolean =\n'
        '        com.wae.core.util.Degrade.orElse("agent:audioPlaying", false) {\n'
        "            val am = service.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager\n"
        "            am.isMusicActive\n"
        "        }",
    ),
    # 語意排序（含非局部 return）
    (
        "        return runCatching {\n"
        "            val ctx = service.applicationContext\n"
        "            if (!com.wae.agent.core.nlp.ZhEmbedder.ensureLoaded(ctx)) return emptyList()",
        '        return com.wae.core.util.Degrade.orEmpty("agent:semanticRank") {\n'
        "            val ctx = service.applicationContext\n"
        "            if (!com.wae.agent.core.nlp.ZhEmbedder.ensureLoaded(ctx)) return@orEmpty emptyList()",
    ),
    (
        "            val gv = com.wae.agent.core.nlp.ZhEmbedder.encode(listOf(simpGoal)).firstOrNull()\n"
        "                ?: return emptyList()",
        "            val gv = com.wae.agent.core.nlp.ZhEmbedder.encode(listOf(simpGoal)).firstOrNull()\n"
        "                ?: return@orEmpty emptyList()",
    ),
    # 相似度加分
    (
        "        return runCatching {\n"
        "            val cv = com.wae.agent.core.nlp.ZhEmbedder.encode(listOf(terms)).firstOrNull()\n"
        "                ?: return 0f",
        '        return com.wae.core.util.Degrade.orElse("agent:semanticBonus", 0f) {\n'
        "            val cv = com.wae.agent.core.nlp.ZhEmbedder.encode(listOf(terms)).firstOrNull()\n"
        "                ?: return@orElse 0f",
    ),
    (
        "            if (sim <= 0.25f) 0f else sim * 0.06f\n"
        "        }.getOrDefault(0f)",
        "            if (sim <= 0.25f) 0f else sim * 0.06f\n"
        "        }",
    ),
    # 已安裝 App 資訊
    (
        "        return runCatching {\n"
        "            pm.getInstalledApplications(0).mapNotNull { app ->\n"
        "                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()",
        '        return com.wae.core.util.Degrade.orEmpty("agent:installedApps") {\n'
        "            pm.getInstalledApplications(0).mapNotNull { app ->\n"
        '                val label = com.wae.core.util.Degrade.orNull("agent:appLabel") {\n'
        "                    pm.getApplicationLabel(app).toString()\n"
        "                }",
    ),
    (
        "                if (label.isNullOrBlank()) null else Triple(label, app.packageName, app.category)\n"
        "            }\n"
        "        }.getOrDefault(emptyList())",
        "                if (label.isNullOrBlank()) null else Triple(label, app.packageName, app.category)\n"
        "            }\n"
        "        }",
    ),
    # 繁簡對照表
    (
        "            tradToSimpMap = runCatching {",
        '            tradToSimpMap = com.wae.core.util.Degrade.orNull("agent:tradSimpMap") {',
    ),
    # 已安裝 App 清單（比對名稱用）
    (
        "        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())",
        '        val apps = com.wae.core.util.Degrade.orEmpty("agent:apps") { pm.getInstalledApplications(0) }',
    ),
    (
        "            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue",
        '            val label = com.wae.core.util.Degrade.orNull("agent:label") {\n'
        "                pm.getApplicationLabel(app).toString()\n"
        "            } ?: continue",
    ),
    # 瀏覽器解析
    (
        "    private fun resolveBrowserPackage(): String? = runCatching {",
        '    private fun resolveBrowserPackage(): String? = com.wae.core.util.Degrade.orNull("agent:browserPkg") {',
    ),
    (
        "        resolved?.activityInfo?.packageName\n"
        "    }.getOrNull()",
        "        resolved?.activityInfo?.packageName\n"
        "    }",
    ),
]


def apply(path: Path, pairs) -> None:
    src = path.read_text(encoding="utf-8")
    n = 0
    for old, new in pairs:
        if old not in src:
            print("MISS:", path.name, repr(old.splitlines()[0][:64]))
            continue
        src = src.replace(old, new, 1)
        n += 1
    path.write_text(src, encoding="utf-8")
    print(f"{path.name}: applied {n}, remaining {src.count('runCatching')}")


apply(R, RULES)
apply(A, AGENT)
