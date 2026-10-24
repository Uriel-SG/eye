package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate notifiche: il testo e' gia' nel formato di NotificationText.compose («titolo\ncorpo» + righe «Link: ...»).
 * Percorso reale: RuleEngine (origin != null) + AlertPolicy. Il MiniLM «peggiore» e' simulato con p = 0.99 sempre.
 * Zero avvisi sulle notifiche legittime (build rossa se una ne genera); recall avvisi sulle truffe con soglia minima.
 */
class NotificationGateCorpusTest {
    private class Row(val id: String, val text: String, val origin: String, val sender: String, val resolved: Map<String, String> = emptyMap())

    private fun load(name: String): List<Row> {
        val f = File("../corpus/$name.jsonl")
        assertTrue(f.exists(), "manca corpus/$name.jsonl")
        return f.readLines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            // facoltativo: link accorciato -> destinazione gia' risolta (fixture della risoluzione di rete)
            val res = (o["resolved"] as? kotlinx.serialization.json.JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            Row(s("id"), s("text"), s("origin"), s("sender"), res)
        }
    }

    private val worstModel = RuleEngine(RuleEngine.loadBundled(), classifier = { 0.99f })
    private val noModel = RuleEngine(RuleEngine.loadBundled())

    private fun verdict(e: RuleEngine, r: Row) = e.analyze(Input(r.text, Source.SHARE, declaredSender = r.sender, origin = r.origin, resolvedLinks = r.resolved))

    private class Res(val alerts: List<String>, val levelAlerts: Int, val n: Int, val missed: List<String> = emptyList())

    private fun run(e: RuleEngine, rows: List<Row>): Res {
        val alerts = ArrayList<String>(); val missed = ArrayList<String>(); var lv = 0
        for (r in rows) {
            val v = verdict(e, r)
            if (v.level != Level.NESSUN_SEGNALE) lv++
            val d = "${r.id} ${v.level} [${v.reasons.joinToString { it.ruleId }}]: ${r.text.replace('\n', '|').take(130)}"
            if (!AlertPolicy.shouldAlert(v)) missed.add(d)
            if (AlertPolicy.shouldAlert(v)) alerts.add("${r.id} ${v.level} [${v.reasons.joinToString { it.ruleId }}]: ${r.text.replace('\n', '|').take(110)}")
        }
        return Res(alerts, lv, rows.size, missed)
    }

    @Test fun gate() {
        val legit = load("notif_legit"); val hold = load("notif_legit_holdout"); val scam = load("notif_scam")
        val report = StringBuilder()
        var legitAlerts = 0
        val lowRecall = ArrayList<String>()
        for ((label, e) in listOf("MiniLM p=0.99" to worstModel, "senza modello" to noModel)) {
            val l = run(e, legit); val h = run(e, hold); val s = run(e, scam)
            val recall = s.alerts.size * 100.0 / s.n
            report.append("[$label] legit: avvisi ${l.alerts.size}/${l.n} (livello>=SOSPETTO ${l.levelAlerts}) · holdout: ${h.alerts.size}/${h.n} (livello ${h.levelAlerts}) · scam: recall avvisi ${s.alerts.size}/${s.n} = %.1f%% (livello ${s.levelAlerts})\n".format(recall))
            if (System.getenv("EYE_GATE_VERBOSE") != null) {
                l.alerts.forEach { report.append("  FP $it\n") }
                if (System.getenv("EYE_GATE_MISS") != null) s.missed.forEach { report.append("  MISS $it\n") }
                if (System.getenv("EYE_GATE_HOLD") != null) h.alerts.forEach { report.append("  FPH $it\n") }
            }
            legitAlerts += l.alerts.size + h.alerts.size
            if (recall < MIN_RECALL) lowRecall.add("[$label] recall avvisi %.1f%% < %.0f%%".format(recall, MIN_RECALL))
        }
        println(report)
        assertTrue(lowRecall.isEmpty(), lowRecall.joinToString())
        assertEquals(0, legitAlerts, "notifiche legittime con avviso:\n$report")
    }

    companion object { const val MIN_RECALL = 85.0 }
}
