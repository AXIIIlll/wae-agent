package com.wae.agent.core.posture

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import com.wae.agent.core.ComponentLibraryType
import com.wae.agent.core.PagePosture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** +1 姿態模型測試：特徵抽取、softmax 推論、置信度、權重載入與降級。 */
class PostureModelTest {

    @org.junit.Before
    fun resetModel() {
        PostureModel.resetToPrior()
    }


    private fun cand(
        gridZone: String,
        editable: Boolean = false,
        scrollable: Boolean = false
    ) = CandidateOption(
        markId = 1,
        nodeStableSig = 1L,
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, 0, 100, 100),
        coreText = "x",
        weldedDescription = "",
        hybridSimScore = 0.5f,
        isClickable = true,
        isEditable = editable,
        isScrollable = scrollable,
        gridZone = gridZone
    )

    private fun features(
        windowCount: Int = 1,
        ratio: Float = 1f,
        candidates: List<CandidateOption> = List(10) { cand("CENTER_LIST") },
        classNames: List<String> = listOf("RecyclerView")
    ) = PostureFeatures.from(PostureFeatures.Raw(windowCount, ratio, candidates, classNames))

    @Test
    fun `features have fixed dimension and finite values`() {
        val f = features(
            candidates = listOf(cand("CENTER_LIST", scrollable = true), cand("TOP_BAR")),
            classNames = listOf("android.widget.RecyclerView", "androidx.appcompat.widget.Toolbar")
        )
        assertEquals(PostureFeatures.DIM, f.size)
        assertTrue(f.all { it.isFinite() })
        assertEquals(0.25f, f[11], 1e-6f)
    }

    @Test
    fun `list page boosts list feature form page boosts form feature`() {
        val list = features(candidates = List(10) { cand("CENTER_LIST", scrollable = true) })
        val form = features(candidates = List(10) { cand("CENTER_LIST", editable = true) })
        assertTrue("列表頁 LIST_FEED 特徵應更高", list[0] > form[0])
        assertTrue("表單頁 FORM_INPUT 特徵應更高", form[1] > list[1])
    }

    @Test
    fun `overlay features only activate with multiple windows`() {
        val single = features(windowCount = 1)
        val multi = features(windowCount = 2, ratio = 0.6f)
        assertEquals(0f, single[2], 1e-6f)
        assertEquals(0f, single[3], 1e-6f)
        assertTrue("多視窗才啟用遮罩特徵", multi[2] > 0f && multi[3] > 0f)
    }

    @Test
    fun `component library inferred from class names`() {
        assertEquals(
            ComponentLibraryType.FLUTTER_OR_CUSTOM,
            PostureFeatures.componentLib(features(classNames = List(10) { "io.flutter.embedding.FlutterView" }))
        )
        assertEquals(
            ComponentLibraryType.ANDROID_NATIVE,
            PostureFeatures.componentLib(features(classNames = listOf("android.widget.FrameLayout")))
        )
    }

    @Test
    fun `classify returns normalized probabilities`() {
        val p = PostureModel.classify(features(candidates = List(10) { cand("CENTER_LIST", scrollable = true) }))
        assertEquals(1f, p.probs.sum(), 1e-4f)
        assertTrue(p.confidence in 0f..1f)
        assertEquals(PagePosture.LIST_FEED, p.primary)
    }

    @Test
    fun `form page classified as form input`() {
        val p = PostureModel.classify(features(candidates = List(10) { cand("CENTER_LIST", editable = true) }))
        assertEquals(PagePosture.FORM_INPUT, p.primary)
    }

    @Test
    fun `dialog page classified as modal dialog`() {
        val p = PostureModel.classify(features(windowCount = 2, ratio = 0.4f))
        assertEquals(PagePosture.MODAL_DIALOG, p.primary)
        assertFalse("overlay 不應為 UNKNOWN", p.overlay == PagePosture.EMPTY_OR_UNKNOWN)
    }

    @Test
    fun `invalid feature dimension degrades to unknown`() {
        val p = PostureModel.classify(FloatArray(3))
        assertEquals(PagePosture.EMPTY_OR_UNKNOWN, p.primary)
        assertEquals(0f, p.confidence, 1e-6f)
    }

    @Test
    fun `weights round trip through json`() {
        val exported = PostureModel.exportJson()
        assertTrue("匯出的權重應可再載入", PostureModel.load(exported, "test"))
        assertTrue(PostureModel.source().contains("test"))
    }

    @Test
    fun `invalid model json is rejected`() {
        assertFalse(PostureModel.load("{not json}", "bad"))
        assertFalse(PostureModel.load("{\"dim\":3,\"classes\":9,\"weights\":[]}", "bad2"))
    }

    @Test
    fun `trained weights change prediction`() {
        val classes = PostureFeatures.POSTURE_CLASSES.size
        val weights = org.json.JSONArray()
        for (i in 0 until PostureFeatures.DIM) {
            val row = org.json.JSONArray()
            for (j in 0 until classes) row.put(if (j == 3) 1.0 else 0.0)
            weights.put(row)
        }
        val bias = org.json.JSONArray()
        for (j in 0 until classes) bias.put(0.0)
        val json = org.json.JSONObject().apply {
            put("dim", PostureFeatures.DIM)
            put("classes", classes)
            put("weights", weights)
            put("bias", bias)
        }.toString()
        assertTrue(PostureModel.load(json, "trained"))
        assertEquals(PagePosture.BOTTOM_SHEET, PostureModel.classify(features()).primary)
    }
}