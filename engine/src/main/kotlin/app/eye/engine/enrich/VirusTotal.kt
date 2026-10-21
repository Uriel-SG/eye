package app.eye.engine.enrich

import app.eye.engine.Verdict

/** Pulsante "Apri su VirusTotal": solo il nome del dominio, mai l'URL completo (i percorsi possono contenere token personali). */
object VirusTotal {
    private val ipv4 = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$")

    /** Domini per cui offrire il pulsante; vuoto = pulsante nascosto. */
    fun domainsFor(verdict: Verdict): List<String> =
        verdict.extracted.domains.map { it.trim().lowercase().trimEnd('.') }
            .filter { urlFor(it) != null }.distinct()

    fun isVisible(verdict: Verdict): Boolean = domainsFor(verdict).isNotEmpty()

    fun urlFor(domain: String): String? {
        val d = domain.trim().lowercase().trimEnd('.')
        return when {
            ipv4.matches(d) -> "https://www.virustotal.com/gui/ip-address/$d"
            RdapBootstrap.isValidDomain(d) && !d.all { it.isDigit() || it == '.' } -> "https://www.virustotal.com/gui/domain/$d"
            else -> null
        }
    }
}
