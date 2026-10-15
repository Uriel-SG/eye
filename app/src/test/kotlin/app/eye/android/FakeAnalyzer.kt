package app.eye.android

import app.eye.engine.Analyzer
import app.eye.engine.Extracted
import app.eye.engine.Input
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict

/** Sostituto temporaneo finché non c'è RuleEngine.default() (F1-T16). Non è il motore reale. */
class FakeAnalyzer : Analyzer {
    override fun analyze(input: Input): Verdict {
        val t = input.text.lowercase()
        val urls = Regex("""https?://\S+""").findAll(input.text).map { it.value }.toList()
        val domains = urls.map { it.substringAfter("://").substringBefore('/').removePrefix("www.") }
        val reasons = mutableListOf<Reason>()
        if (urls.isNotEmpty()) reasons += Reason("fake.link", "Contiene un link: non aprirlo senza verificare.", 1)
        if (listOf("urgente", "subito", "entro 24", "sospeso", "bloccato").any { it in t })
            reasons += Reason("fake.urgenza", "Mette fretta per farti agire senza pensare.", 2)
        if (listOf("codice", "pin", "password", "iban", "carta").any { it in t })
            reasons += Reason("fake.dati", "Chiede dati o codici personali.", 3)
        val score = reasons.sumOf { it.weight }
        val level = when {
            score >= 4 -> Level.PROBABILE_TRUFFA
            score >= 1 -> Level.SOSPETTO
            else -> Level.NESSUN_SEGNALE
        }
        return Verdict(level, score, reasons, emptyList(), Extracted(urls = urls, domains = domains), null, 0)
    }
}
