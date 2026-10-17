package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Percorsi e lettori per i test del classificatore (i dati di terzi in ml/data non sono versionati: i test si saltano se mancano). */
object MlTestSupport {
    val assets = File("../app/src/main/assets/ml")
    val valJsonl = File("../ml/data/val.jsonl")
    val valTokenIds = File("../ml/out/val_token_ids.jsonl")
    val extraTokenIds = File("../ml/out/extra_token_ids.jsonl")
    val valScores = File("../ml/out/val_scores_minilm.csv")
    const val THRESHOLD = 0.4915

    fun tokenizer() = MiniLmTokenizer(File(assets, "tokenizer.json").readText())

    class Row(val id: String, val text: String, val label: String)

    fun valRows(): List<Row> = valJsonl.readLines().filter { it.isNotBlank() }.map {
        val o = Json.parseToJsonElement(it).jsonObject
        Row(o["id"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content, o["label"]!!.jsonPrimitive.content)
    }

    fun ids(line: String): Pair<String, IntArray> {
        val o = Json.parseToJsonElement(line).jsonObject
        return o["id"]!!.jsonPrimitive.content to o["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
    }

    fun csvScores(): Map<String, Double> = valScores.readLines().drop(1).filter { it.isNotBlank() }.associate {
        val c = it.split(',')
        c[0] to c.last().toDouble()
    }
}
