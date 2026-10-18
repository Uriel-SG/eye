package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserExceptionsTest {
    private fun verdict(level: Level, vararg ids: String) = Verdict(
        level, if (level == Level.NESSUN_SEGNALE) 0 else 5,
        ids.map { Reason(it, "motivo $it", 3) }, listOf("azione"), Extracted(), null, 1,
    )

    private fun verisure(time: String, date: String, extra: String = "") = Input(
        "Allarme inserito alle $time del $date. Nessuna anomalia rilevata dal sistema. $extra".trim(),
        Source.SHARE, declaredSender = "Verisure", origin = "com.verisure.app",
    )

    private fun trusted(base: Input = verisure("08:15", "12/10/2026"), v: Verdict = verdict(Level.SOSPETTO, "GEN-01")) =
        UserExceptions.create(ExceptionKind.TRUSTED, v, MessageFingerprint.of(base), "1", 0L)

    private fun matches(e: UserException, input: Input, v: Verdict = verdict(Level.SOSPETTO, "GEN-01")) =
        UserExceptions.apply(v, MessageFingerprint.of(input), listOf(e)).level == Level.NESSUN_SEGNALE

    @Test fun `stessa notifica con data e ora diverse corrisponde`() {
        val e = trusted()
        val out = UserExceptions.apply(verdict(Level.SOSPETTO, "GEN-01"), MessageFingerprint.of(verisure("21:47", "3 novembre 2026")), listOf(e))
        assertEquals(Level.NESSUN_SEGNALE, out.level)
        assertEquals("USR-01", out.reasons.first().ruleId)
        assertEquals(0, out.reasons.first().weight)
    }

    @Test fun `link nuovo non corrisponde`() {
        assertFalse(matches(trusted(), verisure("09:00", "13/10/2026", "Dettagli su http://verisure-accesso.top/login")))
    }

    @Test fun `richiesta password con regola grave nuova non corrisponde`() {
        val e = trusted()
        val engine = RuleEngine.default()
        val bad = verisure("09:00", "13/10/2026", "Inserisci la password per confermare.")
        val v = engine.analyze(bad)
        val out = UserExceptions.apply(v, MessageFingerprint.of(bad), listOf(e))
        assertTrue(out.level == v.level)
        // anche con testo identico e regola grave nuova nel verdetto
        assertFalse(matches(e, verisure("09:00", "13/10/2026"), verdict(Level.SOSPETTO, "GEN-01", "BAN-03")))
    }

    @Test fun `mittente o app diversi e testo diverso non corrispondono`() {
        val e = trusted()
        assertFalse(matches(e, verisure("09:00", "13/10/2026").copy(declaredSender = "Qualcun altro")))
        assertFalse(matches(e, verisure("09:00", "13/10/2026").copy(origin = "com.altra.app")))
        assertFalse(matches(e, Input("Il tuo pacco e fermo in dogana, paga subito la tassa di sdoganamento", Source.SHARE, "Verisure", "com.verisure.app")))
    }

    @Test fun `sospetto alza a sospetto e non abbassa`() {
        val e = UserExceptions.create(ExceptionKind.SUSPICIOUS, verdict(Level.NESSUN_SEGNALE), MessageFingerprint.of(verisure("08:15", "12/10/2026")), "2", 0L)
        val fp = MessageFingerprint.of(verisure("10:00", "14/10/2026"))
        val up = UserExceptions.apply(verdict(Level.NESSUN_SEGNALE), fp, listOf(e))
        assertEquals(Level.SOSPETTO, up.level)
        assertEquals("USR-02", up.reasons.first().ruleId)
        assertEquals(Level.PROBABILE_TRUFFA, UserExceptions.apply(verdict(Level.PROBABILE_TRUFFA, "URL-01"), fp, listOf(e)).level)
    }

    @Test fun `probabile con regola grave nuova non viene abbassato`() {
        val e = trusted()
        val fp = MessageFingerprint.of(verisure("10:00", "14/10/2026"))
        assertEquals(Level.PROBABILE_TRUFFA, UserExceptions.apply(verdict(Level.PROBABILE_TRUFFA, "GEN-01", "URL-01"), fp, listOf(e)).level)
        // regola grave già presente alla creazione: ammessa
        val e2 = trusted(v = verdict(Level.PROBABILE_TRUFFA, "URL-01"))
        assertEquals(Level.NESSUN_SEGNALE, UserExceptions.apply(verdict(Level.PROBABILE_TRUFFA, "URL-01"), fp, listOf(e2)).level)
    }

    @Test fun `decorator applica le eccezioni dopo l'analisi`() {
        val e = trusted()
        val a = ExceptionAwareAnalyzer({ verdict(Level.SOSPETTO, "GEN-01") }.let { f -> object : Analyzer { override fun analyze(input: Input) = f() } }, { listOf(e) })
        assertEquals(Level.NESSUN_SEGNALE, a.analyze(verisure("07:00", "1/1/2027")).level)
    }

    @Test fun `anteprima oscurata e breve`() {
        assertTrue(MessageFingerprint.of(verisure("08:15", "12/10/2026")).preview.let { it.length <= 80 && it.none(Char::isDigit) })
    }
}
