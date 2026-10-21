package app.eye.android.data

import app.eye.android.core.MiniJson
import app.eye.engine.llm.LlmOpinion
import app.eye.engine.llm.LlmVerdict

/** Parere di Gemini Nano salvato con la voce di cronologia (resta sul telefono, cifrato). */
object NanoOpinionJson {
    fun encode(o: LlmOpinion, at: Long): String = MiniJson.stringify(
        mapOf("v" to o.verdict.name, "c" to o.confidence, "e" to o.explanation, "a" to o.actions, "t" to at),
    )

    /** null se assente, corrotto o con un giudizio sconosciuto. */
    fun decode(json: String): LlmOpinion? {
        if (json.isEmpty()) return null
        val m = MiniJson.parse(json) as? Map<*, *> ?: return null
        val v = runCatching { LlmVerdict.valueOf(m["v"] as String) }.getOrNull() ?: return null
        val c = (m["c"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0
        val e = m["e"] as? String ?: return null
        val a = (m["a"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        return LlmOpinion(v, c, e, a)
    }
}
