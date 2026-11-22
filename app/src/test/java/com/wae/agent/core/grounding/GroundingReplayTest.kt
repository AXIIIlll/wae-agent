package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G0：GroundingReplay 的 fixture 解析與 Top-1/Top-3/abstain 定義測試。
 *
 * 直接用交付的 `seed_001.json`（文字已 token 化成 T1/T2）當輸入，
 * 驗證「同一份 fixture → 同一組數字」，確保可重播基準本身是確定性的。
 */
class GroundingReplayTest {

    private fun fixtureFile(name: String = "seed_001.json"): File {
        val candidates = listOf(
            File("src/test/resources/grounding_fixtures/$name"),
            File("app/src/test/resources/grounding_fixtures/$name"),
            File("E:/NewAndroidApp/app/src/test/resources/grounding_fixtures/$name")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 fixture $name（工作目錄=${File(".").absolutePath}）")
    }

    private fun loadSeed(): ReplayFixture {
        return GroundingReplay.parseFixture(fixtureFile().readText(Charsets.UTF_8))
    }

    private fun candidate(
        index: Int,
        score: Float,
        text: String = "T$index",
        editable: Boolean = false
    ) = TraceCandidate(
        slot = index,
        text = text,
        className = "android.widget.TextView",
        viewId = "V$index",
        bounds = intArrayOf(0, index * 100, 100, index * 100 + 50),
        clickable = true,
        editable = editable,
        checkable = false,
        checked = false,
        enabled = true,
        score = score
    )

    private fun step(
        stepId: String = "s0",
        candidates: List<TraceCandidate> = listOf(candidate(0, 0.9f)),
        chosenIndex: Int = 0,
        labelIndex: Int = 0
    ) = ReplayStep(
        stepId = stepId,
        pkg = "P1",
        posture = "FORM",
        stateId = "S1",
        goal = "T1",
        candidates = candidates,
        chosenIndex = chosenIndex,
        labelIndex = labelIndex,
        labelReason = "screen_changed",
        changed = true,
        postconditionVerified = false,
        elapsedMs = 10L
    )

    @Test
    fun `seed fixture parses into three labeled steps`() {
        val fixture = loadSeed()
        assertEquals("seed_001", fixture.name)
        assertEquals("manual", fixture.source)
        assertEquals(3, fixture.steps.size)
        assertTrue(fixture.steps.all { it.labelIndex in it.candidates.indices })

        val first = fixture.steps[0]
        assertEquals("seed_001_s0", first.stepId)
        assertEquals("P1", first.pkg)
        assertEquals("FORM", first.posture)
        assertEquals("S1", first.stateId)
        assertEquals("T1", first.goal)
        assertEquals(3, first.candidates.size)
        assertTrue(first.changed)
        assertTrue(first.postconditionVerified)
        assertEquals(7L, first.elapsedMs)

        val searchBox = first.candidates[0]
        assertEquals("T1", searchBox.text)
        assertEquals("android.widget.EditText", searchBox.className)
        assertEquals("V1", searchBox.viewId)
        assertTrue(searchBox.bounds.contentEquals(intArrayOf(72, 300, 1008, 396)))
        assertFalse(searchBox.clickable)
        assertTrue(searchBox.editable)
        assertEquals(0.91f, searchBox.score, 1e-4f)
    }

    @Test
    fun `seed fixture contains only tokenized text and ids`() {
        val fixture = loadSeed()
        val textPattern = Regex("""^T\d+$""")
        val idPattern = Regex("""^V\d+$""")
        val pkgPattern = Regex("""^P\d+$""")
        for (step in fixture.steps) {
            assertTrue("goal 必須 token 化：${step.goal}", textPattern.matches(step.goal))
            assertTrue("pkg 必須 token 化：${step.pkg}", pkgPattern.matches(step.pkg))
            for (candidate in step.candidates) {
                assertTrue("text 必須 token 化：${candidate.text}", textPattern.matches(candidate.text))
                assertTrue("viewId 必須 token 化：${candidate.viewId}", idPattern.matches(candidate.viewId))
            }
        }
        // 檔案本身不得有 BOM
        val bytes = fixtureFile().readBytes()
        assertFalse(
            "fixture 檔頭不得有 UTF-8 BOM",
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        )
    }

    @Test
    fun `ranking is score descending and stable on ties`() {
        val fixture = loadSeed()
        assertEquals(listOf(0, 1, 2), GroundingReplay.ranking(fixture.steps[0]))
        assertEquals(listOf(0, 2, 1), GroundingReplay.ranking(fixture.steps[1]))

        val tied = step(
            candidates = listOf(candidate(0, 0.5f), candidate(1, 0.5f), candidate(2, 0.9f))
        )
        assertEquals("同分保留原順序", listOf(2, 0, 1), GroundingReplay.ranking(tied))
        assertEquals(listOf(2, 0), GroundingReplay.topK(tied, 2))
        assertTrue(GroundingReplay.topK(tied, 0).isEmpty())
    }

    @Test
    fun `top1 and top3 assertions match seed expectations`() {
        val steps = loadSeed().steps
        assertTrue(GroundingReplay.isTop1OK(steps[0]))
        assertTrue(GroundingReplay.isTop3OK(steps[0]))
        // 正確答案（slot 2）分數 0.550 輸給 slot 0 的 0.620 → Top-1 失敗但 Top-3 命中
        assertFalse(GroundingReplay.isTop1OK(steps[1]))
        assertTrue(GroundingReplay.isTop3OK(steps[1]))
        // abstain 步：正確答案（slot 1）分數 0.100 最低 → Top-1 失敗但 Top-3 命中
        assertFalse(GroundingReplay.isTop1OK(steps[2]))
        assertTrue(GroundingReplay.isTop3OK(steps[2]))

        assertEquals(steps[0], GroundingReplay.requireTop1(steps[0]))
        assertEquals(steps[1], GroundingReplay.requireTop3(steps[1]))
        assertThrows(AssertionError::class.java) { GroundingReplay.requireTop1(steps[1]) }
        assertThrows(AssertionError::class.java) { GroundingReplay.requireTop3(step(candidates = emptyList())) }
    }

    @Test
    fun `unlabeled steps never count as top1 top3 or wrong choice`() {
        val unlabeled = step(labelIndex = -1)
        assertFalse(GroundingReplay.isTop1OK(unlabeled))
        assertFalse(GroundingReplay.isTop3OK(unlabeled))
        assertFalse(GroundingReplay.isWrongChoice(unlabeled))
    }

    @Test
    fun `wrong choice is measured against label and ignores abstain`() {
        assertFalse(GroundingReplay.isWrongChoice(loadSeed().steps[0]))
        assertFalse("chosenIndex=-1 是 abstain，不是錯點", GroundingReplay.isWrongChoice(loadSeed().steps[2]))
        val wrong = step(
            candidates = listOf(candidate(0, 0.9f), candidate(1, 0.1f)),
            chosenIndex = 0,
            labelIndex = 1
        )
        assertTrue(GroundingReplay.isWrongChoice(wrong))
    }

    @Test
    fun `abstain threshold flags low confidence steps`() {
        val fixture = loadSeed()
        assertFalse(GroundingReplay.shouldAbstain(fixture.steps[0], 0.7f))
        assertTrue(GroundingReplay.shouldAbstain(fixture.steps[1], 0.7f))
        assertTrue(GroundingReplay.shouldAbstain(fixture.steps[2], 0.7f))
        assertFalse("門檻 0 等於永不 abstain", GroundingReplay.shouldAbstain(fixture.steps[2], 0f))
    }

    @Test
    fun `evaluate counts hits abstains and latency`() {
        val metrics = GroundingReplay.evaluate(loadSeed())
        assertEquals(3, metrics.steps)
        assertEquals(3, metrics.labeledSteps)
        assertEquals(1, metrics.top1Hits)
        assertEquals(3, metrics.top3Hits)
        assertEquals(1, metrics.abstainSteps)
        assertEquals(1, metrics.abstainEvaluable)
        assertEquals(1, metrics.abstainCorrect)
        assertEquals(0, metrics.wrongClicks)
        assertEquals(1.0 / 3.0, metrics.top1Rate, 1e-6)
        assertEquals(1.0, metrics.top3Rate, 1e-6)
        assertEquals(0.0, metrics.wrongClickRate, 1e-6)
        assertEquals(1.0, metrics.abstainPrecision, 1e-6)
        assertEquals(28.0 / 3.0, metrics.avgElapsedMs, 1e-6)
        assertEquals(12L, metrics.maxElapsedMs)
    }

    @Test
    fun `evaluate with threshold counts simulated abstains`() {
        val metrics = GroundingReplay.evaluate(loadSeed(), abstainThreshold = 0.7f)
        assertEquals(2, metrics.abstainSteps)
        assertEquals(2, metrics.abstainEvaluable)
        assertEquals("兩個 abstain 步的 Top-1 都是錯的，abstain 是正確決定", 2, metrics.abstainCorrect)
        assertEquals(1.0, metrics.abstainPrecision, 1e-6)
    }

    @Test
    fun `evaluateAll merges steps before computing rates`() {
        val fixture = loadSeed()
        val merged = GroundingReplay.evaluateAll(listOf(fixture, fixture))
        assertEquals(6, merged.steps)
        assertEquals(6, merged.labeledSteps)
        assertEquals(2, merged.top1Hits)
        assertEquals(6, merged.top3Hits)
        assertEquals(1.0 / 3.0, merged.top1Rate, 1e-6)
    }

    @Test
    fun `gate failures point at top1 regression only for seed fixtures`() {
        val failures = GroundingReplay.gateFailures(GroundingReplay.evaluate(loadSeed()))
        assertTrue(failures.any { it.contains("Top-1") })
        assertFalse(failures.any { it.contains("Top-3") })
        assertFalse(failures.any { it.contains("錯點率") })
        assertFalse(failures.any { it.contains("延遲") })
    }

    @Test
    fun `empty label set reports no measurable data`() {
        val unlabeledFixture = ReplayFixture(name = "unlabeled", steps = listOf(step(labelIndex = -1)))
        val metrics = GroundingReplay.evaluate(unlabeledFixture)
        assertEquals(0, metrics.labeledSteps)
        assertTrue(metrics.top1Rate.isNaN())
        assertTrue(metrics.abstainPrecision.isNaN())
        assertEquals(listOf("尚無可量測資料：labeledSteps=0"), GroundingReplay.gateFailures(metrics))
    }

    @Test
    fun `trace shaped json without label fields parses as unlabeled`() {
        val trace = GroundingTrace(
            pkg = "com.wae.agent",
            posture = "FORM",
            stateId = "S1",
            goal = "T1",
            candidates = listOf(candidate(0, 0.8f), candidate(1, 0.2f)),
            chosenIndex = 0,
            changed = true,
            postconditionVerified = false,
            elapsedMs = 5L
        )
        val fixture = GroundingReplay.parseFixture(
            "{\"name\":\"from_trace\",\"steps\":[${GroundingTraceRecorder.toJson(trace)}]}"
        )
        assertEquals("from_trace", fixture.name)
        assertEquals(1, fixture.steps.size)
        val parsed = fixture.steps[0]
        assertEquals(-1, parsed.labelIndex)
        assertEquals("unlabeled", parsed.labelReason)
        assertEquals(0, parsed.chosenIndex)
        assertEquals(2, parsed.candidates.size)
        assertTrue(parsed.candidates[0].bounds.contentEquals(intArrayOf(0, 0, 100, 50)))
        assertEquals(0.8f, parsed.candidates[0].score, 1e-4f)
    }

    @Test
    fun `malformed fixture json is rejected with reason`() {
        assertThrows(IllegalArgumentException::class.java) { GroundingReplay.parseFixture("{") }
        assertThrows(IllegalArgumentException::class.java) { GroundingReplay.parseFixture("{}") }
        assertThrows(IllegalArgumentException::class.java) {
            GroundingReplay.parseFixture("{\"steps\":[]}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundingReplay.parseFixture("{\"steps\":[{\"candidates\":[]}]}")
        }
        // labelIndex 超出候選數
        assertThrows(IllegalArgumentException::class.java) {
            GroundingReplay.parseFixture(
                "{\"steps\":[{\"labelIndex\":5,\"candidates\":[{\"bounds\":[0,0,1,1],\"score\":0.5}]}]}"
            )
        }
        // bounds 長度不是 4
        assertThrows(IllegalArgumentException::class.java) {
            GroundingReplay.parseFixture(
                "{\"steps\":[{\"candidates\":[{\"bounds\":[0,0],\"score\":0.5}]}]}"
            )
        }
    }

    @Test
    fun `parseFixtureOrNull returns null on bad json and fixture on good json`() {
        assertNull(GroundingReplay.parseFixtureOrNull("not json"))
        assertNotNull(GroundingReplay.parseFixtureOrNull(fixtureFile().readText(Charsets.UTF_8)))
    }
}
