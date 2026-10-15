package app.eye.android

import app.eye.android.core.HistorySearch
import app.eye.android.core.PdfInput
import app.eye.android.core.PdfStr
import app.eye.android.core.PdfStyle
import app.eye.android.core.ReportPdf
import app.eye.android.data.HistoryRepository
import app.eye.android.data.HistoryRetention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class V4Tests {
    private val day = 24L * 60 * 60 * 1000
    private val str = PdfStr { id, args -> "S$id:" + args.joinToString("|") }

    @Test fun sha256IsStableHexOfUtf8() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ReportPdf.sha256(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReportPdf.sha256("abc"))
        assertEquals(64, ReportPdf.sha256("è").length)
    }

    private fun input(plain: Boolean) = PdfInput(
        createdAt = 0, appVersion = "0.5", rulesVersion = 7L, levelTitle = "Probabile truffa",
        reasons = listOf("Link sospetto"), steps = listOf("Non cliccare"),
        originalText = "Chiama 3331234567 su http://poste-x.top/a", domains = listOf("poste-x.top", "poste-x.top"), includePlainText = plain,
    )

    @Test fun pdfRedactsTextByDefaultAndKeepsHashOfOriginal() {
        val b = ReportPdf.content(input(false), str, Locale.ITALY)
        val all = b.joinToString("\n") { it.text }
        assertFalse(all.contains("3331234567"))
        assertTrue(all.contains(ReportPdf.sha256(input(false).originalText)))
        assertTrue(all.contains("poste-x[.]top"))
        assertEquals(1, all.split("poste-x[.]top").size - 1) // domini senza doppioni
        assertEquals(PdfStyle.TITLE, b.first().style)
    }

    @Test fun pdfPlainTextOptionIncludesTextAndWarning() {
        val b = ReportPdf.content(input(true), str, Locale.ITALY)
        val all = b.joinToString("\n") { it.text }
        assertTrue(all.contains("Chiama 3331234567"))
        assertTrue(b.any { it.style == PdfStyle.NOTE && it.text.startsWith("S${R.string.pdf_plain_warning}") })
        assertEquals(ReportPdf.sha256(input(true).originalText), ReportPdf.sha256(input(false).originalText))
    }

    @Test fun searchIsCaseInsensitiveAndEmptyMatchesAll() {
        assertTrue(HistorySearch.matches("", "x"))
        assertTrue(HistorySearch.matches("  poste ", "Messaggio da POSTE italiane"))
        assertFalse(HistorySearch.matches("banca", "Messaggio da poste", "anteprima"))
    }

    @Test fun retentionChoicesAndDefault() {
        assertEquals(HistoryRetention.DAYS_7, HistoryRetention.fromId(null))
        assertEquals(HistoryRetention.DAYS_7, HistoryRetention.fromId("boh"))
        assertEquals(HistoryRetention.FOREVER, HistoryRetention.fromId("FOREVER"))
        assertEquals(listOf(7, 30, 0), HistoryRetention.entries.map { it.days })
    }

    @Test fun retention30KeepsMonthSavedAlwaysAndForeverKeepsAll() = runBlocking {
        var now = 0L
        val days = MutableStateFlow(30)
        val dao = FakeDao()
        val repo = HistoryRepository(dao, retentionDays = days) { now }
        repo.save("SOSPETTO", "a", emptyList())
        repo.save("SOSPETTO", "salvata", emptyList(), saved = true)
        now = 20 * day
        assertEquals(0, repo.purge())
        assertEquals(setOf("a", "salvata"), repo.observe().first().map { it.preview }.toSet())
        now = 40 * day
        assertEquals(1, repo.purge())
        assertEquals(listOf("salvata"), dao.rows.value.map { it.preview })
        days.value = 0 // sempre
        now = 4000 * day
        assertEquals(0, repo.purge())
        assertEquals(listOf("salvata"), repo.observe().first().map { it.preview })
        repo.save("SOSPETTO", "b", emptyList())
        now = 9000 * day
        assertEquals(2, repo.observe().first().size)
    }
}
