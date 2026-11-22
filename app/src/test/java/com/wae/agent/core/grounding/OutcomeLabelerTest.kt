package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2 自動標註飛輪的 JVM 單元測試（Phase 3）。
 *
 * 守住三件事：
 *  1. [OutcomeLabeler.labelStep] 的三種弱標籤規則與優先序（changed > postcondition > no_change）
 *  2. [OutcomeLabeler.toJson] 手寫 JSON 的格式穩定（欄序/排序/小數位/跳脫/濾除壞值），
 *     因為訓練器 [tools/grounding/train_grounding.py] 直接吃這個格式
 *  3. [OutcomeLabeler.record] 在沒有 Context（開發卡片/JVM 測試）時是安全 no-op
 *
 * 這支測試不碰 Android framework：record 只測 `context == null` 的提前返回路徑。
 */
class OutcomeLabelerTest {

    // 刻意回傳 MutableMap：有一個測試要驗證 labelStep 不會別名（alias）呼叫端的 map
    private fun features(vararg pairs: Pair<String, Float>): MutableMap<String, Float> =
        linkedMapOf(*pairs)

    // ------------------------------------------------------------------
    // labelStep：弱標籤規則
    // ------------------------------------------------------------------

    @Test
    fun `changed screen produces positive sample with screen_changed reason`() {
        val sample = OutcomeLabeler.labelStep(
            features("flag:isClickable" to 1f, "num:textSim" to 0.8f),
            changed = true,
            postconditionVerified = false
        )
        assertNotNull(sample)
        assertEquals(1, sample!!.label)
        assertEquals(OutcomeLabeler.REASON_SCREEN_CHANGED, sample.reason)
        assertEquals(1f, sample.features["flag:isClickable"]!!, 0f)
        assertEquals(0.8f, sample.features["num:textSim"]!!, 0f)
    }

    @Test
    fun `postcondition without visual change still counts as success`() {
        val sample = OutcomeLabeler.labelStep(
            features("flag:isEditable" to 1f),
            changed = false,
            postconditionVerified = true
        )
        assertNotNull(sample)
        assertEquals(1, sample!!.label)
        assertEquals(OutcomeLabeler.REASON_POSTCONDITION, sample.reason)
    }

    @Test
    fun `neither change nor postcondition is a negative sample`() {
        val sample = OutcomeLabeler.labelStep(
            features("num:textSim" to 0.2f),
            changed = false,
            postconditionVerified = false
        )
        assertNotNull(sample)
        assertEquals(0, sample!!.label)
        assertEquals(OutcomeLabeler.REASON_NO_CHANGE, sample.reason)
    }

    @Test
    fun `screen change takes priority over postcondition`() {
        val sample = OutcomeLabeler.labelStep(
            features("flag:clickable" to 1f),
            changed = true,
            postconditionVerified = true
        )
        assertNotNull(sample)
        assertEquals(1, sample!!.label)
        assertEquals(OutcomeLabeler.REASON_SCREEN_CHANGED, sample.reason)
    }

    @Test
    fun `empty features produce no sample`() {
        assertNull(OutcomeLabeler.labelStep(emptyMap(), changed = true, postconditionVerified = true))
    }

    @Test
    fun `all zero or non-finite features produce no sample`() {
        assertNull(
            OutcomeLabeler.labelStep(
                features("num:a" to 0f, "num:b" to -0f),
                changed = true,
                postconditionVerified = false
            )
        )
        assertNull(
            OutcomeLabeler.labelStep(
                features("num:nan" to Float.NaN, "num:inf" to Float.POSITIVE_INFINITY),
                changed = false,
                postconditionVerified = false
            )
        )
    }

    @Test
    fun `a single non-zero finite feature is enough`() {
        val sample = OutcomeLabeler.labelStep(
            features("num:a" to 0f, "num:b" to 0.25f),
            changed = false,
            postconditionVerified = false
        )
        assertNotNull(sample)
        assertEquals(0, sample!!.label)
    }

    @Test
    fun `sample does not alias the caller feature map`() {
        val source = features("num:a" to 0.5f)
        val sample = OutcomeLabeler.labelStep(source, changed = true, postconditionVerified = false)!!
        source["num:a"] = 0.9f
        source["num:late"] = 1f
        assertEquals(0.5f, sample.features["num:a"]!!, 0f)
        assertFalse(sample.features.containsKey("num:late"))
    }

    // ------------------------------------------------------------------
    // toJson：訓練器吃的 JSONL 格式
    // ------------------------------------------------------------------

    @Test
    fun `toJson emits stable sorted features and three decimals`() {
        val json = OutcomeLabeler.toJson(
            GroundingSample(
                features = features("num:z" to 0f, "num:a" to 0.5f, "flag:b" to 1f),
                label = 1,
                reason = OutcomeLabeler.REASON_SCREEN_CHANGED
            )
        )
        assertEquals(
            "{\"features\":{\"flag:b\":1.000,\"num:a\":0.500},\"label\":1," +
                "\"reason\":\"screen_changed\"}",
            json
        )
    }

    @Test
    fun `toJson keeps negative values and drops zero plus non-finite ones`() {
        val json = OutcomeLabeler.toJson(
            GroundingSample(
                features = features(
                    "num:neg" to -0.5f,
                    "num:zero" to 0f,
                    "num:nan" to Float.NaN,
                    "num:inf" to Float.NEGATIVE_INFINITY
                ),
                label = 0,
                reason = OutcomeLabeler.REASON_NO_CHANGE
            )
        )
        assertEquals(
            "{\"features\":{\"num:neg\":-0.500},\"label\":0,\"reason\":\"no_change\"}",
            json
        )
    }

    @Test
    fun `toJson escapes quotes backslashes and control characters`() {
        val json = OutcomeLabeler.toJson(
            GroundingSample(
                features = features("flag:a\"b" to 1f, "flag:c\\d" to 1f),
                label = 1,
                reason = "line1\nline2\ttab"
            )
        )
        assertEquals(
            "{\"features\":{\"flag:a\\\"b\":1.000,\"flag:c\\\\d\":1.000},\"label\":1," +
                "\"reason\":\"line1\\nline2\\ttab\"}",
            json
        )
    }

    @Test
    fun `toJson normalizes label into zero or one`() {
        val pos = OutcomeLabeler.toJson(
            GroundingSample(features("num:a" to 1f), label = 7, reason = "screen_changed")
        )
        val neg = OutcomeLabeler.toJson(
            GroundingSample(features("num:a" to 1f), label = -3, reason = "no_change")
        )
        assertTrue(pos.contains("\"label\":1,"))
        assertTrue(neg.contains("\"label\":0,"))
    }

    @Test
    fun `toJson returns a single line without hardcoded whitespace`() {
        val json = OutcomeLabeler.toJson(
            GroundingSample(
                features = features("num:a" to 1f, "num:b" to 2f),
                label = 1,
                reason = "postcondition"
            )
        )
        assertFalse(json.contains("\n"))
        assertFalse(json.contains(": "))
        assertTrue(json.startsWith("{\"features\":{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun `toJson output parses with a real JSON parser and roundtrips escaping`() {
        // 用 json.org 真的 parse 一次，確保手寫 JSON 不是「看起來像」而是合法；
        // 訓練器是 python json.loads，任何逃脫錯誤都會讓樣本被跳過。
        val sample = GroundingSample(
            features = features("flag:a\"b" to 1f, "num:a" to 0.5f),
            label = 1,
            reason = "line1\nline2\ttab"
        )
        val obj = org.json.JSONObject(OutcomeLabeler.toJson(sample))
        assertEquals(1, obj.getInt("label"))
        assertEquals("line1\nline2\ttab", obj.getString("reason"))
        val parsed = obj.getJSONObject("features")
        assertEquals(1.0, parsed.getDouble("flag:a\"b"), 0.0)
        assertEquals(0.5, parsed.getDouble("num:a"), 0.0)
    }

    // ------------------------------------------------------------------
    // record：沒有 Context 時必須是安全 no-op（絕不影響主流程）
    // ------------------------------------------------------------------

    @Test
    fun `record with null context does not throw`() {
        val sample = GroundingSample(features("num:a" to 1f), 1, OutcomeLabeler.REASON_SCREEN_CHANGED)
        OutcomeLabeler.record(null, sample)
        OutcomeLabeler.record(null, GroundingSample(emptyMap(), 0, OutcomeLabeler.REASON_NO_CHANGE))
    }

    @Test
    fun `clearSamples with null context returns false`() {
        assertFalse(OutcomeLabeler.clearSamples(null))
    }

    @Test
    fun `default samples file name is grounded in the real file name`() {
        assertEquals("grounding_samples.jsonl", OutcomeLabeler.DEFAULT_SAMPLES_FILE)
    }
}
