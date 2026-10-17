package app.eye.engine

import app.eye.engine.MlTestSupport.extraTokenIds
import app.eye.engine.MlTestSupport.ids
import app.eye.engine.MlTestSupport.valJsonl
import app.eye.engine.MlTestSupport.valRows
import app.eye.engine.MlTestSupport.valTokenIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parita' 100% con il tokenizer Python del training (ml/transformer/export_token_ids.py). */
class MiniLmTokenizerTest {
    private val tok = MlTestSupport.tokenizer()

    @Test fun valSetIdsIdentical() {
        assumeTrue(valJsonl.exists() && valTokenIds.exists(), "ml/data/val.jsonl non disponibile")
        val expected = valTokenIds.readLines().filter { it.isNotBlank() }.associate(MlTestSupport::ids)
        val rows = valRows()
        assertEquals(expected.size, rows.size)
        val bad = rows.filter { !tok.encode(it.text).contentEquals(expected.getValue(it.id)) }.map { it.id }
        println("tokenizer val: ${rows.size - bad.size}/${rows.size} identici")
        assertTrue(bad.isEmpty(), "id diversi su ${bad.size}/${rows.size}: ${bad.take(10)}")
    }

    @Test fun edgeCasesIdentical() {
        val bad = extraTokenIds.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val (id, exp) = ids(line)
            val got = tok.encode(o["text"]!!.jsonPrimitive.content)
            if (got.contentEquals(exp)) null else "$id: atteso ${exp.take(20)} ottenuto ${got.take(20)}"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test fun shapeAndTruncation() {
        val e = tok.encode("ciao ".repeat(500))
        assertEquals(128, e.size)
        assertEquals(0, e.first())
        assertEquals(2, e.last())
        assertEquals(listOf(0, 2), tok.encode("").toList())
    }
}
