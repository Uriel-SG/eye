package app.eye.android.core.ai

import app.eye.engine.Level
import java.net.URI

enum class AiProvider(val id: String, val defaultModel: String) {
    ANTHROPIC("anthropic", "claude-opus-5-5"),
    GEMINI("gemini", "gemini-flash-lite-latest"),
    OPENAI("openai", "gpt-5-mini"),
    COMPATIBLE("compatible", "");

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Configurazione completa per una chiamata. La chiave non finisce mai in toString (niente log accidentali). */
class AiConfig(
    val provider: AiProvider,
    model: String,
    val baseUrl: String,
    val apiKey: String,
) {
    val model: String = model.trim().ifEmpty { provider.defaultModel }

    /** Pronta all'uso: chiave presente, modello presente e, per i compatibili, base URL https valido. */
    val isUsable: Boolean
        get() = AiKeys.isValid(apiKey) && model.isNotBlank() &&
            (provider != AiProvider.COMPATIBLE || AiUrls.validBase(baseUrl) != null)

    override fun toString() = "AiConfig(provider=$provider, model=$model)"
}

object AiKeys {
    private val valid = Regex("[\\x21-\\x7E]{8,512}")

    /** Toglie spazi ai bordi (anche NBSP e invisibili ai bordi). */
    fun clean(raw: String): String = raw.trim { it.isWhitespace() || Character.isSpaceChar(it) || Character.getType(it) == Character.FORMAT.toInt() }

    /** Solo ASCII stampabile senza spazi: tutto il resto romperebbe l'intestazione HTTP. */
    fun isValid(key: String): Boolean = valid.matches(key)
}

object AiUrls {
    /** Normalizza una base URL: solo https, host presente, niente credenziali/query/fragment. null se non valida. */
    fun validBase(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty() || t.length > 300 || t.any { it.isWhitespace() }) return null
        val u = try { URI(t) } catch (_: Exception) { return null }
        if (!"https".equals(u.scheme, ignoreCase = true)) return null
        if (u.host.isNullOrEmpty() || u.userInfo != null || u.rawQuery != null || u.rawFragment != null) return null
        return t
    }
}

enum class AiJudgement { TRUFFA, SOSPETTO, NESSUN_SEGNALE }

/** Parere per la logica pura del motore (verdetto finale); la spiegazione e' il primo motivo dell'AI. */
fun AiOpinion.toLlm() = app.eye.engine.llm.LlmOpinion(
    when (judgement) {
        AiJudgement.TRUFFA -> app.eye.engine.llm.LlmVerdict.TRUFFA
        AiJudgement.SOSPETTO -> app.eye.engine.llm.LlmVerdict.SOSPETTO
        AiJudgement.NESSUN_SEGNALE -> app.eye.engine.llm.LlmVerdict.LEGITTIMO
    },
    confidence, reasons.firstOrNull() ?: advice, listOfNotNull(advice.trim().takeIf { it.isNotEmpty() }),
)

data class AiOpinion(val judgement: AiJudgement, val reasons: List<String>, val advice: String, val confidence: Double = 0.0) {
    /** Il parere AI non cambia mai il verdetto: se è più tranquillo delle regole va mostrato un avviso. */
    fun softerThan(rules: Level): Boolean = rank(judgement) < when (rules) {
        Level.PROBABILE_TRUFFA -> 2
        Level.SOSPETTO -> 1
        Level.NESSUN_SEGNALE -> 0
    }

    private fun rank(j: AiJudgement) = when (j) { AiJudgement.TRUFFA -> 2; AiJudgement.SOSPETTO -> 1; AiJudgement.NESSUN_SEGNALE -> 0 }
}

enum class AiError { BAD_KEY, NOT_CONFIGURED, NETWORK, TIMEOUT, AUTH, RATE_LIMIT, FREE_LIMIT, OVERLOADED, HTTP, REFUSED, TRUNCATED, INVALID }

sealed interface AiOutcome {
    data class Success(val opinion: AiOpinion) : AiOutcome
    data class Failure(val error: AiError, val httpCode: Int = 0) : AiOutcome
}

/** Richiesta HTTP costruita senza rete (testabile). */
data class HttpSpec(val url: String, val headers: Map<String, String>, val body: String, val readTimeoutMs: Int = 90_000, val connectTimeoutMs: Int = 15_000) {
    override fun toString() = "HttpSpec($url)" // mai intestazioni (chiave)
}

data class HttpResult(val code: Int, val body: String)
