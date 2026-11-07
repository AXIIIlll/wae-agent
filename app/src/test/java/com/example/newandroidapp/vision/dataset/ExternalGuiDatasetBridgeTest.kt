package com.example.newandroidapp.vision.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalGuiDatasetBridgeTest {

    private fun resolveExternalDatasetsDir(): File {
        val candidates = listOf(
            File("external_datasets"),
            File("../external_datasets"),
            File("e:/NewAndroidApp/external_datasets")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory } ?: File("external_datasets")
    }

    @Test
    fun `test MobileViews real JSON and XML view hierarchies and actions parse and run through AiVisionPipeline`() {
        val baseDir = resolveExternalDatasetsDir()
        val statesDir = File(baseDir, "MobileViews/OneExample/OneExample/states")
        val actionsCsv = File(baseDir, "MobileViews/OneExample/OneExample/actions.csv")
        val appMetaCsv = File(baseDir, "MobileViews/AppMetadata.csv")

        assertTrue("MobileViews states directory must exist", statesDir.exists())
        assertTrue("MobileViews actions.csv must exist", actionsCsv.exists())
        assertTrue("MobileViews AppMetadata.csv must exist", appMetaCsv.exists())

        val jsonStateFile = File(statesDir, "state_1.json")
        val xmlDumpFile = File(statesDir, "window_dump_1.xml")
        assertTrue(jsonStateFile.exists())
        assertTrue(xmlDumpFile.exists())

        val parsedJsonState = ExternalGuiDatasetBridge.parseMobileViewsStateJson(jsonStateFile.readText(Charsets.UTF_8))
        assertNotNull(parsedJsonState.rootNode)
        assertTrue("MobileViews state_1.json should contain views", parsedJsonState.totalViewCount > 10)

        val jsonReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(parsedJsonState)
        assertTrue("AiVisionPipeline should extract raw and final nodes from MobileViews JSON", jsonReport.totalRawNodes > 0)
        assertTrue("AiVisionPipeline should produce final converged nodes from MobileViews JSON", jsonReport.finalNodes.isNotEmpty())

        val parsedXmlState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(xmlDumpFile.readText(Charsets.UTF_8))
        assertNotNull(parsedXmlState.rootNode)
        assertTrue("MobileViews window_dump_1.xml should contain nodes", parsedXmlState.totalViewCount > 10)

        val xmlReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(parsedXmlState)
        assertTrue("AiVisionPipeline should extract raw and final nodes from MobileViews XML", xmlReport.totalRawNodes > 0)
        assertTrue("AiVisionPipeline should produce final converged nodes from MobileViews XML", xmlReport.finalNodes.isNotEmpty())

        val transitions = ExternalGuiDatasetBridge.parseMobileViewsActionsCsv(actionsCsv)
        assertTrue("MobileViews actions.csv should contain state transitions", transitions.size > 50)
        assertTrue("At least one transition should contain parsed target bounding box", transitions.any { it.targetBounds != null })
    }

    @Test
    fun `test Android in the Wild real episodes parse and run through AiVisionPipeline and action matching`() {
        val baseDir = resolveExternalDatasetsDir()
        val aitwJsonl = File(baseDir, "android_in_the_wild/data/parsed_episodes.jsonl")
        assertTrue("AITW parsed_episodes.jsonl must exist", aitwJsonl.exists())

        val steps = ExternalGuiDatasetBridge.loadAitwEpisodesFromJsonl(aitwJsonl, maxSteps = 100)
        assertTrue("Should load real AITW steps across domains", steps.size >= 50)

        val firstStep = steps.first()
        assertTrue(firstStep.goalInfo.isNotBlank())
        assertTrue(firstStep.annotations.isNotEmpty())

        val rawNodes = firstStep.toRawVisionNodes()
        assertEquals(firstStep.annotations.size, rawNodes.size)

        val report = com.example.newandroidapp.vision.AiVisionPipeline.execute(
            rawNodes = rawNodes,
            screenWidth = firstStep.imageWidth,
            screenHeight = firstStep.imageHeight
        )
        assertTrue("AiVisionPipeline should process AITW UI annotations into finalNodes", report.finalNodes.isNotEmpty())

        // Verify AITW official action matching algorithm (action_matching.py)
        val ann = firstStep.annotations.first()
        val centerY = ann.yTop + ann.height * 0.5f
        val centerX = ann.xLeft + ann.width * 0.5f
        val slightlyOffsetY = ann.yTop + ann.height * 0.8f
        val slightlyOffsetX = ann.xLeft + ann.width * 0.8f

        val matchInsideSameBox = ExternalGuiDatasetBridge.checkAitwActionsMatch(
            action1TouchY = centerY,
            action1TouchX = centerX,
            action1LiftY = centerY,
            action1LiftX = centerX,
            action1Type = ExternalGuiDatasetBridge.AitwActionType.DUAL_POINT,
            action2TouchY = slightlyOffsetY,
            action2TouchX = slightlyOffsetX,
            action2LiftY = slightlyOffsetY,
            action2LiftX = slightlyOffsetX,
            action2Type = ExternalGuiDatasetBridge.AitwActionType.DUAL_POINT,
            annotations = firstStep.annotations
        )
        assertTrue("Two taps inside the same augmented UI annotation box must match", matchInsideSameBox)

        val farMismatch = ExternalGuiDatasetBridge.checkAitwActionsMatch(
            action1TouchY = 0.05f,
            action1TouchX = 0.05f,
            action1LiftY = 0.05f,
            action1LiftX = 0.05f,
            action1Type = ExternalGuiDatasetBridge.AitwActionType.DUAL_POINT,
            action2TouchY = 0.95f,
            action2TouchX = 0.95f,
            action2LiftY = 0.95f,
            action2LiftX = 0.95f,
            action2Type = ExternalGuiDatasetBridge.AitwActionType.DUAL_POINT,
            annotations = emptyList()
        )
        assertFalse("Two taps at opposite corners of the screen must not match", farMismatch)
    }

    @Test
    fun `test AMEX 102K screen and button functionality dataset and MobileViews 400 multi-app traces`() {
        val baseDir = resolveExternalDatasetsDir()
        val amexElemDir = File(baseDir, "AMEX/element_anno")
        val mvExtractedDir = File(baseDir, "MobileViews/MultiAppTraces/extracted")

        assertTrue("AMEX element_anno directory must exist", amexElemDir.exists())
        assertTrue("MobileViews MultiAppTraces extracted directory must exist", mvExtractedDir.exists())

        val sampleAmexFiles = amexElemDir.listFiles { f -> f.name.endsWith(".json") }?.take(15).orEmpty()
        assertTrue("AMEX should have extracted element_anno JSON files", sampleAmexFiles.size >= 10)

        var foundPageCaptionCount = 0
        var totalButtonsWithFunctionality = 0
        for (f in sampleAmexFiles) {
            val rec = ExternalGuiDatasetBridge.parseAmexElementAnnoJson(f.readText(Charsets.UTF_8))
            if (rec.pageCaption.isNotBlank()) foundPageCaptionCount++
            totalButtonsWithFunctionality += rec.clickableElements.count { it.functionality.isNotBlank() || it.xmlDescriptions.isNotEmpty() }

            val rawNodes = rec.toRawVisionNodes()
            val rep = com.example.newandroidapp.vision.AiVisionPipeline.execute(
                rawNodes = rawNodes,
                screenWidth = 1440,
                screenHeight = 2960
            )
            assertTrue(rep.totalRawNodes >= 0)
        }
        assertTrue("AMEX sample files should contain page captions", foundPageCaptionCount > 0)
        assertTrue("AMEX sample files should contain button functionality descriptions", totalButtonsWithFunctionality > 10)

        val appFolders = mvExtractedDir.listFiles { f -> f.isDirectory }?.take(10).orEmpty()
        assertTrue("MobileViews MultiAppTraces should contain multiple distinct app folders", appFolders.size >= 5)

        var parsedMultiAppStates = 0
        for (appFolder in appFolders) {
            val statesSub = File(appFolder, "states")
            val firstXml = statesSub.listFiles { f -> f.name.endsWith(".xml") }?.firstOrNull()
            if (firstXml != null) {
                val st = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(firstXml.readText(Charsets.UTF_8))
                val rep = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(st)
                if (rep.totalRawNodes > 0) parsedMultiAppStates++
            }
        }
        assertTrue("Should parse and run AiVisionPipeline across multiple distinct Android apps", parsedMultiAppStates >= 3)
    }

    private fun resolveProjectRootDir(): File {
        val candidates = listOf(
            File("."),
            File(".."),
            File("e:/NewAndroidApp")
        )
        return candidates.firstOrNull { File(it, "app/src/main").exists() } ?: File(".")
    }

    @Test
    fun `test Phase 1 cross-framework tree immunity across MobileViews apps and Compose hierarchies`() {
        val baseDir = resolveExternalDatasetsDir()
        val projectRoot = resolveProjectRootDir()
        val oneExampleDir = File(baseDir, "MobileViews/OneExample/OneExample")
        val mvExtractedDir = File(baseDir, "MobileViews/MultiAppTraces/extracted")
        val composeSamplesDir = File(baseDir, "MobileViews/ComposeSamples")

        assertTrue("MobileViews OneExample directory must exist", oneExampleDir.exists())
        assertTrue("MobileViews MultiAppTraces directory must exist", mvExtractedDir.exists())

        // 1. Verify Jetpack Compose complex hierarchies (Home + Search Hybrid Viewport)
        val composeHomeXml = File(composeSamplesDir, "compose_home_full.xml")
        val composeSearchXml = File(composeSamplesDir, "compose_search_hybrid.xml")
        assertTrue("Compose Home XML sample must exist", composeHomeXml.exists())
        assertTrue("Compose Search XML sample must exist", composeSearchXml.exists())

        val homeState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(composeHomeXml.readText(Charsets.UTF_8))
        val homeReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(homeState)
        val homeLabels = homeReport.finalNodes.map { it.label }

        // Verify all 5 bottom navigation tabs survive 100% without sibling/shim culling
        for (tabKeyword in listOf("首頁", "搜尋", "你的音樂庫", "Premium", "建立")) {
            assertTrue(
                "Compose Home must retain bottom navigation tab '$tabKeyword', actual labels: $homeLabels",
                homeReport.finalNodes.any { it.shortTypeTag != "TXT" && it.label.contains(tabKeyword) }
            )
        }
        // Verify top Compose filter chips with synthetic Button role shims survive 100%
        for (chipKeyword in listOf("所有", "音樂", "Podcast")) {
            assertTrue(
                "Compose Home must retain top filter chip '$chipKeyword'",
                homeReport.finalNodes.any { it.shortTypeTag != "TXT" && it.label.contains(chipKeyword) }
            )
        }
        // Verify Compose quick-play cards with full-card synthetic Button shims and deep TextViews survive 100%
        for (cardKeyword in listOf("UDISTRUCT", "已按讚的歌曲", "TIKI TIKI", "SO TIRED")) {
            assertTrue(
                "Compose Home must retain card '$cardKeyword'",
                homeReport.finalNodes.any { it.shortTypeTag != "TXT" && it.label.contains(cardKeyword) }
            )
        }

        val searchState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(composeSearchXml.readText(Charsets.UTF_8))
        val searchReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(searchState)
        for (tabKeyword in listOf("首頁", "搜尋", "你的音樂庫", "Premium", "建立")) {
            assertTrue(
                "Compose Search hybrid screen must retain bottom navigation tab '$tabKeyword'",
                searchReport.finalNodes.any { it.shortTypeTag != "TXT" && it.label.contains(tabKeyword) }
            )
        }
        assertEquals(
            "Nested identical full-screen black-box voids must be deduplicated to 1 ROI void",
            1,
            searchReport.detectedRoiVoids.size
        )

        // 2. Scan across MobileViews corpus (OneExample + MultiAppTraces 400+ apps)
        val allAppDirs = ArrayList<File>()
        if (oneExampleDir.exists()) allAppDirs.add(oneExampleDir)
        mvExtractedDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.let { allAppDirs.addAll(it) }

        var totalStatesCounted = 0
        var sampledStatesEvaluated = 0
        var totalRawNodesEvaluated = 0
        var totalFinalNodesProduced = 0
        var groundTruthActionsChecked = 0
        var groundTruthActionsRetained = 0

        for ((appIdx, appDir) in allAppDirs.withIndex()) {
            val statesSub = File(appDir, "states")
            val xmlFiles = statesSub.listFiles { f -> f.name.endsWith(".xml") }?.sortedBy { it.name }.orEmpty()
            totalStatesCounted += xmlFiles.size

            // Evaluate ground-truth actions in OneExample + sampled states across MultiAppTraces
            if (appIdx == 0) {
                val actionsCsv = File(appDir, "actions.csv")
                val transitions = ExternalGuiDatasetBridge.parseMobileViewsActionsCsv(actionsCsv)
                    .filter { it.targetBounds != null && (it.actionVerb == "touch" || it.actionVerb == "scroll") }
                val jsonFiles = statesSub.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
                val stateByHash = HashMap<String, ExternalGuiDatasetBridge.MobileViewsStateMetadata>()
                for (jf in jsonFiles.take(120)) {
                    runCatching {
                        val st = ExternalGuiDatasetBridge.parseMobileViewsStateJson(jf.readText(Charsets.UTF_8))
                        if (st.stateStr.isNotBlank()) stateByHash[st.stateStr] = st
                    }
                }
                for (tr in transitions) {
                    val st = stateByHash[tr.fromState] ?: continue
                    val targetBox = tr.targetBounds ?: continue
                    val targetW = targetBox.right - targetBox.left
                    val targetH = targetBox.bottom - targetBox.top
                    if (targetW <= 4 || targetH <= 4) continue
                    // Skip actions inside top/bottom system bar forbidden zones
                    val tCy = (targetBox.top + targetBox.bottom) / 2
                    if (tCy < (st.height * 0.04f).toInt() || tCy > (st.height * 0.93f).toInt()) continue

                    val rep = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(st)
                    groundTruthActionsChecked++
                    val hit = rep.finalNodes.any { fn ->
                        val b = fn.rawOriginalBounds
                        val ix0 = maxOf(b.left, targetBox.left)
                        val iy0 = maxOf(b.top, targetBox.top)
                        val ix1 = minOf(b.right, targetBox.right)
                        val iy1 = minOf(b.bottom, targetBox.bottom)
                        val overlaps = ix1 > ix0 && iy1 > iy0
                        val tapInTarget = fn.finalTapPx.x in targetBox.left..targetBox.right &&
                            fn.finalTapPx.y in targetBox.top..targetBox.bottom
                        overlaps || tapInTarget
                    }
                    if (hit) groundTruthActionsRetained++
                }
            }

            // Sample up to 1 XML per app across the first 80 apps for fast unit test execution while counting full corpus
            if (appIdx < 80 && xmlFiles.isNotEmpty()) {
                val sampleXml = xmlFiles.first()
                runCatching {
                    val st = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(sampleXml.readText(Charsets.UTF_8))
                    val rep = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(st)
                    if (rep.totalRawNodes > 0) {
                        sampledStatesEvaluated++
                        totalRawNodesEvaluated += rep.totalRawNodes
                        totalFinalNodesProduced += rep.finalNodes.size
                    }
                }
            }
        }

        val recallPct = if (groundTruthActionsChecked > 0) {
            (groundTruthActionsRetained.toFloat() * 100f) / groundTruthActionsChecked.toFloat()
        } else {
            100f
        }
        val formattedRecall = ((recallPct * 10f).toInt() / 10f)

        assertTrue("Should scan at least 400 MobileViews app directories", allAppDirs.size >= 400)
        assertTrue("Should evaluate at least 60 distinct app XML hierarchies", sampledStatesEvaluated >= 60)
        assertTrue(
            "Ground-truth action target retention recall must be >= 95%, actual: $formattedRecall% ($groundTruthActionsRetained/$groundTruthActionsChecked)",
            recallPct >= 95.0f
        )

        // 3. Export trained Phase 1 offline knowledge asset to app/src/main/assets/ui_phase1_tree_immunity.json
        val assetsDir = File(projectRoot, "app/src/main/assets")
        assetsDir.mkdirs()
        val outAssetFile = File(assetsDir, Phase1TreeImmunityConfig.ASSET_FILE_NAME)
        val assetJson = """
{
  "schema_version": 1,
  "phase": "Phase 1: Cross-Framework UI Tree Immunity & Structural Prior",
  "trained_corpus": "MobileViews + Real Jetpack Compose Hierarchies (Offline On-Device Prior)",
  "trained_apps_count": ${allAppDirs.size},
  "trained_states_count": $totalStatesCounted,
  "sampled_states_evaluated": $sampledStatesEvaluated,
  "total_raw_nodes_evaluated": $totalRawNodesEvaluated,
  "total_final_nodes_produced": $totalFinalNodesProduced,
  "ground_truth_actions_checked": $groundTruthActionsChecked,
  "ground_truth_actions_retained": $groundTruthActionsRetained,
  "ground_truth_action_recall_percent": $formattedRecall,
  "max_interactive_unit_area_ratio": 0.55,
  "max_icon_leaf_area_ratio": 0.22,
  "void_dedupe_iou_threshold": 0.85,
  "min_main_viewport_interactive_nodes_for_fast_tree": 2,
  "filter_synthetic_role_shim_leaves": true,
  "synthetic_role_shim_classes": [
    "android.widget.Button",
    "android.widget.CheckBox",
    "android.widget.Switch",
    "android.widget.RadioButton",
    "android.widget.ToggleButton"
  ]
}
        """.trimIndent()
        outAssetFile.writeText(assetJson, Charsets.UTF_8)
        assertTrue("Offline asset ui_phase1_tree_immunity.json must be written", outAssetFile.exists())
        assertTrue("Phase1TreeImmunityConfig must load trained JSON file", Phase1TreeImmunityConfig.loadFromFile(outAssetFile))
        assertEquals(allAppDirs.size, Phase1TreeImmunityConfig.trainedAppsCount)
    }

    @Test
    fun `test Phase 2 universal button functionality classifier trained on AMEX 102K screens`() {
        val baseDir = resolveExternalDatasetsDir()
        val projectRoot = resolveProjectRootDir()
        val amexElemDir = File(baseDir, "AMEX/element_anno")
        val composeSamplesDir = File(baseDir, "MobileViews/ComposeSamples")

        assertTrue("AMEX element_anno directory must exist", amexElemDir.exists())

        val assetsDir = File(projectRoot, "app/src/main/assets")
        assetsDir.mkdirs()
        val phase2AssetFile = File(assetsDir, Phase2ButtonFunctionalityClassifier.ASSET_FILE_NAME)

        // 1. Train Phase 2 classifier across 3,000 stratified AMEX screens (covering 35,000+ real annotated buttons)
        val trainedOk = Phase2ButtonFunctionalityClassifier.trainFromAmexDirectory(
            amexElementAnnoDir = amexElemDir,
            maxFilesToScan = 3000,
            outputAssetFile = phase2AssetFile
        )
        assertTrue("Phase 2 training from AMEX must succeed", trainedOk)
        assertTrue("Trained Phase 2 asset file must exist", phase2AssetFile.exists())
        assertTrue("Trained screens count should be >= 2000", Phase2ButtonFunctionalityClassifier.trainedScreensCount >= 2000)
        assertTrue("Trained buttons count should be >= 20000", Phase2ButtonFunctionalityClassifier.trainedButtonsCount >= 20000)

        // 2. Verify offline asset reload
        assertTrue(
            "Phase2ButtonFunctionalityClassifier must reload from JSON asset file",
            Phase2ButtonFunctionalityClassifier.loadFromFile(phase2AssetFile)
        )

        // 3. Verify button functionality classification on real Jetpack Compose hierarchy
        val composeHomeXml = File(composeSamplesDir, "compose_home_full.xml")
        val homeState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(composeHomeXml.readText(Charsets.UTF_8))
        val homeReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(homeState)

        val homeTab = homeReport.finalNodes.firstOrNull { it.label.contains("首頁") }
        val searchTab = homeReport.finalNodes.firstOrNull { it.label.contains("搜尋") }
        val libraryTab = homeReport.finalNodes.firstOrNull { it.label.contains("你的音樂庫") }
        val createTab = homeReport.finalNodes.firstOrNull { it.label.contains("建立") }
        val musicChip = homeReport.finalNodes.firstOrNull { it.label == "音樂" }
        val songCard = homeReport.finalNodes.firstOrNull { it.label.contains("SO TIRED") }

        assertNotNull("Home tab must exist", homeTab)
        assertNotNull("Search tab must exist", searchTab)
        assertNotNull("Library tab must exist", libraryTab)
        assertNotNull("Create tab must exist", createTab)
        assertNotNull("Music filter chip must exist", musicChip)
        assertNotNull("Song card must exist", songCard)

        assertEquals(
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.NAV_HOME_TAB.code,
            homeTab!!.predictedFunctionRole
        )
        assertEquals(
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.SEARCH_ENTRY.code,
            searchTab!!.predictedFunctionRole
        )
        assertEquals(
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.NAV_LIBRARY_SAVED_TAB.code,
            libraryTab!!.predictedFunctionRole
        )
        assertEquals(
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.COMPOSE_CREATE_ADD.code,
            createTab!!.predictedFunctionRole
        )
        assertEquals(
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.FILTER_SORT_CHIP.code,
            musicChip!!.predictedFunctionRole
        )
        assertEquals(
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.CONTENT_CARD_ITEM.code,
            songCard!!.predictedFunctionRole
        )

        // 4. Verify unlabeled visual/spatial buttons on real AMEX screens (e.g., unlabeled wide search bar & top-left back button)
        val amexHomeFile = File(amexElemDir, "2024_3_18_17_19_e8ba0101cbc74242b48af70a57dafdf5-1.json")
        if (amexHomeFile.exists()) {
            val rec = ExternalGuiDatasetBridge.parseAmexElementAnnoJson(amexHomeFile.readText(Charsets.UTF_8))
            val rep = com.example.newandroidapp.vision.AiVisionPipeline.execute(
                rawNodes = rec.toRawVisionNodes(),
                screenWidth = 1440,
                screenHeight = 2960
            )
            val searchBoxNode = rep.finalNodes.firstOrNull {
                it.predictedFunctionRole == Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.SEARCH_INPUT_BOX.code
            }
            assertNotNull("Unlabeled wide search bar [197,283][1373,413] on AMEX home screen must be classified as SEARCH_INPUT_BOX", searchBoxNode)
            assertTrue(
                "Search bar should have Search/搜尋 label, actual: ${searchBoxNode!!.label}",
                searchBoxNode.label.contains("搜尋") || searchBoxNode.label.contains("Search", ignoreCase = true)
            )
        }

        val wazeFile = File(amexElemDir, "Waze_2024_3_5_14_45-43.json")
        if (wazeFile.exists()) {
            val rec = ExternalGuiDatasetBridge.parseAmexElementAnnoJson(wazeFile.readText(Charsets.UTF_8))
            val rep = com.example.newandroidapp.vision.AiVisionPipeline.execute(
                rawNodes = rec.toRawVisionNodes(screenWidth = 720, screenHeight = 1280),
                screenWidth = 720,
                screenHeight = 1280
            )
            val backNode = rep.finalNodes.firstOrNull {
                it.predictedFunctionRole == Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.NAV_BACK.code
            }
            assertNotNull("Unlabeled top-left icon [32,160][128,256] on Waze screen must be classified as NAV_BACK", backNode)
            assertTrue(
                "Unlabeled top-left icon should receive synthesized Chinese functional label [返回上一頁], actual: ${backNode!!.label}",
                backNode.label.contains("返回")
            )
        }
    }

    @Test
    fun `test Phase 3 whole-screen posture and Phase 4 offline knowledge base distillation`() {
        val baseDir = resolveExternalDatasetsDir()
        val projectRoot = resolveProjectRootDir()
        val amexElemDir = File(baseDir, "AMEX/element_anno")
        val mvRootDir = File(baseDir, "MobileViews")
        assertTrue("AMEX element_anno dir must exist", amexElemDir.exists())
        assertTrue("MobileViews root dir must exist", mvRootDir.exists())

        val phase1Asset = File(projectRoot, "app/src/main/assets/ui_phase1_tree_immunity.json")
        val phase2Asset = File(projectRoot, "app/src/main/assets/ui_phase2_button_functionality.json")
        val phase3Asset = File(projectRoot, "app/src/main/assets/ui_phase3_screen_posture.json")

        // 1. Train Phase 3 Whole-Screen Posture & State Transition Knowledge Base on PC (0 phone usage)
        val trainedOk = Phase3ScreenPostureClassifier.trainFromDatasets(
            amexElementAnnoDir = amexElemDir,
            mobileViewsDir = mvRootDir,
            maxAmexFiles = 3000,
            maxMobileViewsApps = 120,
            outputAssetFile = phase3Asset
        )
        assertTrue("Phase 3 training from AMEX + MobileViews must succeed", trainedOk)
        assertTrue("Exported ui_phase3_screen_posture.json must exist", phase3Asset.exists())
        assertTrue("Exported Phase 3 asset must be non-empty", phase3Asset.length() > 1500L)

        // 2. Verify round-trip JSON loading
        val loadedOk = Phase3ScreenPostureClassifier.loadFromJsonString(phase3Asset.readText(Charsets.UTF_8))
        assertTrue("Loading ui_phase3_screen_posture.json must succeed", loadedOk)
        assertTrue(
            "Phase 3 must train on >= 2500 AMEX screens, actual=${Phase3ScreenPostureClassifier.getTrainedAmexScreensCount()}",
            Phase3ScreenPostureClassifier.getTrainedAmexScreensCount() >= 2500
        )
        assertTrue(
            "Phase 3 must scan >= 50 MobileViews state transitions, actual=${Phase3ScreenPostureClassifier.getTrainedMobileViewsTransitionsCount()}",
            Phase3ScreenPostureClassifier.getTrainedMobileViewsTransitionsCount() >= 50
        )

        // 3. Verify Whole-Screen Posture classification on Compose Home & Compose Search Portal
        val composeDir = File(mvRootDir, "ComposeSamples")
        val homeState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(
            File(composeDir, "compose_home_full.xml").readText(Charsets.UTF_8)
        )
        val homeReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(homeState)
        assertEquals(
            "Compose Home screen must be classified as HOME_FEED_HUB",
            Phase3ScreenPostureClassifier.ScreenPostureArchetype.HOME_FEED_HUB.code,
            homeReport.screenPosture
        )
        assertTrue(
            "Compressed prompt must embed HOME_FEED_HUB posture header",
            homeReport.compressedPromptText.contains("posture=HOME_FEED_HUB")
        )

        val searchState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(
            File(composeDir, "compose_search_hybrid.xml").readText(Charsets.UTF_8)
        )
        val searchReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(searchState)
        assertEquals(
            "Compose Search screen must be classified as SEARCH_ENTRY_PORTAL",
            Phase3ScreenPostureClassifier.ScreenPostureArchetype.SEARCH_ENTRY_PORTAL.code,
            searchReport.screenPosture
        )

        // 4. Verify Whole-Screen Posture on Waze Map screen
        val wazeFile = File(amexElemDir, "Waze_2024_3_5_14_45-43.json")
        if (wazeFile.exists()) {
            val rec = ExternalGuiDatasetBridge.parseAmexElementAnnoJson(wazeFile.readText(Charsets.UTF_8))
            val wazeRep = com.example.newandroidapp.vision.AiVisionPipeline.execute(
                rawNodes = rec.toRawVisionNodes(screenWidth = 720, screenHeight = 1280),
                screenWidth = 720,
                screenHeight = 1280
            )
            assertEquals(
                "Waze screen must be classified as MAP_NAVIGATION_EXPLORE",
                Phase3ScreenPostureClassifier.ScreenPostureArchetype.MAP_NAVIGATION_EXPLORE.code,
                wazeRep.screenPosture
            )
        }

        // 5. Verify tactical routing bonus from Whole-Screen Posture
        val searchEntryNode = homeReport.finalNodes.first { it.label.contains("搜尋") }
        val (homeSearchBonus, _) = Phase3ScreenPostureClassifier.computePostureTacticalBonus(
            screenPostureCode = homeReport.screenPosture,
            candidateNode = searchEntryNode,
            hasExplicitSearchIntent = true,
            hasExplicitCloseIntent = false,
            hasExplicitBuyIntent = false,
            hasExplicitPlayIntent = false
        )
        assertTrue("On HOME_FEED_HUB with search intent, SEARCH_ENTRY must receive positive tactical bonus", homeSearchBonus >= 30f)

        // 6. Phase 4 Distillation & Compression verification: all 3 assets exist and total < 1 MB
        assertTrue("Phase 1 asset must exist", phase1Asset.exists() && phase1Asset.length() > 500L)
        assertTrue("Phase 2 asset must exist", phase2Asset.exists() && phase2Asset.length() > 1500L)
        assertTrue("Phase 3 asset must exist", phase3Asset.exists() && phase3Asset.length() > 1500L)
        val totalKnowledgeBaseBytes = phase1Asset.length() + phase2Asset.length() + phase3Asset.length()
        assertTrue(
            "Phase 4 Distillation Gate: Total offline UI knowledge base assets must be < 1 MB (1,048,576 bytes), actual=$totalKnowledgeBaseBytes bytes",
            totalKnowledgeBaseBytes in 4000L..1_048_576L
        )
    }

    @Test
    fun `test live YouTube XML dump search entry detection and planning`() {
        val projectRoot = resolveProjectRootDir()
        val ytFile = File(projectRoot, "yt_live.xml")
        if (!ytFile.exists()) return

        val phase1Asset = File(projectRoot, "app/src/main/assets/ui_phase1_tree_immunity.json")
        val phase2Asset = File(projectRoot, "app/src/main/assets/ui_phase2_button_functionality.json")
        val phase3Asset = File(projectRoot, "app/src/main/assets/ui_phase3_screen_posture.json")
        if (phase1Asset.exists()) Phase1TreeImmunityConfig.loadFromFile(phase1Asset)
        if (phase2Asset.exists()) Phase2ButtonFunctionalityClassifier.loadFromFile(phase2Asset)
        if (phase3Asset.exists()) Phase3ScreenPostureClassifier.loadFromJsonString(phase3Asset.readText(Charsets.UTF_8))

        val xmlString = ytFile.readText(Charsets.UTF_8)
        assertFalse("yt_live.xml must be clean UTF-8 without replacement characters", xmlString.contains('\uFFFD'))
        val ytState = ExternalGuiDatasetBridge.parseMobileViewsWindowDumpXml(xmlString)
        val ytReport = ExternalGuiDatasetBridge.runVisionPipelineOnMobileViewsState(ytState)

        assertEquals("HOME_FEED_HUB", ytReport.screenPosture)
        val searchNode = ytReport.finalNodes.firstOrNull { it.label == "搜尋" }
        assertNotNull("YouTube top-right search button must be retained in finalNodes", searchNode)
        assertEquals("SEARCH_ENTRY", searchNode!!.predictedFunctionRole)

        val microDuckTitleNode = ytReport.finalNodes.firstOrNull { it.label.contains("MicroDuck") && !it.label.endsWith("動作選單") }
        assertNotNull(microDuckTitleNode)
        assertFalse(
            "Video title containing 'MicroDuck' must never be blocked as voice/mic input",
            com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenVoiceOrCameraNode(microDuckTitleNode!!)
        )

        val testGoals = listOf(
            "去youtube查周杰倫",
            "去youtube查AI",
            "去youtube查東西",
            "去youtube搜尋新聞",
            "去youtube查歌",
            "我想聽晴天",
            "去yt查周杰倫",
            "來一首稻香",
            "幫我播周杰倫的歌"
        )
        for (goal in testGoals) {
            com.example.newandroidapp.stability.InAppWorkingMemory.resetForNewGoal(goal)
            com.example.newandroidapp.ai.DualLocalAiCoordinator.resetPlanScriptCache()
            val plannedGoal = com.example.newandroidapp.ai.LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal(
                rawGoal = goal,
                context = null,
                currentReport = ytReport
            )
            val dec = com.example.newandroidapp.ai.DualLocalAiCoordinator.decide(
                mode = com.example.newandroidapp.ai.LocalAiBackendMode.DUAL_FUSION,
                userGoal = plannedGoal,
                report = ytReport,
                context = null,
                currentForegroundPackage = "com.google.android.youtube",
                alreadyLaunchedApp = true,
                activeSubGoalIndex = 0
            )
            assertEquals("Goal '$goal' must decide TAP on YouTube Home", com.example.newandroidapp.ai.AiActionType.TAP, dec.actionType)
            assertEquals("Goal '$goal' must target top-right '搜尋' button (#25)", "搜尋", dec.targetNode?.label)
        }

        // 驗證口語導航與上網查詢擴充（優化 #10）
        val navRes = com.example.newandroidapp.ai.CognitiveReasoningEngine.resolveColloquialAndContextualGoal("帶我去台北101")
        assertEquals("Google 地圖", navRes.inferredAppHint)
        assertTrue(navRes.normalizedGoal.contains("台北101"))

        val webRes = com.example.newandroidapp.ai.CognitiveReasoningEngine.resolveColloquialAndContextualGoal("幫我上網查量子電腦")
        assertEquals("Chrome", webRes.inferredAppHint)
        assertTrue(webRes.normalizedGoal.contains("量子電腦"))
    }
}
