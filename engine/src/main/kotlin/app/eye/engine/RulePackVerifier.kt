package app.eye.engine

import kotlinx.serialization.Serializable
import java.util.Base64

/** Chiavi pubbliche pinnate: keyId -> chiave raw Ed25519 (32 byte, base64). THREAT_MODEL §6. */
object PinnedKeys {
    /**
     * Chiavi pubbliche di produzione (generate 2026-10-11, private custodite fuori dal repo).
     * `a` = attiva, `b` = successiva per la rotazione: firmare con `b` solo se `a` è compromessa o persa.
     */
    val production: Map<String, String> = mapOf(
        "eye-2026-a" to "1VIKlSW87W1DjYxPDjGOV3ovAo0/y3Y1W60KcTbS8X0=",
        "eye-2026-b" to "8AXfhr8hmDpZKrXV2mR4XUBg+IKz8JnOQahe+EqoiWM=",
    )
}

class RulePackException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { TOO_LARGE, MALFORMED, UNSUPPORTED, UNKNOWN_KEY, ROLLBACK, BAD_SIGNATURE, ZIP_BOMB, PAYLOAD_MISMATCH, INVALID_RULES, INVALID_CAMPAIGNS }
}

/**
 * Verifica un envelope firmato secondo THREAT_MODEL §5 (passi 1-7), nell'ordine: nessun parse del
 * payload prima di firma valida. Il passo 8 (swap atomico) e' di [RulePackStore].
 */
class RulePackVerifier(pinnedKeys: Map<String, String> = PinnedKeys.production) {
    private val keys: Map<String, ByteArray> = pinnedKeys.mapValues { Base64.getDecoder().decode(it.value) }
        .also { m -> require(m.values.all { it.size == 32 }) { "chiave pubblica non valida" } }

    /** [installedVersion] = massima versione gia' persistita (0 se nessuna); si accetta solo version > installedVersion. */
    fun verify(bytes: ByteArray, installedVersion: Long): Result<RuleSet> = try {
        Result.success(doVerify(bytes, installedVersion))
    } catch (e: RulePackException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(RulePackException(RulePackException.Reason.MALFORMED, "envelope non valido"))
    }

    private fun doVerify(bytes: ByteArray, installedVersion: Long): RuleSet {
        // passi 1-5 (envelope, chiave, anti-rollback, firma, gunzip limitato): codice condiviso
        val (env, plain) = SignedEnvelope.open(
            bytes, installedVersion, keys, format = 1, domain = DOMAIN, maxEnvelope = MAX_ENVELOPE, maxDecompressed = MAX_DECOMPRESSED,
        )
        // 6-7. parse + validazione semantica (RuleLoader), version/keyId coerenti con l'envelope
        val rs = try {
            RuleLoader.parse(plain.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw RulePackException(RulePackException.Reason.INVALID_RULES, e.message ?: "regole non valide")
        }
        if (rs.version != env.version || rs.keyId != env.keyId) {
            throw RulePackException(RulePackException.Reason.PAYLOAD_MISMATCH, "version/keyId del payload diversi dall'envelope")
        }
        if ((rs.issuedAt != null || rs.expiresAt != null) && !CampaignFeedVerifier.validWindow(rs.issuedAt, rs.expiresAt)) {
            throw RulePackException(RulePackException.Reason.INVALID_RULES, "issuedAt/expiresAt non validi")
        }
        return rs
    }

    companion object {
        const val MAX_ENVELOPE = 2 * 1024 * 1024
        const val MAX_DECOMPRESSED = 10 * 1024 * 1024
        private const val DOMAIN = "EYE-RULES-v1\n"

        /** Messaggio firmato: DOMAIN || keyId || "\n" || version || "\n" || payload (compresso). */
        fun signedMessage(keyId: String, version: Long, payload: ByteArray): ByteArray =
            SignedEnvelope.message(DOMAIN, keyId, version, payload)
    }
}
