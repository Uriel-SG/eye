package app.eye.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Motore + MiniLM REALE (onnxruntime JVM, modello degli assets) come in primo piano sul telefono.
 * Il corpus «meta» (discorsi su truffe, fumetti, casi reali) deve restare a zero falsi positivi anche con il modello;
 * sui set ciechi si stampano recall e falsi positivi (report, nessuna soglia).
 */
class RealModelEvalTest {
    private val model = File(MlTestSupport.assets, "model.int8.onnx")

    private fun classifier(): TextClassifier {
        val tok = MlTestSupport.tokenizer()
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(model.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
        return TextClassifier { text ->
            val ids = tok.encode(text).map { it.toLong() }.toLongArray()
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { a ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { b ->
                    session.run(mapOf("input_ids" to a, "attention_mask" to b)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val l = (out[0].value as Array<FloatArray>)[0]
                        val m = maxOf(l[0], l[1])
                        val e0 = exp((l[0] - m).toDouble()); val e1 = exp((l[1] - m).toDouble())
                        (e1 / (e0 + e1)).toFloat()
                    }
                }
            }
        }
    }

    private fun rows(name: String) = File("../corpus/$name.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    @Test fun report() {
        assumeTrue(model.exists() && File(MlTestSupport.assets, "tokenizer.json").exists(), "modello assente")
        val e = RuleEngine(RuleEngine.loadBundled(), classifier = classifier())
        val sb = StringBuilder("\n")
        val metaBad = ArrayList<String>()
        for (o in rows("meta")) {
            val src = Source.valueOf(o["source"]!!.jsonPrimitive.content)
            val v = e.analyze(Input(o["text"]!!.jsonPrimitive.content, src))
            val legit = o["label"]!!.jsonPrimitive.content == "legit"
            if (legit && v.level != Level.NESSUN_SEGNALE) metaBad.add("FP ${o["id"]!!.jsonPrimitive.content} ${v.level} ${v.reasons.map { it.ruleId }}")
            if (!legit && v.level == Level.NESSUN_SEGNALE) metaBad.add("FN ${o["id"]!!.jsonPrimitive.content}")
        }
        sb.append("MODELLO REALE meta: problemi=${metaBad.size} $metaBad\n")
        for (n in listOf("blind", "blind2", "blind3")) {
            var s = 0; var tp = 0; var l = 0; var fp = 0
            val miss = ArrayList<String>()
            for (o in rows(n)) {
                val v = e.analyze(Input(o["text"]!!.jsonPrimitive.content, Source.PASTE, o["sender"]?.jsonPrimitive?.content))
                if (o["label"]!!.jsonPrimitive.content == "scam") { s++; if (v.level != Level.NESSUN_SEGNALE) tp++ else miss.add(o["id"]!!.jsonPrimitive.content) }
                else { l++; if (v.level != Level.NESSUN_SEGNALE) fp++ }
            }
            sb.append("MODELLO REALE $n: recall $tp/$s, FP $fp/$l, FN $miss\n")
        }
        println(sb)
        assertTrue(metaBad.isEmpty(), metaBad.toString())
    }
}
