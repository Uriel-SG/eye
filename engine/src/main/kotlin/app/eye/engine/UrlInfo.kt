package app.eye.engine

/**
 * API pubblica e stabile per ispezionare un URL con le stesse regole del motore (parsing alla WHATWG:
 * `\` come `/`, userinfo, percent-decoding dell'host, IDN, punto finale). Restituisce null se l'URL non
 * e' http/https/ftp valido o l'host non e' valido.
 */
object UrlInfo {
    private fun parse(url: String): ParsedUrl? = Extractors.parse(Text.stripInvisible(url).trim())

    /** Host ASCII minuscolo (punycode) che il browser contatterebbe, senza porta/punto finale. */
    fun host(url: String): String? = parse(url)?.host

    /** Dominio registrabile (eTLD+1) dell'host, ASCII minuscolo. */
    fun registrableDomain(url: String): String? = parse(url)?.registrable

    /** True se l'URL contiene `user@` prima dell'host (tecnica di inganno classica). */
    fun hasUserInfo(url: String): Boolean = parse(url)?.hasUserInfo ?: false
}
