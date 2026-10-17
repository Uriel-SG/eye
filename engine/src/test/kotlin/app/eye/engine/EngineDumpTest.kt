package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.test.Test

/** Esporta i verdetti delle regole (id,label,level,score) per un jsonl: EYE_DUMP_IN=percorso, EYE_DUMP_OUT=percorso csv. Usato per combinare regole e classificatore (ml/). */
class EngineDumpTest {
    @Test fun dump() {
        val inp = System.getenv("EYE_DUMP_IN"); val out = System.getenv("EYE_DUMP_OUT")
        assumeTrue(inp != null && out != null, "EYE_DUMP_IN/OUT non impostati")
        val ai = System.getenv("EYE_DUMP_AI") == "1"
        val engine = if (!ai) RuleEngine.default() else {
            val tok = MlTestSupport.tokenizer(); val env = OrtEnvironment.getEnvironment()
            val s = env.createSession(java.io.File(MlTestSupport.assets, "model.int8.onnx").absolutePath, OrtSession.SessionOptions())
            val ts = System.getenv("EYE_DUMP_TS")
            val base = RuleEngine.loadBundled()
            val rs = if (ts != null) base.copy(ai = base.ai.copy(raiseThreshold = ts.toDouble())) else base
            RuleEngine(rs, classifier = { t ->
                val ids = tok.encode(t).map { it.toLong() }.toLongArray(); val mask = LongArray(ids.size) { 1L }; val sh = longArrayOf(1, ids.size.toLong())
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), sh).use { a -> OnnxTensor.createTensor(env, LongBuffer.wrap(mask), sh).use { b ->
                    s.run(mapOf("input_ids" to a, "attention_mask" to b)).use { o ->
                        @Suppress("UNCHECKED_CAST") val l = (o[0].value as Array<FloatArray>)[0]; val m = maxOf(l[0], l[1])
                        (exp((l[1] - m).toDouble()) / (exp((l[0] - m).toDouble()) + exp((l[1] - m).toDouble()))).toFloat() } } }
            })
        }
        val sb = StringBuilder("id,label,level,score\n")
        File(inp!!).readLines().filter { it.isNotBlank() }.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val v = engine.analyze(Input(o["text"]!!.jsonPrimitive.content, Source.PASTE, o["sender"]?.jsonPrimitive?.content))
            sb.append("${o["id"]!!.jsonPrimitive.content},${o["label"]!!.jsonPrimitive.content},${v.level},${v.score}\n")
        }
        File(out!!).writeText(sb.toString())
    }
}
