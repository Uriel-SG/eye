package app.eye.android.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.eye.android.core.feed.ModelPaths
import app.eye.engine.MiniLmTokenizer
import app.eye.engine.ModelAutotest
import app.eye.engine.TextClassifier
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * Classificatore truffa/legittimo sul telefono: MiniLM multilingue int8 (ONNX Runtime) in assets/ml.
 * Caricamento LAZY e una sola volta (sessione singleton, 2 thread intra-op); [warmUp] lo anticipa su un thread di
 * background. Se il modello non si carica (asset mancante, memoria, ABI) restituisce sempre null: restano le regole.
 * Nessun contenuto nei log: solo i tempi.
 */
class OnnxTextClassifier(context: Context) : TextClassifier {
    private val appContext = context.applicationContext

    private class Loaded(val env: OrtEnvironment, val session: OrtSession, val tokenizer: MiniLmTokenizer)

    private val loadedLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val t0 = System.nanoTime()
        runCatching { load() }
            .onFailure { Log.w(TAG, "modello non disponibile: ${it.javaClass.simpleName}") }
            .onSuccess { Log.i(TAG, "modello caricato in ${(System.nanoTime() - t0) / 1_000_000} ms") }
            .getOrNull()
    }
    private val loaded: Loaded? get() = loadedLazy.value

    /** Libera la sessione (dopo un aggiornamento del modello l'istanza viene sostituita). */
    fun close() {
        if (loadedLazy.isInitialized()) runCatching { loadedLazy.value?.session?.close() }
    }

    /**
     * Prova un file di modello candidato (apertura + autotest con i testi del manifest firmato) senza attivarlo.
     * Gira nel processo ":ai": un crash nativo uccide solo quel processo e vale come «non superato».
     */
    fun checkCandidate(model: File, autotest: ModelAutotest): Boolean = try {
        val m = openVerified(model, loadTokenizer(), autotest, "candidato")
        m.session.close()
        true
    } catch (e: Throwable) {
        Log.w(TAG, "candidato scartato: ${e.javaClass.simpleName}")
        false
    }

    /** Avvia il caricamento su un thread di background (non blocca nessuno). */
    fun warmUp() {
        Thread({ loaded }, "eye-ai-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    override fun scamProbability(text: String): Float? {
        val m = loaded ?: return null
        return try {
            val t0 = System.nanoTime()
            val ids = m.tokenizer.encode(text)
            val t1 = System.nanoTime()
            val p = infer(m, ids)
            Log.i(TAG, "inferenza: tokenizzazione ${(t1 - t0) / 1_000} us, modello ${(System.nanoTime() - t1) / 1_000_000} ms, ${ids.size} token")
            p
        } catch (e: Throwable) {
            Log.w(TAG, "inferenza fallita: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun infer(m: Loaded, ids: IntArray): Float {
        val shape = longArrayOf(1, ids.size.toLong())
        val idsL = LongArray(ids.size) { ids[it].toLong() }
        val mask = LongArray(ids.size) { 1L }
        OnnxTensor.createTensor(m.env, LongBuffer.wrap(idsL), shape).use { a ->
            OnnxTensor.createTensor(m.env, LongBuffer.wrap(mask), shape).use { b ->
                m.session.run(mapOf("input_ids" to a, "attention_mask" to b)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val l = (out[0].value as Array<FloatArray>)[0]
                    val mx = maxOf(l[0], l[1])
                    val e0 = exp((l[0] - mx).toDouble())
                    val e1 = exp((l[1] - mx).toDouble())
                    return (e1 / (e0 + e1)).toFloat()
                }
            }
        }
    }

    private fun loadTokenizer(): MiniLmTokenizer =
        appContext.assets.open("ml/tokenizer.json").use { MiniLmTokenizer(it.readBytes().toString(Charsets.UTF_8)) }

    /**
     * Modello scaricato dal feed firmato (se presente, integro, col tokenizer atteso e SE supera l'autotest sul questo
     * telefono), altrimenti quello dell'APK. Il modello del feed non sostituisce mai quello incluso: se non va, si ripiega.
     */
    private fun load(): Loaded {
        val tokenizer = loadTokenizer()
        val installed = ModelPaths.installed(appContext)
        if (installed != null) {
            runCatching { openVerified(installed.file, tokenizer, installed.manifest.autotest(), "feed v${installed.manifest.version}") }
                .onSuccess { return it }
                .onFailure { Log.w(TAG, "modello del feed scartato (${it.javaClass.simpleName}): resta quello dell'app") }
        }
        return openVerified(modelFile(), tokenizer, BUNDLED_AUTOTEST, "incluso")
    }

    private fun openVerified(model: File, tokenizer: MiniLmTokenizer, autotest: ModelAutotest, source: String): Loaded {
        val env = OrtEnvironment.getEnvironment()
        // Autotest all'avvio: i kernel int8 di ONNX Runtime dipendono dalla CPU (x86/arm64, dotprod/i8mm...). Se su QUESTO
        // telefono il modello non riproduce i risultati attesi sul PC si riprova senza ottimizzazioni di grafo; se ancora
        // sbaglia, il modello resta spento (solo regole) invece di alzare avvisi a caso.
        for (level in listOf(OrtSession.SessionOptions.OptLevel.ALL_OPT, OrtSession.SessionOptions.OptLevel.NO_OPT)) {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(level)
            }
            val session = env.createSession(model.absolutePath, opts)
            val candidate = Loaded(env, session, tokenizer)
            if (selfCheck(candidate, autotest, "$source/${level.name}")) return candidate
            session.close()
        }
        error("autotest del modello fallito")
    }

    private fun selfCheck(m: Loaded, autotest: ModelAutotest, label: String): Boolean = try {
        val benign = autotest.benign.map { infer(m, m.tokenizer.encode(it.first)) }
        val scam = autotest.scam.map { infer(m, m.tokenizer.encode(it.first)) }
        val seen = HashMap<String, Float>()
        autotest.benign.zip(benign).forEach { seen[it.first.first] = it.second }
        autotest.scam.zip(scam).forEach { seen[it.first.first] = it.second }
        val ok = autotest.passes { seen[it] }
        Log.i(TAG, "autotest $label: ${if (ok) "ok" else "FALLITO"} legittimi=${benign.joinToString { "%.3f".format(it) }} truffe=${scam.joinToString { "%.3f".format(it) }}")
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "autotest $label: errore ${e.javaClass.simpleName}")
        false
    }

    /**
     * ONNX Runtime legge da un percorso (mappatura in memoria): l'asset non compresso si copia nella cache una volta per
     * installazione/aggiornamento. La chiave NON puo' essere la sola dimensione (due versioni del modello possono avere
     * lo stesso numero di byte): include l'istante dell'ultimo aggiornamento dell'app. I file vecchi vengono cancellati.
     */
    private fun modelFile(): File {
        val dir = File(appContext.cacheDir, "ml").apply { mkdirs() }
        val size = appContext.assets.openFd("ml/model.int8.onnx").use { it.length }
        val stamp = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val f = File(dir, modelCacheName(size, stamp))
        if (f.exists() && f.length() == size) {
            dir.listFiles()?.forEach { if (it != f) it.delete() }
            return f
        }
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "model.tmp")
        appContext.assets.open("ml/model.int8.onnx").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        check(tmp.length() == size && tmp.renameTo(f)) { "copia modello fallita" }
        Log.i(TAG, "modello copiato in cache: ${f.name}")
        return f
    }

    companion object {
        private const val TAG = "EyeAI"

        /** Nome del file in cache: dipende dalla dimensione E dall'ultimo aggiornamento dell'app (mai dalla sola dimensione). */
        internal fun modelCacheName(size: Long, appUpdateStamp: Long) = "model.int8.$size.$appUpdateStamp.onnx"

        /** Testi con probabilita' note dal PC (JVM): legittimi ~0.002-0.005, truffe ~0.998. Soglie larghe: cattura il modello «rotto», non le sfumature. */
        val SELF_CHECK_BENIGN = listOf("Mario\nok", "Mario\nCi vediamo dopo alle 8", "Giulia\nHai visto la partita ieri sera?")
        val SELF_CHECK_SCAM = listOf(
            "Gentile clienti, e' stato effettuato un accesso anomalo al suo conto, inserire i dati al seguente link https://intesa-clienti-xq7k.com/login",
            "Royal Mail: Your parcel could not be delivered due to an unpaid shipping fee of 1.99 GBP. Please settle this here: https://rm-redelivery-uk-x7.com",
        )

        /** Autotest del modello incluso nell'APK: soglie larghe (vedi sopra). */
        private val BUNDLED_AUTOTEST get() = ModelAutotest(SELF_CHECK_BENIGN.map { it to 0.2f }, SELF_CHECK_SCAM.map { it to 0.8f })
    }
}
