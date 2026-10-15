package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/** Harness F1-T7: legge i file jsonl in ../corpus, stampa recall/FP per categoria, asserisce recall >= 90% e FP < 3%. */
class CorpusTest {
    private class Row(val id: String, val text: String, val category: String, val sender: String?)

    private fun load(name: String): List<Row>? {
        val f = File("../corpus/$name")
        if (!f.exists()) return null
        return f.readLines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            Row(
                o["id"]?.jsonPrimitive?.content ?: "?",
                o["text"]!!.jsonPrimitive.content,
                o["category"]?.jsonPrimitive?.content ?: "?",
                o["sender"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun pct(a: Int, b: Int) = if (b == 0) 0.0 else 100.0 * a / b

    @Test fun corpusMetrics() {
        val scam = load("scam.jsonl")
        val legit = load("legit.jsonl")
        assumeTrue(scam != null && legit != null, "corpus non presente")
        val engine = RuleEngine.default()
        fun run(r: Row) = engine.analyze(Input(r.text, Source.PASTE, r.sender))

        val sb = StringBuilder("\n=== Corpus: recall truffe per categoria (livello >= SOSPETTO) ===\n")
        var tp = 0
        val missed = ArrayList<String>()
        scam!!.groupBy { it.category }.toSortedMap().forEach { (cat, rows) ->
            val hit = rows.count { r -> (run(r).level != Level.NESSUN_SEGNALE).also { ok -> if (!ok) missed.add("${r.id} ${r.text.take(90)}") } }
            tp += hit
            sb.append(String.format(Locale.ROOT, "  %-18s %3d/%3d  %.1f%%\n", cat, hit, rows.size, pct(hit, rows.size)))
        }
        val probable = scam.count { run(it).level == Level.PROBABILE_TRUFFA }
        val recall = pct(tp, scam.size)
        sb.append(String.format(Locale.ROOT, "RECALL totale: %d/%d = %.1f%% (di cui PROBABILE_TRUFFA: %.1f%%)\n", tp, scam.size, recall, pct(probable, scam.size)))

        sb.append("=== Falsi positivi legittimi per categoria (livello != NESSUN_SEGNALE) ===\n")
        var fp = 0
        var fpProbable = 0
        val fps = ArrayList<String>()
        legit!!.groupBy { it.category }.toSortedMap().forEach { (cat, rows) ->
            val bad = rows.filter { r ->
                val v = run(r)
                if (v.level == Level.PROBABILE_TRUFFA) fpProbable++
                (v.level != Level.NESSUN_SEGNALE).also { if (it) fps.add("${r.id} [${v.level} ${v.score}] ${v.reasons.joinToString { x -> x.ruleId + (if (x.ruleId.startsWith("URL") || x.ruleId.startsWith("MIS")) "{" + x.text.take(70) + "}" else "") }} :: ${r.text.take(80)}") }
            }
            fp += bad.size
            sb.append(String.format(Locale.ROOT, "  %-18s %3d/%3d  %.1f%%\n", cat, bad.size, rows.size, pct(bad.size, rows.size)))
        }
        val fpRate = pct(fp, legit.size)
        sb.append(String.format(Locale.ROOT, "FP totale: %d/%d = %.1f%% (di cui PROBABILE_TRUFFA: %d)\n", fp, legit.size, fpRate, fpProbable))
        if (missed.isNotEmpty()) sb.append("--- Truffe non rilevate ---\n").append(missed.joinToString("\n")).append('\n')
        if (fps.isNotEmpty()) sb.append("--- Falsi positivi ---\n").append(fps.joinToString("\n")).append('\n')
        println(sb)

        assertTrue(recall >= 90.0, "recall $recall < 90%")
        assertTrue(fpRate < 3.0, "FP $fpRate >= 3%")
    }
}
