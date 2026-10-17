package app.eye.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.eye.engine.MlTestSupport.THRESHOLD
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tokenizer Kotlin + modello ONNX (onnxruntime JVM) contro i punteggi Python di ml/out/val_scores_minilm.csv. */
class MiniLmScoreTest {
    @Test fun kotlinScoresMatchPython() {
        assumeTrue(MlTestSupport.valJsonl.exists() && MlTestSupport.valScores.exists(), "ml/data/val.jsonl non disponibile")
        val model = File(MlTestSupport.assets, "model.int8.onnx")
        assumeTrue(model.exists(), "modello assente")
        val tok = MlTestSupport.tokenizer()
        val expected = MlTestSupport.csvScores()
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        var maxDiff = 0.0
        var decisionMismatch = 0
        env.createSession(model.absolutePath, opts).use { s ->
            for (r in MlTestSupport.valRows()) {
                val ids = tok.encode(r.text).map { it.toLong() }.toLongArray()
                val mask = LongArray(ids.size) { 1L }
                val shape = longArrayOf(1, ids.size.toLong())
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { a ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { b ->
                        s.run(mapOf("input_ids" to a, "attention_mask" to b)).use { out ->
                            @Suppress("UNCHECKED_CAST")
                            val l = (out[0].value as Array<FloatArray>)[0]
                            val m = maxOf(l[0], l[1])
                            val e0 = exp((l[0] - m).toDouble()); val e1 = exp((l[1] - m).toDouble())
                            val p = e1 / (e0 + e1)
                            val e = expected.getValue(r.id)
                            maxDiff = maxOf(maxDiff, abs(p - e))
                            if ((p >= THRESHOLD) != (e >= THRESHOLD)) decisionMismatch++
                        }
                    }
                }
            }
        }
        println("MiniLM Kotlin vs Python: max diff = $maxDiff, decisioni diverse = $decisionMismatch")
        assertTrue(maxDiff <= 1e-3, "differenza massima $maxDiff")
        assertEquals(0, decisionMismatch)
    }
}
