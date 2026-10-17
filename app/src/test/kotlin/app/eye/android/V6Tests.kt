package app.eye.android

import app.eye.android.core.MiniJson
import app.eye.android.core.ai.AiConfig
import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiProvider
import app.eye.android.core.ai.AiRequests
import app.eye.android.core.ai.AiService
import app.eye.android.core.ai.HttpResult
import app.eye.android.core.notif.AlertBackend
import app.eye.android.core.notif.AlertStore
import app.eye.android.core.notif.LinkLines
import app.eye.android.core.notif.NotifData
import app.eye.android.core.notif.NotificationText
import app.eye.android.data.AiOpinionJson
import app.eye.android.data.HistoryRepository
import app.eye.android.data.MIGRATION_3_4
import app.eye.android.data.SavedAiOpinion
import app.eye.android.data.TextCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException

/** Avvisi cifrati e persistenti, robustezza del secondo parere AI, parere salvato in cronologia. */
class V6Tests {
    private class MapBackend : AlertBackend {
        val m = LinkedHashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    /** Cifratura finta ma riconoscibile: niente testo in chiaro su disco. */
    private object Rot : TextCrypto {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(stored: String) = stored.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")?.reversed()
    }

    // --- avvisi
    @Test fun alertSurvivesLongerThanFiveMinutesAndIsOneShot() {
        var now = 0L
        val b = MapBackend()
        val s = AlertStore(b, Rot) { now }
        assertTrue(s.put(1042, "testo segreto", "SOSPETTO"))
        assertFalse("testo in chiaro su disco", b.m.values.any { it.contains("testo segreto") })
        now = 7 * 60_000L // oltre i vecchi 5 minuti
        // anche con un'altra istanza (riavvio del processo) il dato c'è
        val again = AlertStore(b, Rot) { now }
        assertEquals("testo segreto", again.take(1042))
        assertNull(again.take(1042))
        assertEquals(0, again.count())
        assertTrue(b.m.isEmpty())
    }

    @Test fun dismissDeletesAndUnknownIdsAreSafe() {
        val b = MapBackend()
        val s = AlertStore(b, Rot)
        s.put(7, "a", "SOSPETTO"); s.put(8, "b", "PROBABILE_TRUFFA")
        s.discard(7)
        assertNull(s.take(7))
        assertEquals("b", s.take(8))
        assertNull(s.take(999)); s.discard(999)
    }

    @Test fun purgeRemovesOnlyOrphansOlderThanThirtyDays() {
        val day = 24L * 60 * 60 * 1000
        var now = 0L
        val b = MapBackend()
        val s = AlertStore(b, Rot) { now }
        s.put(1, "vecchio", "SOSPETTO")
        now = 20 * day; s.put(2, "recente", "SOSPETTO")
        now = 31 * day
        assertEquals(1, s.purge())
        assertEquals(1, s.count())
        assertEquals("recente", s.take(2))
        b.m["t5"] = "0" // data senza dato
        s.purge(); assertNull(b.m["t5"])
    }

    @Test fun unreadableAlertIsDroppedNotShown() {
        val b = MapBackend()
        b.m["d3"] = "garbage"; b.m["t3"] = "0"
        assertNull(AlertStore(b, Rot).take(3))
        assertTrue(b.m.isEmpty())
    }

    // --- AI: retry, timeout, mappatura errori
    private fun cfg() = AiConfig(AiProvider.GEMINI, "gemini-3.8-flash", "", "key-12345678")
    private val ok = HttpResult(200, MiniJson.stringify(mapOf("candidates" to listOf(mapOf(
        "content" to mapOf("parts" to listOf(mapOf("text" to """{"giudizio":"sospetto","motivi":["m"],"consiglio":"c"}"""))), "finishReason" to "STOP")))))

    private class Script(val steps: List<() -> HttpResult>) {
        var calls = 0
        val timeouts = mutableListOf<Int>()
        fun transport() = app.eye.android.core.ai.HttpTransport { spec -> timeouts += spec.readTimeoutMs; steps[minOf(calls++, steps.size - 1)]() }
    }

    private fun svc(script: Script, clock: () -> Long = { 0L }, slept: MutableList<Long> = mutableListOf(), backoff: Long = 3_000) =
        AiService(sleeper = { slept += it }, backoffMs = { backoff }, clock = clock, transport = script.transport())

    @Test fun retriesOnceOnTransientHttpCodesThenSucceeds() {
        for (code in listOf(429, 500, 502, 503, 504)) {
            val sc = Script(listOf({ HttpResult(code, "") }, { ok }))
            val slept = mutableListOf<Long>()
            val o = svc(sc, slept = slept).ask(cfg(), "x")
            assertTrue("per $code", o is AiOutcome.Success)
            assertEquals(2, sc.calls); assertEquals(listOf(3_000L), slept)
        }
    }

    @Test fun retriesOnTimeoutOnlyOnceThenReportsTimeout() {
        val sc = Script(listOf({ throw SocketTimeoutException() }))
        val o = svc(sc).ask(cfg(), "x") as AiOutcome.Failure
        assertEquals(AiError.TIMEOUT, o.error); assertEquals(2, sc.calls)
    }

    @Test fun doesNotRetryAuthInvalidOrNetworkErrors() {
        for ((step, err) in listOf<Pair<() -> HttpResult, AiError>>(
            ({ HttpResult(401, "") } to AiError.AUTH), ({ HttpResult(403, "") } to AiError.AUTH),
            ({ HttpResult(404, "") } to AiError.HTTP), ({ HttpResult(200, "non json") } to AiError.INVALID),
            ({ throw IOException() } to AiError.NETWORK),
        )) {
            val sc = Script(listOf(step))
            assertEquals(err, (svc(sc).ask(cfg(), "x") as AiOutcome.Failure).error)
            assertEquals(1, sc.calls)
        }
    }

    @Test fun finalErrorsAreDistinct() {
        fun err(code: Int) = (svc(Script(listOf({ HttpResult(code, "") }))).ask(cfg(), "x") as AiOutcome.Failure).error
        assertEquals(AiError.RATE_LIMIT, err(429)); assertEquals(AiError.OVERLOADED, err(503)); assertEquals(AiError.AUTH, err(401))
        assertEquals(5, setOf(err(429), err(503), err(401), err(404), AiError.TIMEOUT).size)
    }

    @Test fun overallDeadlineLimitsTimeoutsAndSkipsRetryWithoutBudget() {
        var now = 0L
        // primo tentativo: 90 s e va in timeout; restano 30 s: il secondo tentativo ha al massimo 30 s di lettura
        val sc = Script(listOf({ now += 90_000; throw SocketTimeoutException() }, { ok }))
        val o = svc(sc, clock = { now }).ask(cfg(), "x")
        assertTrue(o is AiOutcome.Success)
        assertEquals(90_000, sc.timeouts[0]); assertEquals(30_000, sc.timeouts[1])
        // senza budget (110 s già passati) niente nuovo tentativo
        now = 0L
        val sc2 = Script(listOf({ now += 110_000; HttpResult(503, "") }, { ok }))
        assertEquals(AiError.OVERLOADED, (svc(sc2, clock = { now }).ask(cfg(), "x") as AiOutcome.Failure).error)
        assertEquals(1, sc2.calls)
    }

    @Test fun geminiBodyUsesJsonModeAndEnoughTokens() {
        @Suppress("UNCHECKED_CAST")
        val body = MiniJson.parse(AiRequests.build(cfg(), "ciao")!!.body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val gc = body["generationConfig"] as Map<String, Any?>
        assertEquals("application/json", gc["responseMimeType"])
        assertTrue((gc["maxOutputTokens"] as Number).toInt() >= 1024)
        assertNotNull(body["contents"])
    }

    // --- parere salvato
    @Test fun opinionJsonRoundTripAndRejectsGarbage() {
        val s = SavedAiOpinion(AiOpinion(AiJudgement.TRUFFA, listOf("a", "b"), "Non cliccare"), "gemini", "gemini-3.8-flash", 1234L)
        assertEquals(s, AiOpinionJson.decode(AiOpinionJson.encode(s)))
        assertNull(AiOpinionJson.decode("")); assertNull(AiOpinionJson.decode("{")); assertNull(AiOpinionJson.decode("""{"j":"boh"}"""))
    }

    @Test fun opinionIsStoredEncryptedAndSurvivesReopen() = runBlocking {
        val dao = FakeDao()
        val repo = HistoryRepository(dao, crypto = Rot)
        val id = repo.save("SOSPETTO", "p", listOf("m"), "testo", "{}")
        assertEquals("", repo.observe().first().single().aiOpinion)
        val json = AiOpinionJson.encode(SavedAiOpinion(AiOpinion(AiJudgement.SOSPETTO, listOf("motivo"), "consiglio"), "openai", "gpt", 1L))
        repo.setAiOpinion(id, json)
        assertFalse("parere in chiaro su disco", dao.rows.value.single().aiOpinion.contains("motivo"))
        assertEquals(json, repo.observe().first().single().aiOpinion) // come dopo un riavvio: ricostruito da disco
        repo.setAiOpinion(id, "")
        assertEquals("", repo.observe().first().single().aiOpinion)
    }

    // --- migrazione Room 3 -> 4 (SQL; la prova sul DB reale è lo smoke con install -r)
    @Test fun migration3to4AddsColumnWithoutDroppingData() {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, m, a ->
            if (m.name == "execSQL") sql += a!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_3_4.migrate(db)
        assertEquals(3, MIGRATION_3_4.startVersion); assertEquals(4, MIGRATION_3_4.endVersion)
        assertEquals(1, sql.size)
        assertTrue(sql[0].startsWith("ALTER TABLE history ADD COLUMN aiOpinion TEXT NOT NULL DEFAULT ''"))
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) })
    }

    // --- link reali dagli span
    @Test fun linkLinesDedupeCapAndMismatch() {
        val l = LinkLines.build(listOf(
            "poste.it" to "https://truffa.top/x", "poste.it" to "https://truffa.top/x",
            "https://ok.it/" to "https://ok.it", "tel" to "tel:123", "x" to "intent://evil", "" to "HTTP://a.b/c",
        ))
        assertEquals(listOf(
            "Link: https://truffa.top/x", "Link: https://ok.it", "Link: HTTP://a.b/c",
            "Il testo del link mostra poste.it ma porta a https://truffa.top/x",
        ), l)
        val many = LinkLines.build((1..30).map { "v$it" to "https://s$it.it" })
        assertEquals(10, many.count { it.startsWith("Link: ") })
        assertTrue(LinkLines.build(listOf("a" to "https://x.it/" + "a".repeat(5000))).first().length <= 2048 + 6)
    }

    @Test fun composeAppendsRealLinksAtTheEnd() {
        val n = NotifData("p", false, false, "Titolo", "Clicca qui", null, links = listOf("qui" to "https://reale.top/p"))
        val t = NotificationText.compose(n)!!
        assertTrue(t.startsWith("Titolo\nClicca qui\n"))
        assertTrue(t.endsWith("Il testo del link mostra qui ma porta a https://reale.top/p"))
        assertEquals("Titolo\nClicca qui",NotificationText.compose(NotifData("p", false, false, "Titolo", "Clicca qui", null)))
    }
}
