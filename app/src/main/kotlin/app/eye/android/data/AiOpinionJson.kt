package app.eye.android.data

import app.eye.android.core.MiniJson
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion

/** Secondo parere AI salvato con la voce di cronologia: contenuto già oscurato, più provider, modello e data. */
data class SavedAiOpinion(val opinion: AiOpinion, val providerId: String, val model: String, val at: Long)

object AiOpinionJson {
    fun encode(s: SavedAiOpinion): String = MiniJson.stringify(
        mapOf(
            "j" to s.opinion.judgement.name, "r" to s.opinion.reasons, "a" to s.opinion.advice,
            "p" to s.providerId, "m" to s.model, "t" to s.at,
        ),
    )

    /** null se assente, corrotto o con un giudizio sconosciuto. */
    fun decode(json: String): SavedAiOpinion? {
        if (json.isEmpty()) return null
        val m = MiniJson.parse(json) as? Map<*, *> ?: return null
        val j = runCatching { AiJudgement.valueOf(m["j"] as String) }.getOrNull() ?: return null
        val reasons = (m["r"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        return SavedAiOpinion(
            AiOpinion(j, reasons, m["a"] as? String ?: ""),
            m["p"] as? String ?: "", m["m"] as? String ?: "", (m["t"] as? Number)?.toLong() ?: 0L,
        )
    }
}
