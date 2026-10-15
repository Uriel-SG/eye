package app.eye.engine

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.*

/** Chiavi di test (resources/rulepack): mai di produzione. */
class CampaignFeedTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/rulepack/$n")!!.use { it.readBytes() }
    private val priv: Ed25519PrivateKeyParameters = run {
        val pem = res("TEST-eye-a.testkey.pem").toString(Charsets.US_ASCII).lines().filter { !it.startsWith("-----") }.joinToString("")
        Ed25519PrivateKeyParameters(Base64.getDecoder().decode(pem).takeLast(32).toByteArray(), 0)
    }
    private val pubA = "QbS3C1wZQ1NxuL5IXZ+1UgMQWxulCMMGjTrZsOyocG0="
    private val keys = mapOf("TEST-eye-a" to pubA)
    private val verifier = CampaignFeedVerifier(keys)

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun campaign(id: String = "camp-uno", url: String = "https://www.cert-agid.gov.it/news/x/", status: String = "in_corso", category: String = "smishing") =
        """{"id":"$id","title":"Titolo","category":"$category","summary":"Descrizione","signals":["s1"],"actions":["a1"],
           "example":"es","firstSeen":"2026-09-01","updatedAt":"2026-09-02","source":{"name":"CERT-AGID","url":"$url"},"status":"$status"}"""

    private fun payloadJson(
        version: Long = 3, keyId: String = "TEST-eye-a", campaigns: String = campaign(),
        issued: String = "2026-10-01T00:00:00Z", expires: String = "2026-11-15T00:00:00Z",
    ) =
        """{"version":$version,"keyId":"$keyId","issuedAt":"$issued","expiresAt":"$expires","campaigns":[$campaigns]}"""

    private fun envelope(
        version: Long = 3, format: Int = 2, domain: String = CampaignFeedVerifier.DOMAIN,
        payload: ByteArray = gz(payloadJson(version).toByteArray()), tamper: Boolean = false,
    ): ByteArray {
        val keyId = "TEST-eye-a"
        val s = Ed25519Signer().apply {
            init(true, priv)
            val m = SignedEnvelope.message(domain, keyId, version, payload); update(m, 0, m.size)
        }
        val b64 = Base64.getEncoder()
        val sentPayload = if (tamper) payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else payload
        return ("""{"format":$format,"alg":"Ed25519","keyId":"$keyId","version":$version,"createdAt":"2026-10-11T10:00:00Z",""" +
            """"payload":"${b64.encodeToString(sentPayload)}","sig":"${b64.encodeToString(s.generateSignature())}"}""").toByteArray()
    }

    private fun reason(r: Result<*>) = (r.exceptionOrNull() as? RulePackException)?.reason

    @Test fun validFeed() {
        val f = verifier.verify(envelope(), 0).getOrThrow()
        assertEquals(3, f.version)
        assertEquals("camp-uno", f.campaigns.single().id)
    }

    @Test fun tamperedPayloadRejected() {
        val r = verifier.verify(envelope(tamper = true), 0)
        assertTrue(reason(r) in setOf(RulePackException.Reason.BAD_SIGNATURE, RulePackException.Reason.MALFORMED), "$r")
    }

    @Test fun rollbackRejected() {
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(version = 3), 3)))
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(version = 3), 9)))
        assertTrue(verifier.verify(envelope(version = 4), 3).isSuccess)
    }

    @Test fun rulesAndCampaignsNotInterchangeable() {
        // envelope campagne (format 2) davanti al verifier regole
        val rv = RulePackVerifier(keys)
        assertEquals(RulePackException.Reason.UNSUPPORTED, reason(rv.verify(envelope(), 0)))
        // stesso envelope ma format=1 e firmato col dominio delle regole: il verifier campagne lo rifiuta per formato
        val asRules = envelope(format = 1, domain = "EYE-RULES-v1\n")
        assertEquals(RulePackException.Reason.UNSUPPORTED, reason(verifier.verify(asRules, 0)))
        // format 2 ma dominio regole: firma non valida
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(envelope(domain = "EYE-RULES-v1\n"), 0)))
        // format 1 con dominio campagne: lo rifiuta il verifier regole (format 1 ok, firma su dominio diverso)
        val forged = envelope(format = 1, domain = CampaignFeedVerifier.DOMAIN)
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(rv.verify(forged, 0)))
    }

    private fun invalid(campaigns: String): Result<CampaignFeed> =
        verifier.verify(envelope(payload = gz(payloadJson(campaigns = campaigns).toByteArray())), 0)

    @Test fun invalidFieldsRejected() {
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign(status = "boh"))))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign(category = "x"))))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign(id = "Maiuscolo!"))))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign() + "," + campaign())))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign().replace("2026-09-02", "2026-13-40"))))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign().replace(""""signals":["s1"]""", """"signals":[]"""))))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid("""{"id":"zzz"}""")))
    }

    @Test fun payloadMismatchRejected() {
        val r = verifier.verify(envelope(payload = gz(payloadJson(version = 99).toByteArray())), 0)
        assertEquals(RulePackException.Reason.PAYLOAD_MISMATCH, reason(r))
    }

    @Test fun sourceUrlsRestricted() {
        val ok = listOf(
            "https://www.cert-agid.gov.it/news/a/", "https://cert-agid.gov.it/x", "https://www.commissariatodips.it/a/index.html",
            "https://www.poliziadistato.it/a", "https://www.agenziaentrate.gov.it/portale/x", "https://www.inps.it/it/it/a.html",
        )
        val bad = listOf(
            "http://www.inps.it/a", "https://evilinps.it/a", "https://inps.it.evil.com/a", "https://www.inps.it@evil.com/a",
            "https://user:pw@www.inps.it/a", "https://www.inps.it:8443/a", "https://evilgov.it/a", "https://gov.it.evil.net/",
            "https://example.com", "ftp://www.inps.it/a", "https://www.inps.it/a b", "javascript:alert(1)", "https://xn--inps-9ra.it/", "",
            "https://www.inps.it./a", "https://www.insр.it/a",
        )
        ok.forEach { assertTrue(CampaignFeedVerifier.isOfficialHttpsUrl(it), it) }
        bad.forEach { assertFalse(CampaignFeedVerifier.isOfficialHttpsUrl(it), it) }
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(invalid(campaign(url = "https://evil.example/x"))))
    }

    @Test fun tooLargeAndBombRejected() {
        assertEquals(RulePackException.Reason.TOO_LARGE, reason(verifier.verify(ByteArray(CampaignFeedVerifier.MAX_ENVELOPE + 1), 0)))
        val bomb = gz(ByteArray(CampaignFeedVerifier.MAX_DECOMPRESSED + 1))
        assertEquals(RulePackException.Reason.ZIP_BOMB, reason(verifier.verify(envelope(payload = bomb), 0)))
    }

    @Test fun storeIsIndependentFromRules() {
        val dir = Files.createTempDirectory("camp").toFile()
        val store = campaignStore(dir)
        assertTrue(store.installCampaigns(verifier, envelope(version = 3)).isSuccess)
        assertEquals(3, store.installedVersion())
        assertTrue(store.installCampaigns(verifier, envelope(version = 3)).isFailure)
        assertEquals(3, store.loadCampaigns(verifier)?.version)
        assertEquals(0, FileRulePackStore(dir).installedVersion())
        dir.deleteRecursively()
    }

    private fun idx(s: String) = FeedIndex.parse(s.toByteArray())
    private val sha = "a".repeat(64)

    @Test fun feedIndexParsing() {
        val ok = idx("""{"rules":{"version":2,"sha256":"$sha","url":"rules.eye.json"},"campaigns":{"version":1,"sha256":"$sha","url":"campaigns.eye.json"}}""").getOrThrow()
        assertEquals(2, ok.rules.version)
        assertEquals("campaigns.eye.json", ok.campaigns.url)
        assertTrue(ok.rules.matches(ByteArray(0)).not())
        listOf(
            "", "[]", "{}", """{"rules":{"version":2,"sha256":"$sha","url":"rules.eye.json"}}""",
            """{"rules":{"version":"2","sha256":"$sha","url":"r.json"},"campaigns":{"version":1,"sha256":"$sha","url":"c.json"}}""",
            """{"rules":{"version":0,"sha256":"$sha","url":"r.json"},"campaigns":{"version":1,"sha256":"$sha","url":"c.json"}}""",
            """{"rules":{"version":2,"sha256":"zz","url":"r.json"},"campaigns":{"version":1,"sha256":"$sha","url":"c.json"}}""",
            """{"rules":{"version":2,"sha256":"$sha","url":"https://evil.example/r.json"},"campaigns":{"version":1,"sha256":"$sha","url":"c.json"}}""",
            """{"rules":{"version":2,"sha256":"$sha","url":"../r.json"},"campaigns":{"version":1,"sha256":"$sha","url":"c.json"}}""",
            "x".repeat(FeedIndex.MAX_BYTES + 1),
        ).forEach { assertTrue(idx(it).isFailure, it.take(60)) }
    }

    @Test fun staleIsInformativeNotARejection() {
        val f = verifier.verify(envelope(), 0).getOrThrow()
        assertFalse(f.isStale(java.time.Instant.parse("2026-11-14T23:59:59Z")))
        assertTrue(f.isStale(java.time.Instant.parse("2026-11-15T00:00:01Z")))
        assertEquals(10, f.staleDays(java.time.Instant.parse("2026-10-11T12:00:00Z")))
    }

    @Test fun invalidWindowRejected() {
        fun r(i: String, e: String) = verifier.verify(envelope(payload = gz(payloadJson(issued = i, expires = e).toByteArray())), 0)
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(r("2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z")))
        assertEquals(RulePackException.Reason.INVALID_CAMPAIGNS, reason(r("ieri", "domani")))
    }

    @Test fun rulesStaleAndBundledNeverStale() {
        val bundled = RuleLoader.parse(String(javaClass.getResourceAsStream("/rules/rules.json")!!.readBytes(), Charsets.UTF_8))
        assertFalse(bundled.isStale(java.time.Instant.parse("2030-01-01T00:00:00Z")))
        assertNull(bundled.staleDays(java.time.Instant.now()))
        val signed = bundled.copy(issuedAt = "2026-10-01T00:00:00Z", expiresAt = "2026-11-15T00:00:00Z")
        assertTrue(signed.isStale(java.time.Instant.parse("2026-12-01T00:00:00Z")))
    }
}
