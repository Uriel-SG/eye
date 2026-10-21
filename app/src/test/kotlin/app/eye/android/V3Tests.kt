package app.eye.android

import app.eye.android.core.notif.AutoStatus
import app.eye.android.core.notif.MonitoredApps
import app.eye.android.core.notif.NotifData
import app.eye.android.core.notif.NotificationGate
import app.eye.android.core.notif.NotificationText
import app.eye.android.core.notif.autoStatus
import app.eye.android.data.HistoryRepository
import app.eye.android.data.MIGRATION_2_3
import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class V3Tests {
    private val wa = "com.whatsapp"
    private fun n(
        pkg: String = wa, title: String? = "Poste", text: String? = "ciao", big: String? = null,
        lines: List<String> = emptyList(), last: String? = null, ongoing: Boolean = false, summary: Boolean = false,
    ) = NotifData(pkg, ongoing, summary, title, text, big, lines, last)

    // --- estrazione
    @Test fun composesTitleAndBestBody() {
        assertEquals("Poste\nconto sospeso", NotificationText.compose(n(text = "conto", big = null, last = "conto sospeso")))
        assertEquals("Poste\ntesto lungo completo", NotificationText.compose(n(text = "testo", big = "testo lungo completo")))
        assertEquals("Poste\nultima", NotificationText.compose(n(text = "3 messaggi", lines = listOf("prima", "ultima"))))
        assertEquals("Poste\nciao", NotificationText.compose(n()))
        assertEquals("Solo titolo", NotificationText.compose(n(title = "Solo titolo", text = null)))
        assertNull(NotificationText.compose(n(title = " ", text = "")))
    }

    @Test fun doesNotRepeatTitleInsideBody() {
        assertEquals("Poste: verifica ora", NotificationText.compose(n(title = "Poste", text = "Poste: verifica ora")))
    }

    @Test fun truncatesAtTenThousand() {
        assertEquals(10_000, NotificationText.compose(n(text = "x".repeat(50_000)))!!.length)
    }

    // --- filtri
    private val monitored = setOf(wa, "org.telegram.messenger")

    @Test fun ignoresOwnOngoingSummaryAndUnselectedApps() {
        val g = NotificationGate("app.eye.android")
        assertNull(g.accept(n(pkg = "app.eye.android"), monitored + "app.eye.android"))
        assertNull(g.accept(n(ongoing = true), monitored))
        assertNull(g.accept(n(summary = true), monitored))
        assertNull(g.accept(n(pkg = "com.altra.app"), monitored))
        assertNotNull(g.accept(n(), monitored))
    }

    @Test fun dropsRecentDuplicatesThenAcceptsAfterWindow() {
        var now = 0L
        val g = NotificationGate("x", clock = { now }, dedupeMs = 60_000)
        assertNotNull(g.accept(n(), monitored))
        now = 5_000; assertNull(g.accept(n(), monitored))
        assertNotNull(g.accept(n(text = "altro"), monitored))
        assertNotNull(g.accept(n(pkg = "org.telegram.messenger"), monitored)) // stesso testo, altra app
        now = 70_000; assertNotNull(g.accept(n(), monitored))
    }

    @Test fun alertRateLimitIsPerApp() {
        var now = 0L
        val g = NotificationGate("x", clock = { now }, alertIntervalMs = 10_000)
        assertTrue(g.allowAlert(wa))
        now = 9_999; assertFalse(g.allowAlert(wa))
        assertTrue(g.allowAlert("org.telegram.messenger"))
        now = 10_000; assertTrue(g.allowAlert(wa))
    }

    private fun verdict(level: Level, vararg ids: String) =
        Verdict(level, 0, ids.map { Reason(it, "", 0) }, emptyList(), Extracted(), null, 1)

    @Test fun alertPolicyProbableAlwaysSuspectOnlyWithStrongSignal() {
        assertTrue(NotificationGate.shouldAlert(verdict(Level.PROBABILE_TRUFFA, "URG-05")))
        assertTrue(NotificationGate.shouldAlert(verdict(Level.SOSPETTO, "URL-01")))
        assertFalse(NotificationGate.shouldAlert(verdict(Level.SOSPETTO, "URG-05", "COM-02")))
        assertFalse(NotificationGate.shouldAlert(verdict(Level.NESSUN_SEGNALE)))
    }

    @Test fun monitoredAppsRespectDisabledAndDebugOnlyShell() {
        assertEquals(9, MonitoredApps.PRESETS.size)
        assertFalse("com.android.shell" in MonitoredApps.active(false, emptySet()))
        assertTrue("com.android.shell" in MonitoredApps.active(true, emptySet()))
        assertFalse(wa in MonitoredApps.active(false, setOf(wa)))
    }

    @Test fun statusReflectsRealPermissionState() {
        assertEquals(AutoStatus.OFF, autoStatus(false, true, true))
        assertEquals(AutoStatus.NEEDS_ACCESS, autoStatus(true, false, true))
        assertEquals(AutoStatus.ALERTS_BLOCKED, autoStatus(true, true, false))
        assertEquals(AutoStatus.ACTIVE, autoStatus(true, true, true))
    }

    // --- cronologia: salvate
    @Test fun purgeNeverDeletesSavedEntries() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        var now = 0L
        val dao = FakeDao()
        val repo = HistoryRepository(dao) { now }
        val keep = repo.save("SOSPETTO", "tenuta", listOf("m"), saved = true)
        repo.save("SOSPETTO", "vecchia", listOf("m"))
        now = 30 * day
        repo.save("SOSPETTO", "nuova", listOf("m"))
        assertEquals(setOf("tenuta", "nuova"), repo.observe().first().map { it.preview }.toSet())
        assertTrue(dao.rows.value.any { it.id == keep })
        assertFalse(dao.rows.value.any { it.preview == "vecchia" })
    }

    @Test fun toggleSavedAndClearWithoutSaved() = runBlocking {
        val dao = FakeDao()
        val repo = HistoryRepository(dao) { 5 }
        val a = repo.save("SOSPETTO", "a", emptyList())
        repo.save("SOSPETTO", "b", emptyList())
        repo.setSaved(a, true)
        repo.deleteAll(includeSaved = false)
        assertEquals(listOf("a"), dao.rows.value.map { it.preview })
        repo.setSaved(a, false)
        assertFalse(dao.rows.value.single().saved)
        repo.setSaved(a, true)
        repo.deleteAll(includeSaved = true)
        assertTrue(dao.rows.value.isEmpty())
    }

    // --- migrazione Room 2 -> 3 (non distruttiva)
    @Test fun migrationAddsColumnWithoutDroppingData() {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, args ->
            if (m.name == "execSQL") sql += args[0] as String
            null
        } as SupportSQLiteDatabase
        assertEquals(2, MIGRATION_2_3.startVersion); assertEquals(3, MIGRATION_2_3.endVersion)
        MIGRATION_2_3.migrate(db)
        assertEquals(listOf("ALTER TABLE history ADD COLUMN saved INTEGER NOT NULL DEFAULT 0"), sql)
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
    }
}
