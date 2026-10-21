package app.eye.android

import app.eye.android.core.CheckOutcome
import app.eye.android.core.CheckUseCase
import app.eye.android.core.ReasonTone
import app.eye.android.core.Redactor
import app.eye.android.core.reasonTone
import app.eye.android.core.ai.AiProvider
import app.eye.android.data.HistoryRepository
import app.eye.android.data.Settings
import app.eye.android.data.VerdictJson
import app.eye.android.ui.Route
import app.eye.android.ui.TabNav
import app.eye.engine.Extracted
import app.eye.engine.Input
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Source
import app.eye.engine.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAndNavTest {
    private val verdict = Verdict(
        Level.PROBABILE_TRUFFA, 7,
        listOf(Reason("a.b", "Mette \"fretta\"\nsubito", 3), Reason("c.d", "Dominio noto", -2)),
        listOf("Non cliccare", "Chiama la banca"),
        Extracted(listOf("https://x.top/p"), listOf("x.top"), listOf("+393331234567"), listOf("IT60X0542811101000000123456"), listOf("12,50")),
        "banca", 1234L,
    )

    @Test fun verdictJsonRoundTrip() {
        assertEquals(verdict, VerdictJson.decode(VerdictJson.encode(verdict)))
        val bare = verdict.copy(category = null, reasons = emptyList(), actions = emptyList(), extracted = Extracted())
        assertEquals(bare, VerdictJson.decode(VerdictJson.encode(bare)))
    }

    @Test fun verdictJsonRejectsCorruptData() {
        listOf("", "x", "{}", "[]", """{"level":"BOH"}""", """{"level":""").forEach { assertNull(it, VerdictJson.decode(it)) }
    }

    @Test fun historyKeepsFullTextAndVerdictForRetrieval() = runBlocking {
        val dao = FakeDao()
        val text = "Urgente https://poste-verifica.com/x chiama +39 333 1234567"
        val uc = CheckUseCase(FakeAnalyzer(), HistoryRepository(dao), { true })
        assertTrue(uc.run(text, Source.OCR) is CheckOutcome.Done)
        val e = dao.rows.value.single()
        assertEquals(text, e.fullText)
        assertEquals(FakeAnalyzer().analyze(Input(text, Source.OCR)), VerdictJson.decode(e.verdictJson))
        assertFalse(e.preview.contains("1234567")) // l'anteprima resta oscurata
        // il dettaglio mostra il testo oscurato con Redactor finché l'utente non lo scopre
        val hidden = Redactor.redact(e.fullText)
        assertFalse(hidden.contains("poste-verifica")); assertFalse(hidden.contains("1234567"))
    }

    @Test fun nothingSavedWhenHistoryOff() = runBlocking {
        val dao = FakeDao()
        CheckUseCase(FakeAnalyzer(), HistoryRepository(dao), { false }).run("ciao https://x.com", Source.PASTE)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun reasonToneDrivesIconColor() {
        assertEquals(ReasonTone.RISK, reasonTone(1))
        assertEquals(ReasonTone.NEUTRAL, reasonTone(0))
        assertEquals(ReasonTone.REDUCES, reasonTone(-1))
    }

    // --- navigazione
    @Test fun homeAlwaysClearsStackAbove() {
        val stacks = listOf(
            listOf(Route.HOME), listOf(Route.HOME, Route.SETTINGS), listOf(Route.HOME, Route.CATALOG, Route.CARD),
            listOf(Route.HOME, Route.HISTORY, Route.HISTORY_ITEM), listOf(Route.HOME, Route.PASTE, Route.VERDICT),
        )
        stacks.forEach { assertEquals(listOf(Route.HOME), TabNav.apply(it, Route.HOME)) }
    }

    @Test fun homeWorksEvenWhenHomeIsNotInStack() {
        // es. dopo l'onboarding o uno stack anomalo: si riparte comunque da Home
        assertEquals(TabNav.Plan.ResetToHome, TabNav.plan(listOf(Route.SETTINGS), Route.HOME))
        assertEquals(listOf(Route.HOME), TabNav.apply(listOf(Route.ONBOARDING, Route.SETTINGS), Route.HOME))
        assertEquals(TabNav.Plan.PopToHome, TabNav.plan(listOf(Route.HOME, Route.SETTINGS), Route.HOME))
    }

    @Test fun otherTabsSitOnHomeWithoutGrowing() {
        assertEquals(listOf(Route.HOME, Route.SETTINGS), TabNav.apply(listOf(Route.HOME), Route.SETTINGS))
        assertEquals(listOf(Route.HOME, Route.HISTORY), TabNav.apply(listOf(Route.HOME, Route.SETTINGS), Route.HISTORY))
        assertEquals(listOf(Route.HOME, Route.SETTINGS), TabNav.apply(listOf(Route.HOME, Route.SETTINGS), Route.SETTINGS))
        // andata e ritorno ripetuti: Home risponde sempre
        var st = listOf(Route.HOME)
        repeat(5) { st = TabNav.apply(TabNav.apply(st, Route.CATALOG), Route.HOME); assertEquals(listOf(Route.HOME), st) }
    }

    @Test fun domainAgeCheckIsOffByDefault() { assertFalse(Settings().domainAgeCheck) }

    // --- impostazioni AI
    @Test fun aiConfiguredRequiresKeyAndValidCompatibleSetup() {
        assertFalse(Settings().aiConfigured)
        assertTrue(Settings(aiKeyCipher = "v1:x").aiConfigured)
        assertFalse(Settings(aiProvider = AiProvider.COMPATIBLE, aiKeyCipher = "v1:x", aiBaseUrl = "https://a.example").aiConfigured) // modello mancante
        assertFalse(Settings(aiProvider = AiProvider.COMPATIBLE, aiKeyCipher = "v1:x", aiModel = "m", aiBaseUrl = "http://a.example").aiConfigured)
        assertTrue(Settings(aiProvider = AiProvider.COMPATIBLE, aiKeyCipher = "v1:x", aiModel = "m", aiBaseUrl = "https://a.example").aiConfigured)
        assertFalse(Settings().aiSendUnredacted)
    }

    @Test fun redactKeepLinksKeepsUrlsButHidesPersonalData() {
        val t = "Chiama +39 333 1234567 o scrivi a mario@x.it, IBAN IT60X0542811101000000123456, codice 482913: https://poste-verifica.com/a/123456 (www.sito.top)"
        val r = Redactor.redactKeepLinks(t)
        assertTrue(r, r.contains("https://poste-verifica.com/a/123456"))
        assertTrue(r, r.contains("www.sito.top"))
        listOf("1234567", "mario@x.it", "IT60X05", "482913").forEach { assertFalse(r, r.contains(it)) }
        assertEquals("ciao a tutti", Redactor.redactKeepLinks("ciao a tutti"))
    }

    private class FakeCrypto(var alive: Boolean = true) : app.eye.android.data.TextCrypto {
        override fun encrypt(plain: String) = "v1:" + plain.reversed()
        override fun decrypt(stored: String): String? = if (alive && stored.startsWith("v1:")) stored.removePrefix("v1:").reversed() else null
    }

    @Test fun historyIsEncryptedAtRestAndUnreadableRowsAreDropped() = runBlocking {
        val dao = FakeDao()
        val crypto = FakeCrypto()
        val repo = HistoryRepository(dao, crypto)
        val text = "Il tuo codice 482913 IBAN IT60X0542811101000000123456"
        repo.save("SOSPETTO", "anteprima", listOf("m"), text, VerdictJson.encode(verdict))
        val raw = dao.rows.value.single()
        assertFalse(raw.fullText.contains("482913")); assertFalse(raw.verdictJson.contains("x.top"))
        val read = repo.observe().first().single()
        assertEquals(text, read.fullText)
        assertEquals(verdict, VerdictJson.decode(read.verdictJson))
        crypto.alive = false // chiave Keystore invalidata
        assertTrue(repo.observe().first().isEmpty())
        assertEquals(1, repo.purge())
        assertTrue(dao.rows.value.isEmpty())
    }
}
