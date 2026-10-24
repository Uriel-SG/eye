package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Segnalazioni reali dei tester (EN/IT/OCR) + esempi di sviluppo: corpus/regression.jsonl. */
class RegressionTest {
    private val engine = RuleEngine.default()
    private fun analyze(t: String, sender: String? = null) = engine.analyze(Input(t, Source.OCR, sender))

    private class Row(val id: String, val text: String, val scam: Boolean)

    private fun load(name: String): List<Row> {
        val f = File("../corpus/$name")
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            Row(o["id"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content, o["label"]!!.jsonPrimitive.content == "scam")
        }
    }

    @Test fun redditShareMentioningGoogleIsNotFlagged() {
        val v = analyze("Guarda questo thread su Google Antigravity: https://www.reddit.com/r/google_antigravity/comments/1abcd/x/")
        assertEquals(Level.NESSUN_SEGNALE, v.level, v.reasons.toString())
    }

    @Test fun googleEmailAboutWorkosIsNotFlagged() {
        val v = analyze("Google: hai condiviso dati con workos.com. Gestisci l'accesso su https://workos.com/docs", "Google")
        assertEquals(Level.NESSUN_SEGNALE, v.level, v.reasons.toString())
    }

    @Test fun lookalikeStillFlaggedEvenWithGoogleMention() {
        val v = analyze("Google: accedi al tuo account su https://google-account-verify.com/login")
        assertNotEquals(Level.NESSUN_SEGNALE, v.level)
    }

    @Test fun brandPresentingItselfWithWrongDomainIsFlagged() {
        val v = analyze("Il tuo account Google e' stato sospeso. Verifica qui: https://sicurezza-accessi.net/g")
        assertNotEquals(Level.NESSUN_SEGNALE, v.level)
    }

    @Test fun ocrFakeShopAdWithoutAnyActionIsNoSignal() {
        // 1.8.0: annuncio di un finto negozio senza link, numero ne' richiesta: nulla da fare, quindi nessun segnale (prima SOSPETTO)
        val ad = "22:23 A A FINAL NOTICE: [num] LAUNCH PRICING ENDS AT MIDNIGHT BIKEBEE | SUR- RON X! 53 Your $1,[num] Factory Discount Disa"
        assertEquals(Level.NESSUN_SEGNALE, analyze(ad).level)
        // appena c'e' un'azione (un link) torna a essere un sospetto
        assertNotEquals(Level.NESSUN_SEGNALE, analyze("$ad bikebee-outlet-xq7k.shop").level)
    }

    @Test fun unseenVariantsGeneralize() {
        val scams = listOf(
            "ACT NOW!! Your Apple ID is locked. Confirm your password within 12 hours: apple-id-support.xyz",
            "Congratulations, you were selected for a free cruise. Claim your prize before it expires today",
            "URGENT: tax office notice. Pay the outstanding $320 immediately to avoid arrest",
            "OFFERTA LAMPO SOLO OGGI! Smartphone 80% di sconto, prezzo di fabbrica 99 euro, ordina ora",
            "Your parcel is held at customs. Pay the fee of 2.99 GBP now: royalmail-fee.top",
            "Hurry! Limited time crypto giveaway, send 0.1 BTC and receive 1 BTC back",
        )
        val legit = listOf(
            "Ho letto un articolo su Apple e Microsoft, guarda https://www.reddit.com/r/technology/comments/abc/x/",
            "Please find attached the agenda for tomorrow's meeting. See you at 10.",
            "Your order has shipped. Track it on https://www.amazon.com/progress-tracker",
            "Reminder: the library book is due next Friday. Thank you!",
        )
        for (t in scams) assertNotEquals(Level.NESSUN_SEGNALE, analyze(t).level, t)
        for (t in legit) assertEquals(Level.NESSUN_SEGNALE, analyze(t).level, t + " " + analyze(t).reasons)
    }

    @Test fun regressionMetrics() {
        val rows = load("regression.jsonl")
        if (rows.isEmpty()) return
        val sc = rows.filter { it.scam }
        val lg = rows.filter { !it.scam }
        val missed = sc.filter { analyze(it.text).level == Level.NESSUN_SEGNALE }
        val fps = lg.filter { analyze(it.text).level != Level.NESSUN_SEGNALE }
        val recall = 100.0 * (sc.size - missed.size) / sc.size
        val fpRate = 100.0 * fps.size / lg.size
        println(String.format(Locale.ROOT, "\nREGRESSIONE recall %d/%d = %.1f%%, FP %d/%d = %.1f%%", sc.size - missed.size, sc.size, recall, fps.size, lg.size, fpRate))
        missed.forEach { println("  MISSED ${it.id} ${it.text.take(80)}") }
        fps.forEach { println("  FP ${it.id} ${analyze(it.text).reasons.map { r -> r.ruleId }} ${it.text.take(80)}") }
        assertTrue(recall >= 95.0, "recall $recall")
        assertTrue(fpRate <= 3.0, "FP $fpRate")
    }
}
