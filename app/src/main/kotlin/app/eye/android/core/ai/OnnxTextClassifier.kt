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
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        return Loaded(env, env.createSession(model.absolutePath, opts), tokenizer)
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

    private companion object {
        const val TAG = "EyeAI"
    }
}
