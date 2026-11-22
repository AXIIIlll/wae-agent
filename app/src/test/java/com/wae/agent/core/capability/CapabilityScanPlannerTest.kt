package com.wae.agent.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自動掃描的調度邏輯測試（純 JVM，不需要 Android）。
 *
 * 這些案例對應「別人的手機」上最常見的情境：
 * 首次啟動、快取有效、安裝了 App、卸載了 App、靜默更新、快取過期。
 */
class CapabilityScanPlannerTest {

    private val options = ScanOptions(maxApps = 3, rescanTtlMs = 7L * 24 * 3600 * 1000)
    private val now = 1_700_000_000_000L

    private fun app(pkg: String, version: Long, launchable: Boolean = true, system: Boolean = false) =
        RawAppInfo(
            pkg = pkg, label = pkg, versionCode = version, installer = "",
            category = 0, targetSdk = 34, launchable = launchable, isSystem = system,
            permissions = emptyList(), exportedActivities = emptyList()
        )

    private fun snapshot(apps: List<RawAppInfo>, builtAt: Long = now) = CapabilitySnapshot(
        schemaVersion = CapabilitySchema.VERSION, builtAt = builtAt, partial = false, apps = apps
    )

    @Test
    fun `first launch does a full scan`() {
        val mode = CapabilityScanPlanner.decide(
            previous = null, currentVersionCodes = emptyMap(), nowMs = now,
            options = options, dirty = false
        )
        assertEquals(CapabilityScanPlanner.Mode.FULL_SCAN, mode)
    }

    @Test
    fun `fresh cache with no changes skips`() {
        val prev = snapshot(listOf(app("a", 1)))
        val mode = CapabilityScanPlanner.decide(
            previous = prev, currentVersionCodes = mapOf("a" to 1L), nowMs = now,
            options = options, dirty = false
        )
        assertEquals(CapabilityScanPlanner.Mode.SKIP, mode)
    }

    @Test
    fun `package install or uninstall triggers incremental scan`() {
        val prev = snapshot(listOf(app("a", 1)))
        val installed = CapabilityScanPlanner.decide(
            previous = prev, currentVersionCodes = mapOf("a" to 1L, "b" to 1L), nowMs = now,
            options = options, dirty = true
        )
        assertEquals(CapabilityScanPlanner.Mode.INCREMENTAL_SCAN, installed)

        val uninstalled = CapabilityScanPlanner.decide(
            previous = prev, currentVersionCodes = emptyMap(), nowMs = now,
            options = options, dirty = false
        )
        assertEquals(CapabilityScanPlanner.Mode.INCREMENTAL_SCAN, uninstalled)
    }

    @Test
    fun `silent app update triggers incremental scan even without broadcast`() {
        val prev = snapshot(listOf(app("a", 1)))
        val mode = CapabilityScanPlanner.decide(
            previous = prev, currentVersionCodes = mapOf("a" to 2L), nowMs = now,
            options = options, dirty = false
        )
        assertEquals(CapabilityScanPlanner.Mode.INCREMENTAL_SCAN, mode)
    }

    @Test
    fun `expired cache forces full rescan`() {
        val old = snapshot(listOf(app("a", 1)), builtAt = now - options.rescanTtlMs - 1)
        val mode = CapabilityScanPlanner.decide(
            previous = old, currentVersionCodes = mapOf("a" to 1L), nowMs = now,
            options = options, dirty = false
        )
        assertEquals(CapabilityScanPlanner.Mode.FULL_SCAN, mode)
    }

    @Test
    fun `partial snapshot does not fake package changes`() {
        // 上次是部分掃描（只抓到 1 個 App），但裝置上其實有 3 個 → 不該判定為「有新套件」
        val prev = CapabilitySnapshot(
            schemaVersion = CapabilitySchema.VERSION,
            builtAt = now,
            partial = true,
            apps = listOf(app("a", 1))
        )
        val mode = CapabilityScanPlanner.decide(
            previous = prev,
            currentVersionCodes = mapOf("a" to 1L, "b" to 1L, "c" to 1L),
            nowMs = now,
            options = options,
            dirty = false
        )
        assertEquals(
            "partial 快照不應因為「快照外還有其他 App」而被判定為變動",
            CapabilityScanPlanner.Mode.SKIP, mode
        )
    }

    @Test
    fun `schema version change forces full rescan`() {
        val old = CapabilitySnapshot(
            schemaVersion = 0, builtAt = now, partial = false, apps = listOf(app("a", 1))
        )
        val mode = CapabilityScanPlanner.decide(
            previous = old, currentVersionCodes = mapOf("a" to 1L), nowMs = now,
            options = options, dirty = false
        )
        assertEquals(CapabilityScanPlanner.Mode.FULL_SCAN, mode)
    }

    @Test
    fun `filter keeps launchable apps first and respects maxApps cap`() {
        val raw = listOf(
            app("sys1", 1, launchable = false, system = true),
            app("user1", 1, launchable = true),
            app("user2", 1, launchable = false),
            app("user3", 1, launchable = true)
        )
        val capped = CapabilityScanPlanner.filterAndCap(raw, options.copy(includeSystemApps = false))
        assertEquals(3, capped.size)
        assertTrue("可啟動的 App 應優先保留", capped.first().launchable)
        assertTrue(capped.none { it.isSystem })
    }

    @Test
    fun `merge keeps untouched apps and drops uninstalled ones`() {
        val prev = snapshot(listOf(app("a", 1), app("b", 1), app("c", 1)))
        val rescanned = listOf(app("b", 2))
        val merged = CapabilityScanPlanner.merge(
            previous = prev,
            rescanned = rescanned,
            currentPackages = setOf("a", "b")
        )
        val byPkg = merged.associateBy { it.pkg }
        assertEquals(setOf("a", "b"), byPkg.keys)
        assertEquals(2L, byPkg["b"]?.versionCode)
    }

    @Test
    fun `snapshot json round trips and rejects unknown schema`() {
        val snap = snapshot(listOf(app("a", 7)))
        val parsed = CapabilitySnapshot.parse(snap.toJson())
        assertEquals(CapabilitySchema.VERSION, parsed?.schemaVersion)
        assertEquals("a", parsed?.apps?.first()?.pkg)
        assertEquals(7L, parsed?.apps?.first()?.versionCode)

        val tampered = snap.toJson().replace("\"schemaVersion\":${CapabilitySchema.VERSION}", "\"schemaVersion\":999")
        assertEquals(null, CapabilitySnapshot.parse(tampered))
    }
}
