package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/** Valutazione sul set cieco (corpus/blind.jsonl, scritto senza vedere le regole). Solo report, nessuna asserzione. */
class BlindEvalTest {
    @Test fun blindReport() {
        val f = File("../corpus/blind.jsonl")
        assumeTrue(f.exists(), "set cieco non presente")
        val engine = RuleEngine.default()
        val rows = f.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        val misses = StringBuilder()
        var scam = 0; var tp = 0; var tpStrong = 0; var legit = 0; var fp = 0
        for (o in rows) {
            val text = o["text"]!!.jsonPrimitive.content
            val v = engine.analyze(Input(text, Source.PASTE, o["sender"]?.jsonPrimitive?.content))
            val id = o["id"]!!.jsonPrimitive.content
            if (o["label"]!!.jsonPrimitive.content == "scam") {
                scam++
                if (v.level != Level.NESSUN_SEGNALE) tp++ else misses.append("- FN $id (${v.score}): ${text.take(140)}\n")
                if (v.level == Level.PROBABILE_TRUFFA) tpStrong++
            } else {
                legit++
                if (v.level != Level.NESSUN_SEGNALE) {
                    fp++
                    misses.append("- FP $id ${v.level} (${v.score}; ${v.reasons.joinToString { it.ruleId }}): ${text.take(140)}\n")
                }
            }
        }
        val report = "# Valutazione set cieco\n\nRecall (>=SOSPETTO): $tp/$scam · PROBABILE_TRUFFA: $tpStrong/$scam · Falsi positivi: $fp/$legit\n\n$misses"
        File("../corpus/eval_blind.md").writeText(report)
        println(report)
    }
}
