package app.eye.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

@Serializable
internal data class Envelope(
    val format: Int,
    val alg: String,
    val keyId: String,
    val version: Long,
    val createdAt: String,
    val payload: String,
    val sig: String,
)

/**
 * Passi 1-5 di THREAT_MODEL §5, condivisi da regole e campagne. Cio' che li distingue e' solo
 * [format] e il prefisso di dominio di firma: un envelope di un tipo non verifica mai come l'altro.
 */
internal object SignedEnvelope {
    private val strictJson = Json { ignoreUnknownKeys = false }

    /** Messaggio firmato: domain || keyId || "\n" || version || "\n" || payload (compresso). */
    fun message(domain: String, keyId: String, version: Long, payload: ByteArray): ByteArray =
        "$domain$keyId\n$version\n".toByteArray(Charsets.UTF_8) + payload

    private fun fail(r: RulePackException.Reason, m: String): Nothing = throw RulePackException(r, m)

    /** Restituisce envelope e payload decompresso, ancora NON parsato. */
    fun open(
        bytes: ByteArray, installedVersion: Long, keys: Map<String, ByteArray>,
        format: Int, domain: String, maxEnvelope: Int, maxDecompressed: Int,
    ): Pair<Envelope, ByteArray> {
        // 1. dimensione e parse del solo envelope (schema fisso, campi sconosciuti rifiutati)
        if (bytes.size > maxEnvelope) fail(RulePackException.Reason.TOO_LARGE, "envelope troppo grande")
        val env = try {
            strictJson.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            fail(RulePackException.Reason.MALFORMED, "envelope malformato")
        }
        // 2. formato, algoritmo, chiave pinnata
        if (env.format != format || env.alg != "Ed25519") fail(RulePackException.Reason.UNSUPPORTED, "formato/algoritmo non supportato")
        val pub = keys[env.keyId] ?: fail(RulePackException.Reason.UNKNOWN_KEY, "keyId sconosciuto")
        // 3. anti-rollback
        if (env.version <= installedVersion) fail(RulePackException.Reason.ROLLBACK, "versione non superiore a quella installata")
        // 4. firma (base64 non valido o lunghezza errata = rifiuto)
        val payload: ByteArray
        val sig: ByteArray
        try {
            payload = Base64.getDecoder().decode(env.payload)
            sig = Base64.getDecoder().decode(env.sig)
        } catch (e: IllegalArgumentException) {
            fail(RulePackException.Reason.MALFORMED, "base64 non valido")
        }
        if (sig.size != 64) fail(RulePackException.Reason.BAD_SIGNATURE, "firma non valida")
        val signer = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(pub, 0))
            val msg = message(domain, env.keyId, env.version, payload)
            update(msg, 0, msg.size)
        }
        if (!signer.verifySignature(sig)) fail(RulePackException.Reason.BAD_SIGNATURE, "firma non valida")
        // 5. decompressione con limite
        return env to gunzipLimited(payload, maxDecompressed)
    }

    private fun gunzipLimited(data: ByteArray, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    if (out.size() + n > max) fail(RulePackException.Reason.ZIP_BOMB, "payload decompresso troppo grande")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: java.io.IOException) {
            fail(RulePackException.Reason.MALFORMED, "gzip non valido")
        }
        return out.toByteArray()
    }
}
