package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Discorsi SU truffe/sicurezza (articoli, avvisi educativi, chat che citano truffe, report tecnici, fumetti, casi reali)
 * contro truffe che usano citazioni o racconti per ingannare ma hanno un vettore d'azione (corpus/meta.jsonl).
 * Legit: sempre NESSUN_SEGNALE e nessun avviso. Scam: sempre almeno SOSPETTO. Con o senza modello (p = 0.99, caso peggiore).
 */
class MetaCorpusTest {
    private class Row(val id: String, val text: String, val label: String, val source: Source)

    private val rows = File("../corpus/meta.jsonl").readLines().filter { it.isNotBlank() }.map {
        val o = Json.parseToJsonElement(it).jsonObject
        Row(o["id"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content, o["label"]!!.jsonPrimitive.content, Source.valueOf(o["source"]!!.jsonPrimitive.content))
    }

    @Test fun legitNeverFlaggedScamNeverMissed() {
        val bad = ArrayList<String>()
        for (e in listOf(RuleEngine.default(), RuleEngine(RuleEngine.loadBundled(), classifier = { 0.99f }))) {
            for (r in rows) {
                val v = e.analyze(Input(r.text, r.source))
                val d = "${r.id} ${v.level} [${v.reasons.joinToString { it.ruleId }}] vec=${v.vectors}: ${r.text.take(90)}"
                if (r.label == "legit" && (v.level != Level.NESSUN_SEGNALE || AlertPolicy.shouldAlert(v))) bad.add("FP $d")
                if (r.label == "scam" && v.level == Level.NESSUN_SEGNALE) bad.add("FN $d")
            }
        }
        println("META legit=${rows.count { it.label == "legit" }} scam=${rows.count { it.label == "scam" }} problemi=${bad.size}\n" + bad.joinToString("\n"))
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
