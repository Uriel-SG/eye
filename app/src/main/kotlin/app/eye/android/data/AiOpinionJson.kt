package app.eye.android.data

import app.eye.android.core.MiniJson
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion

/** Secondo parere AI salvato con la voce di cronologia: contenuto già oscurato, più provider, modello e data. */
data class SavedAiOpinion(val opinion: AiOpinion, val providerId: String, val model: String, val at: Long, val auto: Boolean = false)

/** Pareri AI richiesti a mano: mai "auto" (non cambiano il verdetto) e non sostituiscono il parere automatico gia' salvato. */
object ManualAi {
    fun opinion(o: app.eye.android.core.ai.AiOpinion, providerId: String, model: String, at: Long) = SavedAiOpinion(o, providerId, model, at, auto = false)
    fun persist(previousWasAuto: Boolean): Boolean = !previousWasAuto
}

object AiOpinionJson {
    fun encode(s: SavedAiOpinion): String = MiniJson.stringify(
        mapOf(
            "j" to s.opinion.judgement.name, "r" to s.opinion.reasons, "a" to s.opinion.advice,
            "p" to s.providerId, "m" to s.model, "t" to s.at, "c" to s.opinion.confidence, "x" to s.auto,
        ),
    )

    /** null se assente, corrotto o con un giudizio sconosciuto. */
    fun decode(json: String): SavedAiOpinion? {
        if (json.isEmpty()) return null
        val m = MiniJson.parse(json) as? Map<*, *> ?: return null
        val j = runCatching { AiJudgement.valueOf(m["j"] as String) }.getOrNull() ?: return null
        val reasons = (m["r"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        return SavedAiOpinion(
            AiOpinion(j, reasons, m["a"] as? String ?: "", (m["c"] as? Number)?.toDouble() ?: 0.0),
            m["p"] as? String ?: "", m["m"] as? String ?: "", (m["t"] as? Number)?.toLong() ?: 0L, m["x"] == true,
        )
    }
}
