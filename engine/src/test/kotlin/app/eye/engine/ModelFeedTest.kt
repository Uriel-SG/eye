package app.eye.engine

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feed «modello AI» (EYE-MODEL-v1): firma, anti-rollback (anche vs modello incluso), manomissione, campi, autotest. Chiavi: SOLO TEST. */
class ModelFeedTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/rulepack/$n")!!.use { it.readBytes() }
    private fun priv(n: String): Ed25519PrivateKeyParameters {
        val pem = res(n).toString(Charsets.US_ASCII).lines().filter { !it.startsWith("-----") }.joinToString("")
        return Ed25519PrivateKeyParameters(Base64.getDecoder().decode(pem).takeLast(32).toByteArray(), 0)
    }
    private val pubA = "QbS3C1wZQ1NxuL5IXZ+1UgMQWxulCMMGjTrZsOyocG0="
    private val verifier = ModelFeedVerifier(mapOf("TEST-eye-a" to pubA))
    private val b64 = Base64.getEncoder()
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private val sha = "a".repeat(64)

    private fun manifest(
        version: Long = 5, keyId: String = "TEST-eye-a", modelSha: String = sha, size: Long = 35_000_000, url: String = "model-5.onnx",
        threshold: Double = 0.9, benign: String = """[{"text":"a","pMax":0.2},{"text":"b","pMax":0.2},{"text":"c","pMax":0.2}]""",
        scam: String = """[{"text":"x","pMin":0.8},{"text":"y","pMin":0.8}]""", extra: String = "",
    ) = ("""{"version":$version,"keyId":"$keyId","issuedAt":"2026-10-24T00:00:00Z","modelSha256":"$modelSha","modelSize":$size,"modelUrl":"$url",""" +
        """"tokenizerSha256":"$sha","tokenizerSize":1000,"raiseThreshold":$threshold,"benign":$benign,"scam":$scam$extra}""").toByteArray()

    private fun envelope(
        plain: ByteArray, version: Long = 5, keyId: String = "TEST-eye-a", format: Int = ModelFeedVerifier.FORMAT, domain: String = ModelFeedVerifier.DOMAIN,
    ): ByteArray {
        val payload = gz(plain)
        val s = Ed25519Signer().apply {
            init(true, priv("TEST-eye-a.testkey.pem"))
            val m = SignedEnvelope.message(domain, keyId, version, payload); update(m, 0, m.size)
        }
        return ("""{"format":$format,"alg":"Ed25519","keyId":"$keyId","version":$version,"createdAt":"2026-10-24T10:00:00Z",""" +
            """"payload":"${b64.encodeToString(payload)}","sig":"${b64.encodeToString(s.generateSignature())}"}""").toByteArray()
    }

    private fun reason(r: Result<*>) = (r.exceptionOrNull() as? RulePackException)?.reason

    @Test fun validManifest() {
        val m = verifier.verify(envelope(manifest()), 0).getOrThrow()
        assertEquals(5, m.version)
        assertEquals(0.9, m.raiseThreshold)
        assertEquals("model-v5-${"a".repeat(16)}.onnx", m.fileName())
        assertEquals(3, m.autotest().benign.size)
        assertEquals(2, m.autotest().scam.size)
    }

    @Test fun validFromPythonTool() {
        val m = verifier.verify(res("TEST-model-v7.json"), 6).getOrThrow()
        assertEquals(7, m.version)
        assertEquals("model-7.onnx", m.modelUrl)
        assertEquals(64, m.modelSha256.length)
        assertEquals(0.9, m.raiseThreshold)
        // il tokenizer del manifest e' quello incluso nell'app
        val tok = java.io.File("../app/src/main/assets/ml/tokenizer.json")
        if (tok.isFile) assertEquals(FeedIndex.sha256Hex(tok.readBytes()), m.tokenizerSha256)
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(res("TEST-model-v7.json"), 7)))
    }

    @Test fun antiRollbackIncludingBundledModel() {
        val e = envelope(manifest(version = 5))
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(e, 5)))
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(e, 9)))
        // versione <= modello dell'APK: rifiutata anche con nulla di installato
        val old = envelope(manifest(version = BUNDLED_MODEL_VERSION, url = "model-3.onnx"), version = BUNDLED_MODEL_VERSION)
        assertEquals(RulePackException.Reason.ROLLBACK, reason(verifier.verify(old, 0)))
    }

    @Test fun signatureTamperingAndDomains() {
        val good = envelope(manifest()).toString(Charsets.UTF_8)
        // manifest manomesso (altro payload, stessa firma)
        val other = envelope(manifest(modelSha = "b".repeat(64))).toString(Charsets.UTF_8)
        val payloadOf = { s: String -> Regex(""""payload":"([^"]+)"""").find(s)!!.groupValues[1] }
        val forged = good.replace(payloadOf(good), payloadOf(other)).toByteArray()
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(forged, 0)))
        // dominio di un altro feed e format sbagliato
        assertEquals(RulePackException.Reason.BAD_SIGNATURE, reason(verifier.verify(envelope(manifest(), domain = "EYE-BLOCKLIST-v1\n"), 0)))
        assertEquals(RulePackException.Reason.UNSUPPORTED, reason(verifier.verify(envelope(manifest(), format = 3), 0)))
        // chiave non pinnata
        assertEquals(RulePackException.Reason.UNKNOWN_KEY, reason(ModelFeedVerifier(mapOf("altra" to pubA)).verify(envelope(manifest()), 0)))
        // il feed regole/campagne/elenchi non verifica come modello (e viceversa)
        assertFalse(BlocklistVerifier(mapOf("TEST-eye-a" to pubA)).verify(envelope(manifest()), 0).isSuccess)
    }

    @Test fun invalidFieldsAreRejected() {
        fun bad(m: ByteArray) = assertEquals(RulePackException.Reason.INVALID_MODEL, reason(verifier.verify(envelope(m), 0)))
        bad(manifest(modelSha = "xyz"))
        bad(manifest(size = 0))
        bad(manifest(size = 500_000_000))
        bad(manifest(url = "../evil.onnx"))
        bad(manifest(url = "model.exe"))
        bad(manifest(threshold = 0.2))
        bad(manifest(threshold = 1.0))
        bad(manifest(benign = """[{"text":"a","pMax":0.2}]"""))
        bad(manifest(benign = """[{"text":"a","pMax":0.9},{"text":"b","pMax":0.2},{"text":"c","pMax":0.2}]"""))
        bad(manifest(scam = """[{"text":"x","pMin":0.1},{"text":"y","pMin":0.8}]"""))
        bad(manifest(keyId = "TEST-eye-a").let { String(it).replace("\"version\":5", "\"version\":6").toByteArray() }) // version != envelope
        bad(manifest(extra = ""","unknown":1""")) // campi sconosciuti
    }

    @Test fun autotestNeedsEveryProbeToPass() {
        val t = ModelAutotest(listOf("ok" to 0.2f, "hi" to 0.2f), listOf("scam" to 0.8f))
        val good = mapOf("ok" to 0.01f, "hi" to 0.1f, "scam" to 0.99f)
        assertTrue(t.passes { good[it] })
        assertFalse(t.passes { if (it == "ok") 0.5f else good[it] }) // legittimo troppo alto
        assertFalse(t.passes { if (it == "scam") 0.5f else good[it] }) // truffa troppo bassa
        assertFalse(t.passes { if (it == "hi") null else good[it] }) // non disponibile
        assertFalse(t.passes { if (it == "hi") Float.NaN else good[it] })
    }

    @Test fun storeKeepsRollbackAndReloads() {
        val dir = Files.createTempDirectory("model").toFile()
        val store = modelStore(dir)
        assertNull(store.loadModel(verifier))
        store.installModel(verifier, envelope(manifest(version = 5))).getOrThrow()
        assertEquals(5, store.installedVersion())
        assertEquals(5, store.loadModel(verifier)!!.version)
        assertEquals(RulePackException.Reason.ROLLBACK, reason(store.installModel(verifier, envelope(manifest(version = 4, url = "model-4.onnx"), version = 4))))
        assertEquals(5, store.installedVersion())
        assertNotNull(store.installModel(verifier, envelope(manifest(version = 6, url = "model-6.onnx"), version = 6)).getOrNull())
    }
}
