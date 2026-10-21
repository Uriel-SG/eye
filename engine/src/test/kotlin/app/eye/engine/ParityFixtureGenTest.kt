package app.eye.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * Rigenera la fixture del test di parita' Android (app/src/androidTest/assets/parity.jsonl): testi, id token e
 * probabilita' calcolati sulla JVM con il modello/tokenizer correnti. Uso: EYE_GEN_PARITY=1 ./gradlew :engine:test --tests '*ParityFixtureGenTest*'
 */
class ParityFixtureGenTest {
    @Test fun generate() {
        assumeTrue(System.getenv("EYE_GEN_PARITY") == "1", "EYE_GEN_PARITY non impostato")
        val chat = listOf(
            "ok", "Mario\nok", "Mario\nCi vediamo dopo alle 8", "Giulia\nHai visto la partita ieri sera?",
            "Mamma\nOk grazie, a dopo 😊", "👍", "Marco Rossi\nSì", "Paolo\nok\nLink: https://www.youtube.com/watch?v=abc",
            "Gruppo Famiglia\nAnna: arrivo tra 10 minuti", "Telegram\nNuovo messaggio", "WhatsApp\n2 nuovi messaggi",
            "Luca\nCi sentiamo domani? ​perfetto", "Sara\nGrazie mille!!!", "Mario\nok\nok\nok",
        )
        val val_ = MlTestSupport.valRows().filterIndexed { i, _ -> i % 13 == 0 }.map { it.text }
        val tok = MlTestSupport.tokenizer()
        val env = OrtEnvironment.getEnvironment()
        val s = env.createSession(File(MlTestSupport.assets, "model.int8.onnx").absolutePath, OrtSession.SessionOptions())
        val lines = (chat + val_).map { t ->
            val ids = tok.encode(t)
            val l = ids.map { it.toLong() }.toLongArray(); val m = LongArray(l.size) { 1L }; val sh = longArrayOf(1, l.size.toLong())
            val p = OnnxTensor.createTensor(env, LongBuffer.wrap(l), sh).use { a -> OnnxTensor.createTensor(env, LongBuffer.wrap(m), sh).use { b ->
                s.run(mapOf("input_ids" to a, "attention_mask" to b)).use { o ->
                    @Suppress("UNCHECKED_CAST") val lg = (o[0].value as Array<FloatArray>)[0]; val mx = maxOf(lg[0], lg[1])
                    exp((lg[1] - mx).toDouble()) / (exp((lg[0] - mx).toDouble()) + exp((lg[1] - mx).toDouble())) } } }
            JsonObject(mapOf("text" to JsonPrimitive(t), "ids" to JsonArray(ids.map { JsonPrimitive(it) }), "p" to JsonPrimitive(p))).toString()
        }
        val out = File("../app/src/androidTest/assets/parity.jsonl"); out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n")
    }
}
