package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/** Esporta i verdetti delle regole (id,label,level,score) per un jsonl: EYE_DUMP_IN=percorso, EYE_DUMP_OUT=percorso csv. Usato per combinare regole e classificatore (ml/). */
class EngineDumpTest {
    @Test fun dump() {
        val inp = System.getenv("EYE_DUMP_IN"); val out = System.getenv("EYE_DUMP_OUT")
        assumeTrue(inp != null && out != null, "EYE_DUMP_IN/OUT non impostati")
        val engine = RuleEngine.default()
        val sb = StringBuilder("id,label,level,score\n")
        File(inp!!).readLines().filter { it.isNotBlank() }.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val v = engine.analyze(Input(o["text"]!!.jsonPrimitive.content, Source.PASTE, o["sender"]?.jsonPrimitive?.content))
            sb.append("${o["id"]!!.jsonPrimitive.content},${o["label"]!!.jsonPrimitive.content},${v.level},${v.score}\n")
        }
        File(out!!).writeText(sb.toString())
    }
}
