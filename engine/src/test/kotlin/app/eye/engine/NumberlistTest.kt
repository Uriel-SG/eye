package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Normalizzazione dei numeri, feed «numeri» (firma, anti-rollback), NUM-01/NUM-02 e gate sulle notifiche legittime. */
class NumberlistTest {
    private fun n(raw: String) = PhoneNumbers.parse(raw)

    // ---- normalizzazione (stessi vettori di tools/numbers/test_build_numbers.py)

    @Test fun normalizesItalianForms() {
        for (raw in listOf("+39 333 123 4567", "0039 333-123.4567", "333 1234567", "(333) 123 4567", "+39333 1234567")) {
            assertEquals(PhoneNumber("+393331234567", NumberKind.MOBILE), n(raw), raw)
        }
        assertEquals(PhoneNumber("+390212345678", NumberKind.LANDLINE), n("02 1234 5678"))
        assertEquals(PhoneNumber("+390212345678", NumberKind.LANDLINE), n("+39 02 1234 5678"))
        assertEquals(PhoneNumber("+447911123456", NumberKind.INTL), n("0044 7911 123456"))
        assertEquals(PhoneNumber("+21620123456", NumberKind.INTL), n("+216 20 123 456"))
    }

    @Test fun specialNumbersAreNeverListable() {
        assertEquals(NumberKind.TOLL_FREE, n("800 123 456")!!.kind)
        assertEquals(NumberKind.TOLL_FREE, n("+39 803 160")!!.kind)
        assertEquals(NumberKind.SHORT, n("112")!!.kind)
        assertEquals(NumberKind.OTHER_IT, n("116 117")!!.kind)
        assertEquals(NumberKind.OTHER_IT, n("840 123 456")!!.kind)
        assertEquals(NumberKind.PREMIUM, n("899 123 456")!!.kind)
        assertEquals(NumberKind.PREMIUM, n("+39 892 424")!!.kind)
        assertEquals(NumberKind.PREMIUM, n("144 123456")!!.kind)
        assertEquals(NumberKind.PREMIUM, n("+979 1234 5678")!!.kind)
        for (raw in listOf("800 123 456", "112", "116 117", "899 123 456", "+979 1234 5678")) assertFalse(n(raw)!!.isListable, raw)
        assertTrue(n("333 1234567")!!.isListable)
    }

    @Test fun rejectsGarbage() {
        assertNull(n("abc"))
        assertNull(n(""))
        assertNull(n("+39 +333 1234567"))
        assertNull(n("333 123456789012")) // troppo lungo
        assertNull(n("1".repeat(41)))
    }

    @Test fun hashMatchesPythonTool() {
        // sha256("tel:+393331234567")[:6], come tools/numbers/build_numbers.py
        val v = HashBlocklist.hashOf(HashNumberlist.KEY_PREFIX + "+393331234567")
        assertEquals("663f1d78bd90", "%012x".format(v))
    }

    // ---- lookup

    private val list = HashNumberlist.of(listOf("+393331234567", "+390212345678", "+447911123456"))

    @Test fun lookupExactOnly() {
        assertTrue(list.isKnownSpam("+393331234567"))
        assertFalse(list.isKnownSpam("+393331234568"))
        assertFalse(list.isKnownSpam("393331234567"))
        assertFalse(HashNumberlist.of(emptyList()).isKnownSpam("+393331234567"))
        // spazio degli hash separato da quello degli host
        assertFalse(HashNumberlist(HashBlocklist.of(listOf("+393331234567"))).isKnownSpam("+393331234567"))
    }

    // ---- feed firmato

    private fun res(n: String) = javaClass.getResourceAsStream("/rulepack/$n")!!.use { it.readBytes() }
    private fun priv(n: String): Ed25519PrivateKeyParameters {
        val pem = res(n).toString(Charsets.US_ASCII).lines().filter { !it.startsWith("-----") }.joinToString("")
        return Ed25519PrivateKeyParameters(Base64.getDecoder().decode(pem).takeLast(32).toByteArray(), 0)
    }
    private val pubA = "QbS3C1wZQ1NxuL5IXZ+1UgMQWxulCMMGjTrZsOyocG0="
    private val verifier = numberlistVerifier(mapOf("TEST-eye-a" to pubA))
    private val b64 = Base64.getEncoder()
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun reason(r: Result<*>) = (r.exceptionOrNull() as? RulePackException)?.reason

    private fun hashes(vararg e164: String) = e164.map { HashBlocklist.hashOf(HashNumberlist.KEY_PREFIX + it) }.distinct().sorted()

    private fun plain(h: List<Long>, magic: String = "ENL1", version: Long = 5): ByteArray {
        val header = """{"version":$version,"keyId":"TEST-eye-a","issuedAt":"2026-10-24T00:00:00Z","expiresAt":"2026-11-24T00:00:00Z","count":${h.size},"hashBytes":6}""".toByteArray()
        val o = ByteArrayOutputStream()
        o.write(magic.toByteArray()); o.write(ByteBuffer.allocate(4).putInt(header.size).array()); o.write(header)
        fun u48(v: Long) = repeat(6) { o.write(((v shr (8 * (5 - it))) and 0xFF).toInt()) }
        for ((i, v) in h.withIndex()) {
            if (i == 0) { u48(v); continue }
            val gap = v - h[i - 1]
            if (gap < 0xFFFFFFFFL) o.write(ByteBuffer.allocate(4).putInt(gap.toInt()).array()) else { repeat(4) { o.write(0xFF) }; u48(v) }
        }
        return o.toByteArray()
    }

    private fun envelope(plain: ByteArray, version: Long = 5, format: Int = NUMBERS_FORMAT, domain: String = NUMBERS_DOMAIN, signKey: String = "TEST-eye-a.testkey.pem"): ByteArray {
        val payload = gz(plain)
        val s = Ed25519Signer().apply {
            init(true, priv(signKey))
            val m = SignedEnvelope.message(domain, "TEST-eye-a", version, payload); update(m, 0, m.size)
        }
        return ("""{"format":$format,"alg":"Ed25519","keyId":"TEST-eye-a","version":$version,"createdAt":"2026-10-24T10:00:00Z",""" +
            """"payload":"${b64.encodeToString(payload)}","sig":"${b64.encodeToString(s.generateSignature())}"}""").toByteArray()
    }

    private val h3 = hashes("+393331234567", "+390212345678", "+447911123456")

    @Test fun validFeedLoadsAndLooksUp() {
        val l = HashNumberlist(verifier.verify(envelope(plain(h3)), 0).getOrThrow())
        assertEquals(3, l.size)
        assertEquals(5, l.version)
        assertTrue(l.isKnownSpam("+393331234567"))
        assertFalse(l.isKnownSpam("+393331234568"))
    }

    @Test fun validFromPythonTool() {
        // prodotto da build_numbers.py + rulepack.py sign-numbers (seed: 3 numeri validi + 1 numero verde scartato)
        val l = HashNumberlist(verifier.verify(res("TEST-numbers-v7.json"), 6).getOrThrow())
        assertEquals(7, l.version)
        assertEquals(3, l.size)
        for (e in listOf("+393331234567", "+390212345678", "+447911123456")) assertTrue(l.isKnownSpam(e), e)
        assertFalse(l.isKnownSpam("+39800123456"))
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(res("TEST-numbers-v7.json"), 7)))
    }

    @Test fun rollbackTamperAndKeyRejected() {
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(plain(h3)), 5)))
        val env = envelope(plain(h3)).toString(Charsets.UTF_8)
        assertFalse(verifier.verify(env.replace("\"payload\":\"H4sI", "\"payload\":\"H4sJ").toByteArray(), 0).isSuccess)
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(envelope(plain(h3), signKey = "TEST-eye-b.testkey.pem"), 0)))
    }

    @Test fun feedsAreNotInterchangeable() {
        // un elenco siti (format 3, dominio EYE-BLOCKLIST, magic EBL1) non e' un elenco numeri, e viceversa
        val siteBytes = envelope(plain(h3, magic = "EBL1"), format = 3, domain = BlocklistVerifier.DOMAIN)
        assertEquals(RulePackException.Reason.UNSUPPORTED, reason(verifier.verify(siteBytes, 0)))
        val siteVerifier = BlocklistVerifier(mapOf("TEST-eye-a" to pubA))
        assertFalse(siteVerifier.verify(envelope(plain(h3)), 0).isSuccess)
        // firma di dominio sbagliato con format giusto
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(envelope(plain(h3), domain = BlocklistVerifier.DOMAIN), 0)))
        // magic sbagliato dentro un envelope per il resto valido
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST, reason(verifier.verify(envelope(plain(h3, magic = "EBL1")), 0)))
    }

    @Test fun storeAndHolder() {
        val dir = Files.createTempDirectory("nm").toFile()
        try {
            val store = numberlistStore(dir)
            assertNull(store.loadBlocklist(verifier))
            assertTrue(store.installNumberlist(verifier, envelope(plain(h3))).isSuccess)
            assertFalse(store.installNumberlist(verifier, envelope(plain(h3))).isSuccess) // stessa versione
            val holder = NumberlistHolder(store, verifier)
            assertTrue(holder.isKnownSpam("+393331234567"))
            assertNotNull(holder.current())
            File(dir, "numbers.eye.json").writeBytes("{}".toByteArray())
            holder.reload()
            assertFalse(holder.isKnownSpam("+393331234567")) // file rovinato: nessun elenco, nessuna eccezione
            assertFalse(File(dir, "blocklist.eye.json").exists()) // store separato da quello dei siti
        } finally { dir.deleteRecursively() }
    }

    // ---- NUM-01 / NUM-02

    private val engine = RuleEngine(RuleEngine.loadBundled(), numberReputation = list)
    private val plainEngine = RuleEngine(RuleEngine.loadBundled())

    @Test fun num01FiresOnListedNumberInAnyFormat() {
        for (t in listOf("Richiamami al 333 123 4567", "Scrivimi +39 333-1234567", "chiama lo 0039 333 123 4567", "Per info 02 1234 5678")) {
            val v = engine.analyze(Input(t, Source.PASTE))
            assertTrue(v.reasons.any { it.ruleId == "NUM-01" && "elenchi pubblici" in it.text }, t)
        }
        // la spiegazione riporta il numero normalizzato
        assertTrue(engine.analyze(Input("chiama 333 1234567", Source.PASTE)).reasons.first { it.ruleId == "NUM-01" }.text.contains("+393331234567"))
    }

    @Test fun num01SilentOtherwise() {
        assertTrue(plainEngine.analyze(Input("Richiamami al 333 123 4567", Source.PASTE)).reasons.none { it.ruleId == "NUM-01" })
        assertTrue(engine.analyze(Input("Richiamami al 333 123 4568", Source.PASTE)).reasons.none { it.ruleId == "NUM-01" })
    }

    @Test fun tollFreeShortAndPremiumNeverInList() {
        // anche se un feed (errato o manomesso) le contenesse, numeri verdi/brevi non sono mai interrogati
        val poisoned = HashNumberlist.of(listOf("+39800123456", "+39803160", "+39112", "+39899123456"))
        val e = RuleEngine(RuleEngine.loadBundled(), numberReputation = poisoned)
        for (t in listOf("Numero verde 800 123 456", "INPS 803 160", "Emergenze 112", "Chiama 899 123 456")) {
            assertTrue(e.analyze(Input(t, Source.PASTE)).reasons.none { it.ruleId == "NUM-01" }, t)
        }
    }

    @Test fun num02PremiumWithPrefix() {
        for (t in listOf("Richiama il +39 899 123456", "Chiama lo 0039 892 424 per il rimborso", "+979 1234 5678 ti ha cercato")) {
            val v = plainEngine.analyze(Input(t, Source.PASTE))
            assertTrue(v.reasons.any { it.ruleId == "NUM-02" }, t)
        }
        // le forme nazionali restano di PHN-01, senza doppio conteggio
        val v = plainEngine.analyze(Input("Chiama lo 899 123 456", Source.PASTE))
        assertTrue(v.reasons.any { it.ruleId == "PHN-01" } && v.reasons.none { it.ruleId == "NUM-02" })
        for (t in listOf("Numero verde 800 123 456", "Chiamaci al +39 02 1234 5678", "Call +44 7911 123456")) {
            assertTrue(plainEngine.analyze(Input(t, Source.PASTE)).reasons.none { it.ruleId == "NUM-02" }, t)
        }
    }

    @Test fun num01AlertsInBackgroundWithPhoneVector() {
        val v = engine.analyze(Input("Mario Rossi\nPotresti richiamarmi al 333 123 4567?", Source.SHARE, origin = "com.whatsapp"))
        assertTrue(v.reasons.any { it.ruleId == "NUM-01" })
        assertTrue(ActionVector.PHONE in v.vectors)
        assertTrue(AlertPolicy.shouldAlert(v), "avviso atteso: ${v.level} ${v.reasons.map { it.ruleId }}")
        assertTrue("NUM-01" in AlertPolicy.STRONG_RULE_IDS && "NUM-02" in AlertPolicy.STRONG_RULE_IDS)
    }

    // ---- gate: con un elenco pieno dei numeri delle truffe, zero avvisi in piu' sulle notifiche legittime

    private class Row(val text: String, val origin: String?, val sender: String?, val label: String?)

    private fun rows(name: String): List<Row> {
        val f = File("../corpus/$name.jsonl")
        assertTrue(f.exists(), "manca corpus/$name.jsonl")
        return f.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.map {
            Row(it["text"]!!.jsonPrimitive.content, it["origin"]?.jsonPrimitive?.content, it["sender"]?.jsonPrimitive?.content, it["label"]?.jsonPrimitive?.content)
        }
    }

    @Test fun gateLegitCorporaNeverHitNum01() {
        val scamNumbers = (rows("notif_scam") + rows("scam")).flatMap { Extractors.phones(it.text) }
            .mapNotNull { PhoneNumbers.parse(it) }.filter { it.isListable }.map { it.e164 }.toSet()
        assertTrue(scamNumbers.size > 50, "numeri nelle truffe: ${scamNumbers.size}")
        val full = RuleEngine(RuleEngine.loadBundled(), numberReputation = HashNumberlist.of(scamNumbers))
        val base = RuleEngine(RuleEngine.loadBundled())
        fun Row.input() = Input(text, Source.SHARE, declaredSender = sender, origin = origin)
        var num01 = 0; var alertsChanged = 0; var scamHits = 0
        for (name in listOf("notif_legit", "notif_legit_holdout")) for (r in rows(name)) {
            if (full.analyze(r.input()).reasons.any { it.ruleId == "NUM-01" }) num01++
            if (AlertPolicy.shouldAlert(full.analyze(r.input())) != AlertPolicy.shouldAlert(base.analyze(r.input()))) alertsChanged++
        }
        for (name in listOf("legit", "regression")) for (r in rows(name).filter { it.label != "scam" }) {
            if (full.analyze(Input(r.text, Source.PASTE)).reasons.any { it.ruleId == "NUM-01" }) num01++
        }
        for (r in rows("notif_scam")) if (full.analyze(r.input()).reasons.any { it.ruleId == "NUM-01" }) scamHits++
        assertEquals(0, num01, "NUM-01 su testi legittimi")
        assertEquals(0, alertsChanged, "avvisi sulle notifiche legittime cambiati dall'elenco")
        assertTrue(scamHits > 50, "NUM-01 sulle notifiche truffa: $scamHits")
    }
}
