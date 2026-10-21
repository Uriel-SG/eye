package app.eye.android.core.ai

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.eye.engine.MiniLmTokenizer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parita' Android/JVM: id token (tokenizer su ART) e probabilita' (ONNX Runtime Android) devono coincidere con quelli
 * calcolati sulla JVM (fixture androidTest/assets/parity.jsonl, rigenerabile con ParityFixtureGenTest).
 * Il classificatore e' quello di produzione, con modello copiato dagli asset del target app.
 */
class OnnxParityTest {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = InstrumentationRegistry.getInstrumentation().context.assets.open("parity.jsonl").use {
        it.readBytes().toString(Charsets.UTF_8).lines().filter { l -> l.isNotBlank() }.map { l -> JSONObject(l) }
    }

    @Test fun tokenizerIdsMatchJvm() {
        val tok = target.assets.open("ml/tokenizer.json").use { MiniLmTokenizer(it.readBytes().toString(Charsets.UTF_8)) }
        for (f in fixtures) {
            val text = f.getString("text")
            val want = f.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            assertArrayEquals("ids diversi per il testo di ${text.length} caratteri: $text", want, tok.encode(text))
        }
    }

    @Test fun probabilitiesMatchJvm() {
        val clf = OnnxTextClassifier(target)
        for (f in fixtures) {
            val text = f.getString("text")
            val want = f.getDouble("p")
            val got = clf.scamProbability(text) ?: error("modello non disponibile")
            assertEquals("p diversa per: $text", want, got.toDouble(), 0.02)
        }
    }

    /** I vettori dell'autotest di produzione devono passare (altrimenti il modello si spegne da solo). */
    @Test fun selfCheckVectorsAreSeparated() {
        val clf = OnnxTextClassifier(target)
        for (t in OnnxTextClassifier.SELF_CHECK_BENIGN) assertEquals(0.0, clf.scamProbability(t)!!.toDouble(), 0.2)
        for (t in OnnxTextClassifier.SELF_CHECK_SCAM) assertEquals(1.0, clf.scamProbability(t)!!.toDouble(), 0.2)
    }
}
