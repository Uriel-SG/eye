package app.eye.engine

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Feed «siti malevoli»: firma, anti-rollback, manomissione, lookup, BLK-01 e AlertPolicy. Chiavi in resources/rulepack: SOLO TEST. */
class BlocklistTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/rulepack/$n")!!.use { it.readBytes() }
    private fun priv(n: String): Ed25519PrivateKeyParameters {
        val pem = res(n).toString(Charsets.US_ASCII).lines().filter { !it.startsWith("-----") }.joinToString("")
        return Ed25519PrivateKeyParameters(Base64.getDecoder().decode(pem).takeLast(32).toByteArray(), 0)
    }
    private val pubA = "QbS3C1wZQ1NxuL5IXZ+1UgMQWxulCMMGjTrZsOyocG0="
    private val verifier = BlocklistVerifier(mapOf("TEST-eye-a" to pubA))
    private val b64 = Base64.getEncoder()

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** Stesso contenitore del tool Python, costruito qui per poter variare i campi. */
    private fun plain(
        hashes: LongArray, version: Long = 5, keyId: String = "TEST-eye-a", count: Int = hashes.size,
        issued: String = "2026-10-21T00:00:00Z", expires: String = "2026-11-21T00:00:00Z", extraTail: Int = 0,
    ): ByteArray {
        val header = """{"version":$version,"keyId":"$keyId","issuedAt":"$issued","expiresAt":"$expires","count":$count,"hashBytes":6}"""
            .toByteArray()
        val o = ByteArrayOutputStream()
        o.write("EBL1".toByteArray()); o.write(ByteBuffer.allocate(4).putInt(header.size).array()); o.write(header)
        fun u48(v: Long) = repeat(6) { o.write(((v shr (8 * (5 - it))) and 0xFF).toInt()) }
        for ((i, v) in hashes.withIndex()) {
            if (i == 0) { u48(v); continue }
            val gap = v - hashes[i - 1]
            if (gap < 0xFFFFFFFFL) o.write(ByteBuffer.allocate(4).putInt(gap.toInt()).array())
            else { repeat(4) { o.write(0xFF) }; u48(v) }
        }
        repeat(extraTail) { o.write(0) }
        return o.toByteArray()
    }

    private fun envelope(
        plain: ByteArray, version: Long = 5, keyId: String = "TEST-eye-a", signKey: String = "TEST-eye-a.testkey.pem",
        format: Int = 3, domain: String = BlocklistVerifier.DOMAIN,
    ): ByteArray {
        val payload = gz(plain)
        val s = Ed25519Signer().apply {
            init(true, priv(signKey))
            val m = SignedEnvelope.message(domain, keyId, version, payload); update(m, 0, m.size)
        }
        return ("""{"format":$format,"alg":"Ed25519","keyId":"$keyId","version":$version,"createdAt":"2026-10-21T10:00:00Z",""" +
            """"payload":"${b64.encodeToString(payload)}","sig":"${b64.encodeToString(s.generateSignature())}"}""").toByteArray()
    }

    private val sample = longArrayOf(1, 2, 5, 1L shl 40, (1L shl 40) + 3, 1L shl 47, (1L shl 48) - 1) // gap piccoli e con escape
    private fun reason(r: Result<*>) = (r.exceptionOrNull() as? RulePackException)?.reason

    @Test fun validRoundTripWithEscapes() {
        val bl = verifier.verify(envelope(plain(sample)), 0).getOrThrow()
        assertEquals(5, bl.version)
        assertEquals(sample.size, bl.size)
    }

    @Test fun validFromPythonTool() {
        val bl = verifier.verify(res("TEST-blocklist-v7.json"), 6).getOrThrow()
        assertEquals(7, bl.version)
        assertEquals(4, bl.size)
        assertTrue(bl.contains("badsite-eye.com"))
        assertTrue(bl.contains("evil.github.io"))
        assertTrue(bl.contains("xn--80ak6aa92e.com"))
        assertTrue(bl.contains("203.0.113.9"))
        assertFalse(bl.contains("example.com"))
    }

    @Test fun rollbackRejected() {
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(plain(sample)), 5)))
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(plain(sample)), 9)))
    }

    @Test fun tamperedPayloadOrSignatureRejected() {
        val env = envelope(plain(sample)).toString(Charsets.UTF_8)
        val tampered = env.replace("\"payload\":\"H4sI", "\"payload\":\"H4sJ")
        assertFalse(verifier.verify(tampered.toByteArray(), 0).isSuccess)
        val badSig = env.replace(Regex("\"sig\":\"(.)"), "\"sig\":\"A")
        if (badSig != env) assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(badSig.toByteArray(), 0)))
        // firma valida ma di un'altra chiave non pinnata
        assertEquals(RulePackException.Reason.BAD_SIGNATURE,
            reason(verifier.verify(envelope(plain(sample), signKey = "TEST-eye-b.testkey.pem"), 0)))
    }

    @Test fun otherFeedsDoNotVerifyAsBlocklist() {
        // le regole firmate (format 1, dominio EYE-RULES) non sono un elenco, e viceversa
        assertEquals(RulePackException.Reason.UNSUPPORTED, reason(verifier.verify(res("TEST-pack-v1000.json"), 0)))
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(envelope(plain(sample), domain = "EYE-RULES-v1\n"), 0)))
        val rulesVerifier = RulePackVerifier(mapOf("TEST-eye-a" to pubA))
        assertFalse(rulesVerifier.verify(envelope(plain(sample)), 0).isSuccess)
    }

    @Test fun inconsistentContentRejected() {
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST, reason(verifier.verify(envelope(plain(sample, version = 99)), 0)))
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST, reason(verifier.verify(envelope(plain(sample, keyId = "TEST-eye-b")), 0)))
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST, reason(verifier.verify(envelope(plain(sample, count = sample.size + 1)), 0)))
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST, reason(verifier.verify(envelope(plain(sample, extraTail = 3)), 0)))
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST,
            reason(verifier.verify(envelope(plain(sample, issued = "2026-12-01T00:00:00Z", expires = "2026-11-01T00:00:00Z")), 0)))
        assertEquals(RulePackException.Reason.INVALID_BLOCKLIST, reason(verifier.verify(envelope(plain(longArrayOf(5, 5))), 0)))
    }

    @Test fun storeInstallsAtomicallyAndKeepsAntiRollback() {
        val dir = Files.createTempDirectory("bl").toFile()
        try {
            val store = blocklistStore(dir)
            assertNull(store.loadBlocklist(verifier))
            assertTrue(store.installBlocklist(verifier, envelope(plain(sample))).isSuccess)
            assertEquals(5, store.installedVersion())
            assertNotNull(store.loadBlocklist(verifier))
            assertFalse(store.installBlocklist(verifier, envelope(plain(sample))).isSuccess) // stessa versione
            // rovinato su disco: non si usa, nessuna eccezione
            java.io.File(dir, "blocklist.eye.json").writeBytes("{}".toByteArray())
            assertNull(store.loadBlocklist(verifier))
            val holder = BlocklistHolder(store, verifier)
            assertFalse(holder.isKnownMalicious("a.com", "a.com"))
        } finally { dir.deleteRecursively() }
    }

    private fun assertNull(x: Any?) = assertEquals(null, x)

    // ---- lookup

    private val bl = HashBlocklist.of(listOf("evil.com", "phish.example.org", "tenant.github.io", "203.0.113.9"))

    @Test fun lookupExactSubdomainAndParents() {
        assertTrue(bl.isKnownMalicious("evil.com", "evil.com"))
        assertTrue(bl.isKnownMalicious("login.secure.evil.com", "evil.com")) // dominio padre in lista
        assertTrue(bl.isKnownMalicious("phish.example.org", "example.org"))
        assertTrue(bl.isKnownMalicious("a.phish.example.org", "example.org"))
        assertTrue(bl.isKnownMalicious("203.0.113.9", "203.0.113.9"))
        assertFalse(bl.isKnownMalicious("203.0.113.10", "203.0.113.10"))
    }

    @Test fun lookupNoFalseMatches() {
        assertFalse(bl.isKnownMalicious("example.org", "example.org")) // il padre non e' in lista, solo il figlio
        assertFalse(bl.isKnownMalicious("www.example.org", "example.org"))
        assertFalse(bl.isKnownMalicious("notevil.com", "notevil.com"))
        assertFalse(bl.isKnownMalicious("evil.com.attacker.net", "attacker.net"))
        assertFalse(bl.isKnownMalicious("", ""))
        assertFalse(HashBlocklist.of(emptyList()).isKnownMalicious("evil.com", "evil.com"))
    }

    @Test fun sharedHostingOnlyTheTenantMatches() {
        // github.io e' un suffisso pubblico: il registrabile di tenant.github.io e' il tenant stesso
        val u = UrlInfo.registrableDomain("https://tenant.github.io/x")!!
        assertEquals("tenant.github.io", u)
        assertTrue(bl.isKnownMalicious("tenant.github.io", u))
        assertTrue(bl.isKnownMalicious("x.tenant.github.io", u))
        val other = UrlInfo.registrableDomain("https://altro.github.io/")!!
        assertFalse(bl.isKnownMalicious("altro.github.io", other)) // un altro tenant: mai
        assertFalse(bl.isKnownMalicious("github.io", "github.io"))
    }

    @Test fun staleListStillWorks() {
        val old = HashBlocklist.of(listOf("evil.com"), 1, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")
        assertTrue(old.isStale(java.time.Instant.parse("2026-10-21T00:00:00Z")))
        assertTrue(old.isKnownMalicious("evil.com", "evil.com"))
    }

    // ---- BLK-01 e AlertPolicy

    private val engine = RuleEngine(RuleEngine.loadBundled(), reputation = bl)
    private val plainEngine = RuleEngine(RuleEngine.loadBundled())

    @Test fun blk01GivesProbableAndExplains() {
        val v = engine.analyze(Input("Ciao, guarda qui http://evil.com/foto", Source.PASTE))
        assertEquals(Level.PROBABILE_TRUFFA, v.level)
        assertTrue(v.reasons.any { it.ruleId == "BLK-01" && "evil.com" in it.text && "elenco pubblico" in it.text })
        assertEquals(Level.PROBABILE_TRUFFA, engine.analyze(Input("https://login.evil.com", Source.PASTE)).level)
    }

    @Test fun blk01SilentWithoutListOrOnOtherHosts() {
        assertTrue(plainEngine.analyze(Input("http://evil.com/foto", Source.PASTE)).reasons.none { it.ruleId == "BLK-01" })
        assertTrue(engine.analyze(Input("https://www.example.org/pagina", Source.PASTE)).reasons.none { it.ruleId == "BLK-01" })
        assertTrue(engine.analyze(Input("https://altro.github.io/pagina", Source.PASTE)).reasons.none { it.ruleId == "BLK-01" })
    }

    @Test fun blk01NeverOnOfficialOrPopularDomainsEvenIfListed() {
        val poisoned = HashBlocklist.of(listOf("youtube.com", "poste.it", "www.google.com", "ilpost.it"))
        val e = RuleEngine(RuleEngine.loadBundled(), reputation = poisoned)
        for (u in listOf("https://youtube.com/watch", "https://www.poste.it/", "https://www.google.com/", "https://ilpost.it/x")) {
            assertTrue(e.analyze(Input(u, Source.PASTE)).reasons.none { it.ruleId == "BLK-01" }, u)
        }
    }

    @Test fun blk01IsHardAndAlertsInBackground() {
        assertTrue("BLK-01" in RuleEngine.loadBundled().ai.hardRuleIds)
        assertTrue("BLK-01" in AlertPolicy.STRONG_RULE_IDS)
        val v = engine.analyze(Input("Notifica: http://evil.com/x", Source.SHARE, origin = "com.example.chat"))
        assertEquals(Level.PROBABILE_TRUFFA, v.level)
        assertTrue(AlertPolicy.shouldAlert(v))
        // anche a livello SOSPETTO sarebbe un segnale forte
        assertTrue(AlertPolicy.shouldAlert(Verdict(Level.SOSPETTO, 30, listOf(Reason("BLK-01", "", 30)), emptyList(), Extracted(), null, 10, setOf(ActionVector.LINK), AlertTier.ALERT)))
    }
}
