package app.eye.android.core.feed

import app.eye.engine.BUNDLED_MODEL_VERSION
import app.eye.engine.FeedIndex
import app.eye.engine.ModelAutotest
import app.eye.engine.ModelFeedVerifier
import app.eye.engine.ModelManifest
import app.eye.engine.RulePackStore
import app.eye.engine.installModel
import app.eye.engine.loadModel
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Prova il file del modello in un processo isolato (":ai"): true solo se supera l'autotest del manifest. */
fun interface ModelChecker {
    fun check(model: File, autotest: ModelAutotest): Boolean
}

/** Modello AI scaricato e attivabile: file verificato + manifest firmato coerente. */
class InstalledModel(val file: File, val manifest: ModelManifest) {
    companion object {
        fun sha256Hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Modello del feed da usare, o null (= modello dell'APK). Riverifica firma del manifest, tokenizer atteso,
         * dimensione e sha256 del file: qualunque incoerenza = null. Mai eccezioni.
         */
        fun resolve(store: RulePackStore, modelDir: File, bundledTokenizerSha: String, verifier: ModelFeedVerifier = ModelFeedVerifier()): InstalledModel? = try {
            val m = store.loadModel(verifier)
            val f = m?.let { File(modelDir, it.fileName()) }
            if (m != null && m.version > BUNDLED_MODEL_VERSION && m.tokenizerSha256 == bundledTokenizerSha &&
                f != null && f.isFile && f.length() == m.modelSize && sha256Hex(f) == m.modelSha256
            ) InstalledModel(f, m) else null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Aggiornamento del modello AI da feed firmato (EYE-MODEL-v1). Bloccante: da chiamare fuori dal main thread e solo
 * con rete non a consumo e in carica (il file pesa decine di MB). Ordine, e ogni passo fallito lascia il modello
 * precedente intatto: indice -> manifest firmato (firma, anti-rollback, tokenizer atteso) -> download su file ->
 * dimensione e sha256 del manifest -> AUTOTEST in processo isolato -> solo allora file definitivo + manifest salvato.
 */
class ModelUpdater(
    private val http: FeedHttp,
    baseUrl: String,
    private val store: RulePackStore,
    private val modelDir: File,
    private val state: FeedState,
    private val checker: ModelChecker,
    private val bundledTokenizerSha: () -> String,
    private val verifier: ModelFeedVerifier = ModelFeedVerifier(),
    private val onActivated: (ModelManifest) -> Unit = {},
) {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    fun update(): FeedResult {
        val index = try {
            when (val r = http.get(base + "version.json", state.etag(MODEL), FeedIndex.MAX_BYTES)) {
                FeedResponse.NotModified -> return FeedResult.UpToDate
                is FeedResponse.Ok -> FeedIndex.parse(r.bytes).getOrNull()?.let { it to r.etag } ?: return FeedResult.Failed(FeedFailure.BAD_INDEX)
            }
        } catch (e: IOException) {
            return FeedResult.Failed(FeedFailure.NETWORK)
        }
        val entry = index.first.model
        val floor = maxOf(store.installedVersion(), BUNDLED_MODEL_VERSION, state.rejectedModel())
        if (entry == null || entry.version <= floor) {
            state.setEtag(MODEL, index.second)
            return FeedResult.UpToDate
        }
        // 1. manifest firmato
        val envelope = try {
            (http.get(base + entry.url, null, ModelFeedVerifier.MAX_ENVELOPE) as? FeedResponse.Ok)?.bytes?.takeIf { entry.matches(it) }
        } catch (e: IOException) { null } ?: return FeedResult.Failed(FeedFailure.BAD_DOWNLOAD)
        val manifest = verifier.verify(envelope, store.installedVersion()).getOrNull() ?: return FeedResult.Failed(FeedFailure.REJECTED)
        if (manifest.tokenizerSha256 != bundledTokenizerSha()) {
            state.setRejectedModel(manifest.version) // tokenizer diverso da quello incluso: questo modello non e' compatibile
            return FeedResult.Failed(FeedFailure.REJECTED)
        }
        // 2. download e integrita'
        modelDir.mkdirs()
        if (modelDir.usableSpace < manifest.modelSize * 2) return FeedResult.Failed(FeedFailure.BAD_DOWNLOAD)
        val tmp = File(modelDir, "model.part")
        tmp.delete()
        try {
            try {
                http.download(base + manifest.modelUrl, tmp, manifest.modelSize)
            } catch (e: IOException) {
                return FeedResult.Failed(if (e is FeedTooLargeException) FeedFailure.BAD_DOWNLOAD else FeedFailure.NETWORK)
            }
            if (tmp.length() != manifest.modelSize || InstalledModel.sha256Hex(tmp) != manifest.modelSha256) {
                state.setRejectedModel(manifest.version)
                return FeedResult.Failed(FeedFailure.BAD_DOWNLOAD)
            }
            // 3. autotest PRIMA di attivare: se fallisce resta il modello precedente
            val ok = try { checker.check(tmp, manifest.autotest()) } catch (e: Exception) { false }
            if (!ok) {
                state.setRejectedModel(manifest.version)
                return FeedResult.Failed(FeedFailure.REJECTED)
            }
            // 4. attivazione: file definitivo (nome con versione e sha256), poi manifest (che avanza l'anti-rollback)
            val dest = File(modelDir, manifest.fileName())
            if (!tmp.renameTo(dest)) return FeedResult.Failed(FeedFailure.BAD_DOWNLOAD)
            if (store.installModel(verifier, envelope).isFailure) {
                dest.delete()
                return FeedResult.Failed(FeedFailure.REJECTED)
            }
            modelDir.listFiles()?.forEach { if (it != dest && it.name.startsWith("model")) it.delete() }
            state.setEtag(MODEL, index.second)
            runCatching { onActivated(manifest) }
            return FeedResult.Updated(manifest.version)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val MODEL = "model"
    }
}
