package app.eye.android.data

import app.eye.android.core.MiniJson
import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict

/** Serializzazione del [Verdict] per la cronologia locale (solo su questo dispositivo). */
object VerdictJson {
    fun encode(v: Verdict): String = MiniJson.stringify(
        mapOf(
            "level" to v.level.name,
            "score" to v.score,
            "reasons" to v.reasons.map { mapOf("id" to it.ruleId, "text" to it.text, "w" to it.weight) },
            "actions" to v.actions,
            "urls" to v.extracted.urls,
            "domains" to v.extracted.domains,
            "phones" to v.extracted.phones,
            "ibans" to v.extracted.ibans,
            "amounts" to v.extracted.amounts,
            "category" to v.category,
            "rules" to v.rulesVersion,
        ),
    )

    /** null se il JSON è assente, corrotto o con un livello sconosciuto. */
    fun decode(s: String): Verdict? {
        val m = MiniJson.parse(s) as? Map<*, *> ?: return null
        val level = runCatching { Level.valueOf(m["level"] as String) }.getOrNull() ?: return null
        fun strings(k: String) = (m[k] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val reasons = (m["reasons"] as? List<*>).orEmpty().mapNotNull {
            val r = it as? Map<*, *> ?: return@mapNotNull null
            Reason(
                r["id"] as? String ?: return@mapNotNull null,
                r["text"] as? String ?: return@mapNotNull null,
                (r["w"] as? Number)?.toInt() ?: 0,
            )
        }
        return Verdict(
            level = level,
            score = (m["score"] as? Number)?.toInt() ?: 0,
            reasons = reasons,
            actions = strings("actions"),
            extracted = Extracted(strings("urls"), strings("domains"), strings("phones"), strings("ibans"), strings("amounts")),
            category = m["category"] as? String,
            rulesVersion = (m["rules"] as? Number)?.toLong() ?: 0L,
        )
    }
}
