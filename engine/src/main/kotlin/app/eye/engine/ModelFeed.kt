package app.eye.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/** Versione del modello incluso nell'APK (MiniLM v3): un modello del feed deve avere versione SUPERIORE. */
const val BUNDLED_MODEL_VERSION = 3L

@Serializable data class BenignProbe(val text: String, val pMax: Double)
@Serializable data class ScamProbe(val text: String, val pMin: Double)

/**
 * Manifest firmato del feed «modello AI» (EYE-MODEL-v1): descrive il file del modello, scaricato a parte, e come
 * provarlo prima di attivarlo. Il file del modello NON e' nell'envelope: e' verificato con [modelSha256].
 */
@Serializable
data class ModelManifest(
    val version: Long,
    val keyId: String,
    val issuedAt: String,
    val modelSha256: String,
    val modelSize: Long,
    /** Nome del file del modello accanto a version.json (es. model-4.onnx). */
    val modelUrl: String,
    /** sha256 del tokenizer.json atteso: deve essere quello incluso nell'app (il tokenizer non si aggiorna da qui). */
    val tokenizerSha256: String,
    val tokenizerSize: Long,
    /** Soglia consigliata per alzare il verdetto (AI-01) con questo modello. */
    val raiseThreshold: Double,
    val benign: List<BenignProbe>,
    val scam: List<ScamProbe>,
) {
    fun autotest() = ModelAutotest(benign.map { it.text to it.pMax.toFloat() }, scam.map { it.text to it.pMin.toFloat() })

    /** Nome del file in filesDir: include versione e sha256 (mai la sola dimensione). */
    fun fileName() = "model-v$version-${modelSha256.take(16)}.onnx"
}

/** Prova del modello: i testi legittimi devono avere p <= limite, le truffe p >= limite. Punteggio nullo/NaN = fallita. */
class ModelAutotest(val benign: List<Pair<String, Float>>, val scam: List<Pair<String, Float>>) {
    fun passes(score: (String) -> Float?): Boolean {
        val b = benign.map { score(it.first) }
        val s = scam.map { score(it.first) }
        if (b.any { it == null || it.isNaN() } || s.any { it == null || it.isNaN() }) return false
        return b.indices.all { b[it]!! <= benign[it].second } && s.indices.all { s[it]!! >= scam[it].second }
    }
}

/** Verifica l'envelope del feed modello (format 5, dominio proprio): passi 1-5 di [SignedEnvelope], poi il manifest. Anti-rollback indipendente. */
class ModelFeedVerifier(pinnedKeys: Map<String, String> = PinnedKeys.production) {
    private val keys: Map<String, ByteArray> = pinnedKeys.mapValues { Base64.getDecoder().decode(it.value) }
        .also { m -> require(m.values.all { it.size == 32 }) { "chiave pubblica non valida" } }

    fun verify(bytes: ByteArray, installedVersion: Long): Result<ModelManifest> = try {
        Result.success(doVerify(bytes, installedVersion))
    } catch (e: RulePackException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(RulePackException(RulePackException.Reason.MALFORMED, "envelope non valido"))
    }

    private fun bad(m: String): Nothing = throw RulePackException(RulePackException.Reason.INVALID_MODEL, m)

    private fun doVerify(bytes: ByteArray, installedVersion: Long): ModelManifest {
        // anti-rollback anche rispetto al modello incluso nell'APK
        val (env, plain) = SignedEnvelope.open(
            bytes, maxOf(installedVersion, BUNDLED_MODEL_VERSION), keys, format = FORMAT, domain = DOMAIN,
            maxEnvelope = MAX_ENVELOPE, maxDecompressed = MAX_DECOMPRESSED,
        )
        val m = try {
            Json { ignoreUnknownKeys = false }.decodeFromString<ModelManifest>(plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            bad("manifest malformato")
        }
        if (m.version != env.version || m.keyId != env.keyId) bad("version/keyId diversi dall'envelope")
        if (!CampaignFeedVerifier.validWindow(m.issuedAt, "2999-01-01T00:00:00Z")) bad("issuedAt non valido")
        if (!SHA.matches(m.modelSha256) || !SHA.matches(m.tokenizerSha256)) bad("sha256 non valido")
        if (m.modelSize !in 1..MAX_MODEL_BYTES || m.tokenizerSize !in 1..MAX_TOKENIZER_BYTES) bad("dimensioni fuori range")
        if (!FILE.matches(m.modelUrl) || ".." in m.modelUrl) bad("nome file non valido")
        if (!(m.raiseThreshold >= 0.5 && m.raiseThreshold <= 0.9999)) bad("soglia fuori range")
        if (m.benign.size != 3 || m.scam.size != 2) bad("autotest: servono 3 testi legittimi e 2 truffe")
        if (m.benign.any { it.text.isBlank() || it.text.length > MAX_PROBE || !(it.pMax > 0.0 && it.pMax <= 0.5) }) bad("autotest legittimi non valido")
        if (m.scam.any { it.text.isBlank() || it.text.length > MAX_PROBE || !(it.pMin >= 0.5 && it.pMin < 1.0) }) bad("autotest truffe non valido")
        return m
    }

    companion object {
        const val FORMAT = 5
        const val DOMAIN = "EYE-MODEL-v1\n"
        const val MAX_ENVELOPE = 64 * 1024
        const val MAX_DECOMPRESSED = 64 * 1024
        const val MAX_MODEL_BYTES = 120L * 1024 * 1024
        const val MAX_TOKENIZER_BYTES = 16L * 1024 * 1024
        private const val MAX_PROBE = 1000
        private val SHA = Regex("^[0-9a-f]{64}$")
        private val FILE = Regex("^[a-z0-9][a-z0-9._-]{0,63}\\.onnx$")
    }
}

fun modelStore(dir: File): RulePackStore = FileRulePackStore(dir, "model.eye.json", "model.version")

/** Verifica [envelope] e, se valido, lo salva con swap atomico (da chiamare SOLO a modello scaricato, verificato e provato). */
fun RulePackStore.installModel(verifier: ModelFeedVerifier, envelope: ByteArray): Result<ModelManifest> =
    verifier.verify(envelope, installedVersion()).onSuccess { save(envelope, it.version) }

/** Manifest salvato, riverificato (firma e coerenza); null se assente o non valido. */
fun RulePackStore.loadModel(verifier: ModelFeedVerifier): ModelManifest? {
    val bytes = readPack() ?: return null
    return verifier.verify(bytes, maxOf(installedVersion() - 1, 0)).getOrNull()
}
