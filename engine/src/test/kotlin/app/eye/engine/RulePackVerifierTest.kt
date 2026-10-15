package app.eye.engine

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.*

/** Le chiavi in resources/rulepack sono SOLO TEST. */
class RulePackVerifierTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/rulepack/$n")!!.use { it.readBytes() }
    private fun priv(n: String): Ed25519PrivateKeyParameters {
        val pem = res(n).toString(Charsets.US_ASCII).lines().filter { !it.startsWith("-----") }.joinToString("")
        return Ed25519PrivateKeyParameters(Base64.getDecoder().decode(pem).takeLast(32).toByteArray(), 0)
    }
    private val pubA = "QbS3C1wZQ1NxuL5IXZ+1UgMQWxulCMMGjTrZsOyocG0="
    private val pubB = "3NSDFaJpgt7pjTNHeYLSCQqcZ3lFcvNy0dDeaqpZS2E="
    private val verifier = RulePackVerifier(mapOf("TEST-eye-a" to pubA, "TEST-eye-b" to pubB))
    private val rules = String(javaClass.getResourceAsStream("/rules/rules.json")!!.readBytes(), Charsets.UTF_8)

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun rulesJson(version: Long, keyId: String?): String {
        var r = rules.replaceFirst(Regex("\"version\"\\s*:\\s*\\d+"), "\"version\": $version")
        if (keyId != null) r = r.replaceFirst("{", "{\"keyId\":\"$keyId\",")
        return r
    }

    private fun envelope(
        version: Long = 5, keyId: String = "TEST-eye-a", signKey: String = "TEST-eye-a.testkey.pem",
        payload: ByteArray = gz(rulesJson(version, keyId).toByteArray()), extra: String = "",
    ): ByteArray {
        val s = Ed25519Signer().apply {
            init(true, priv(signKey))
            val m = RulePackVerifier.signedMessage(keyId, version, payload); update(m, 0, m.size)
        }
        val b64 = Base64.getEncoder()
        return ("""{"format":1,"alg":"Ed25519","keyId":"$keyId","version":$version,"createdAt":"2026-10-11T10:00:00Z",""" +
            """"payload":"${b64.encodeToString(payload)}","sig":"${b64.encodeToString(s.generateSignature())}"$extra}""").toByteArray()
    }

    private fun reason(r: Result<RuleSet>) = (r.exceptionOrNull() as? RulePackException)?.reason

    @Test fun validSignature() {
        val rs = verifier.verify(envelope(), 0).getOrThrow()
        assertEquals(5, rs.version)
        assertNotNull(RuleEngine(rs).analyze(Input("ciao", Source.PASTE)))
    }

    @Test fun validFromPythonTool() {
        assertEquals(1000, verifier.verify(res("TEST-pack-v1000.json"), 999).getOrThrow().version)
    }

    @Test fun secondKeyAccepted() {
        assertTrue(verifier.verify(envelope(keyId = "TEST-eye-b", signKey = "TEST-eye-b.testkey.pem"), 0).isSuccess)
    }

    @Test fun wrongSignerRejected() {
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(envelope(signKey = "TEST-eye-b.testkey.pem"), 0)))
    }

    @Test fun alteredSignature() {
        val s = envelope().toString(Charsets.UTF_8)
        val i = s.indexOf("\"sig\":\"") + 7
        val flipped = s.substring(0, i) + (if (s[i] == 'A') 'B' else 'A') + s.substring(i + 1)
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(flipped.toByteArray(), 0)))
    }

    @Test fun alteredPayloadRejectedBeforeParse() {
        val payload = gz(rulesJson(5, "TEST-eye-a").toByteArray())
        val good = envelope(payload = payload).toString(Charsets.UTF_8)
        val tampered = payload.clone().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val bad = good.replace(Base64.getEncoder().encodeToString(payload), Base64.getEncoder().encodeToString(tampered))
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(bad.toByteArray(), 0)))
    }

    @Test fun alteredVersionOrKeyIdCoveredBySignature() {
        val s = envelope(version = 5).toString(Charsets.UTF_8).replace("\"version\":5", "\"version\":6")
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(s.toByteArray(), 0)))
        val k = envelope().toString(Charsets.UTF_8).replace("\"keyId\":\"TEST-eye-a\"", "\"keyId\":\"TEST-eye-b\"")
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(k.toByteArray(), 0)))
    }

    @Test fun unknownKeyId() {
        assertEquals(RulePackException.Reason.UNKNOWN_KEY, reason(verifier.verify(envelope(keyId = "sconosciuta"), 0)))
    }

    @Test fun productionHasNoPinnedKeysYet() {
        assertEquals(RulePackException.Reason.UNKNOWN_KEY, reason(RulePackVerifier().verify(envelope(), 0)))
    }

    @Test fun rollback() {
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(version = 5), 5)))
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(envelope(version = 5), 9)))
        assertTrue(verifier.verify(envelope(version = 5), 4).isSuccess)
    }

    @Test fun zipBomb() {
        val bomb = gz(ByteArray(RulePackVerifier.MAX_DECOMPRESSED + 1))
        assertTrue(bomb.size < 100_000)
        assertEquals(RulePackException.Reason.ZIP_BOMB, reason(verifier.verify(envelope(payload = bomb), 0)))
    }

    @Test fun oversizedEnvelope() {
        assertEquals(RulePackException.Reason.TOO_LARGE, reason(verifier.verify(ByteArray(RulePackVerifier.MAX_ENVELOPE + 1), 0)))
    }

    @Test fun malformedEnvelopes() {
        for (s in listOf("", "non json", "{}", "[]", """{"format":1}""")) {
            assertEquals(RulePackException.Reason.MALFORMED, reason(verifier.verify(s.toByteArray(), 0)), s)
        }
        assertEquals(RulePackException.Reason.MALFORMED, reason(verifier.verify(envelope(extra = ""","x":1"""), 0)))
        val b = envelope().toString(Charsets.UTF_8)
        val badB64 = b.replace(Regex("\"payload\":\"[^\"]*\""), "\"payload\":\"!!!\"")
        assertEquals(RulePackException.Reason.MALFORMED, reason(verifier.verify(badB64.toByteArray(), 0)))
        assertEquals(RulePackException.Reason.MALFORMED, reason(verifier.verify(envelope(payload = "not gzip".toByteArray()), 0)))
        val f = b.replace("\"format\":1", "\"format\":2")
        assertEquals(RulePackException.Reason.UNSUPPORTED, reason(verifier.verify(f.toByteArray(), 0)))
    }

    @Test fun payloadMustRepeatVersionAndKeyId() {
        val wrongVer = envelope(version = 5, payload = gz(rulesJson(4, "TEST-eye-a").toByteArray()))
        assertEquals(RulePackException.Reason.PAYLOAD_MISMATCH, reason(verifier.verify(wrongVer, 0)))
        val noKey = envelope(payload = gz(rulesJson(5, null).toByteArray()))
        assertEquals(RulePackException.Reason.PAYLOAD_MISMATCH, reason(verifier.verify(noKey, 0)))
    }

    @Test fun validSignatureButInvalidRules() {
        val e = envelope(payload = gz("""{"version":5,"keyId":"TEST-eye-a"}""".toByteArray()))
        assertEquals(RulePackException.Reason.INVALID_RULES, reason(verifier.verify(e, 0)))
    }

    @Test fun storeAtomicInstallAndRollback() {
        val dir = Files.createTempDirectory("rulepack").toFile()
        try {
            val store = FileRulePackStore(dir)
            assertEquals(0, store.installedVersion())
            assertNull(store.loadVerified(verifier))
            assertEquals(5, store.install(verifier, envelope(version = 5)).getOrThrow().version)
            assertEquals(5, store.installedVersion())
            assertEquals(5, store.loadVerified(verifier)?.version)
            assertTrue(store.install(verifier, envelope(version = 5)).isFailure)
            assertTrue(store.install(verifier, envelope(version = 3)).isFailure)
            assertEquals(5, store.loadVerified(verifier)?.version)
            assertEquals(7, store.install(verifier, envelope(version = 7)).getOrThrow().version)
            assertEquals(listOf("rules.eye.json", "rules.version"), dir.list()!!.sorted())
        } finally { dir.deleteRecursively() }
    }
}
