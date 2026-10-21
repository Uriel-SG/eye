package app.eye.android.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.eye.engine.MiniLmTokenizer
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

    private val loaded: Loaded? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val t0 = System.nanoTime()
        runCatching { load() }
            .onFailure { Log.w(TAG, "modello non disponibile: ${it.javaClass.simpleName}") }
            .onSuccess { Log.i(TAG, "modello caricato in ${(System.nanoTime() - t0) / 1_000_000} ms") }
            .getOrNull()
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

    private fun load(): Loaded {
        val tokenizer = appContext.assets.open("ml/tokenizer.json").use { MiniLmTokenizer(it.readBytes().toString(Charsets.UTF_8)) }
        val model = modelFile()
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
            if (selfCheck(candidate, level.name)) return candidate
            session.close()
        }
        error("autotest del modello fallito")
    }

    private fun selfCheck(m: Loaded, label: String): Boolean = try {
        val benign = SELF_CHECK_BENIGN.map { infer(m, m.tokenizer.encode(it)) }
        val scam = SELF_CHECK_SCAM.map { infer(m, m.tokenizer.encode(it)) }
        val ok = benign.all { it < 0.2f } && scam.all { it > 0.8f }
        Log.i(TAG, "autotest $label: ${if (ok) "ok" else "FALLITO"} legittimi=${benign.joinToString { "%.3f".format(it) }} truffe=${scam.joinToString { "%.3f".format(it) }}")
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "autotest $label: errore ${e.javaClass.simpleName}")
        false
    }

    /** ONNX Runtime legge da un percorso (mappatura in memoria): l'asset non compresso si copia una volta nella cache. */
    private fun modelFile(): File {
        val dir = File(appContext.cacheDir, "ml").apply { mkdirs() }
        val size = appContext.assets.openFd("ml/model.int8.onnx").use { it.length }
        val f = File(dir, "model.int8.$size.onnx")
        if (f.exists() && f.length() == size) return f
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "model.tmp")
        appContext.assets.open("ml/model.int8.onnx").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        check(tmp.length() == size && tmp.renameTo(f)) { "copia modello fallita" }
        return f
    }

    companion object {
        private const val TAG = "EyeAI"

        /** Testi con probabilita' note dal PC (JVM): legittimi ~0.002-0.005, truffe ~0.998. Soglie larghe: cattura il modello «rotto», non le sfumature. */
        val SELF_CHECK_BENIGN = listOf("Mario\nok", "Mario\nCi vediamo dopo alle 8", "Giulia\nHai visto la partita ieri sera?")
        val SELF_CHECK_SCAM = listOf(
            "Gentile clienti, e' stato effettuato un accesso anomalo al suo conto, inserire i dati al seguente link https://intesa-clienti-xq7k.com/login",
            "Royal Mail: Your parcel could not be delivered due to an unpaid shipping fee of 1.99 GBP. Please settle this here: https://rm-redelivery-uk-x7.com",
        )
    }
}
