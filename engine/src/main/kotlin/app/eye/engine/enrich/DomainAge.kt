package app.eye.engine.enrich

import app.eye.engine.Hosts
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.RuleLists
import app.eye.engine.UserExceptions
import app.eye.engine.Verdict

/** Esito della ricerca dell'eta' per un dominio: [registeredAtMs] null = non disponibile (nessun RDAP o nessuna data). */
data class DomainAgeEntry(val domain: String, val registeredAtMs: Long?)

/**
 * Eta' del dominio (data di registrazione dal registro, via RDAP). Logica pura: la rete sta in :app.
 * Regola di sicurezza: puo' solo ALZARE il livello, mai abbassarlo; domini popolari/ufficiali e shortener non si controllano.
 */
object DomainAge {
    const val STRONG_DAYS = 30L
    const val MILD_DAYS = 180L
    const val MAX_DOMAINS = 3
    const val STRONG_ID = "AGE-01"
    const val MILD_ID = "AGE-02"
    const val STRONG_WEIGHT = 40
    const val MILD_WEIGHT = 10
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Domini da controllare: max [max], esclusi IP, popolari, affidabili, ufficiali dei marchi e shortener. */
    fun candidates(domains: List<String>, lists: RuleLists, max: Int = MAX_DOMAINS): List<String> {
        val skip = HashSet<String>().apply {
            addAll(lists.popularDomains); addAll(lists.trustedDomains); addAll(lists.shorteners)
            lists.brands.forEach { addAll(it.official) }
        }
        return domains.asSequence().map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() && !Hosts.isIp(it) && RdapBootstrap.isValidDomain(it) && it !in skip }
            .distinct().take(max).toList()
    }

    /** Giorni interi tra registrazione e [nowMs]; una data nel futuro vale 0. */
    fun ageDays(registeredAtMs: Long, nowMs: Long): Long = ((nowMs - registeredAtMs) / DAY_MS).coerceAtLeast(0)

    private fun rank(l: Level) = when (l) { Level.PROBABILE_TRUFFA -> 0; Level.SOSPETTO -> 1; Level.NESSUN_SEGNALE -> 2 }
    private fun stricter(a: Level, b: Level) = if (rank(a) <= rank(b)) a else b

    /**
     * Verdetto dopo il controllo: < 30 giorni = segnale forte (almeno SOSPETTO; PROBABILE_TRUFFA se c'erano gia' altri
     * segnali di truffa); 30-180 giorni = lieve (+10; alza NESSUN_SEGNALE -> SOSPETTO solo con altri segnali); oltre = nessun effetto.
     * Mai abbassa. Un'eccezione "affidabile" dell'utente non viene scavalcata.
     */
    fun apply(verdict: Verdict, entries: List<DomainAgeEntry>, nowMs: Long): Verdict {
        if (verdict.reasons.any { it.ruleId == UserExceptions.TRUSTED_ID || it.ruleId == STRONG_ID || it.ruleId == MILD_ID }) return verdict
        val youngest = entries.filter { it.registeredAtMs != null }
            .minByOrNull { ageDays(it.registeredAtMs!!, nowMs) } ?: return verdict
        val days = ageDays(youngest.registeredAtMs!!, nowMs)
        if (days >= MILD_DAYS) return verdict
        val otherSignals = verdict.reasons.any { it.weight > 0 && it.ruleId != "LLM-01" }
        val strong = days < STRONG_DAYS
        val target = when {
            strong && otherSignals -> Level.PROBABILE_TRUFFA
            strong -> Level.SOSPETTO
            otherSignals -> Level.SOSPETTO
            else -> verdict.level
        }
        val level = stricter(verdict.level, target)
        val reason = if (strong) Reason(STRONG_ID, strongText(youngest.domain, days), STRONG_WEIGHT)
        else Reason(MILD_ID, mildText(youngest.domain, days), MILD_WEIGHT)
        return verdict.copy(
            level = level, score = verdict.score + reason.weight, reasons = verdict.reasons + reason,
            // azioni vuote: l'app usa quelle predefinite del nuovo livello
            actions = if (level != verdict.level) emptyList() else verdict.actions,
        )
    }

    fun strongText(domain: String, days: Long): String {
        val ago = when (days) { 0L -> "oggi"; 1L -> "1 giorno fa"; else -> "$days giorni fa" }
        return "Il sito $domain è stato registrato solo $ago: i siti truffa sono quasi sempre nuovissimi."
    }

    fun mildText(domain: String, days: Long): String {
        val months = (days / 30).coerceAtLeast(1)
        return "Il sito $domain è stato registrato circa ${if (months == 1L) "1 mese" else "$months mesi"} fa: i siti recenti meritano più prudenza."
    }

    /** Eta' leggibile per l'interfaccia ("12 giorni", "5 mesi", "3 anni"). */
    fun humanAge(days: Long): String = when {
        days < 1 -> "meno di un giorno"
        days == 1L -> "1 giorno"
        days < 60 -> "$days giorni"
        days < 730 -> "${days / 30} mesi"
        else -> "${days / 365} anni"
    }
}
