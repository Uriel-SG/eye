package app.eye.engine

/**
 * «Disinnesca» gli URL in un testo in uscita, così il destinatario non può toccarli per sbaglio:
 * `https://poste-x.top/login` -> `hxxps://poste-x[.]top/login`. Le email senza link restano invariate.
 * Idempotente: i link già disinnescati non vengono toccati.
 */
object LinkDefang {
    data class Result(val text: String, val count: Int)

    const val NOTE = "(link resi non cliccabili per sicurezza)"

    private val token = Regex("\\S+")
    private const val LEAD = "([{<\"'«“‘*"
    private const val TRAIL = ".,;:!?)]}>\"'»”’…*"
    private val scheme = Regex("^(?i)(https?|ftp):/*")

    fun apply(text: String): Result {
        var count = 0
        val out = token.replace(text) { m ->
            val tok = m.value
            if (tok.contains("[.]") || tok.contains("(.)") || tok.contains("[@]") || tok.contains("hxxp", ignoreCase = true)) return@replace tok
            val lead = tok.length - tok.trimStart { it in LEAD }.length
            val core = tok.substring(lead).trimEnd { it in TRAIL }
            if (core.length < 4) return@replace tok
            val url = Extractors.urls(core).firstOrNull() ?: return@replace tok
            var at = core.indexOf(url.raw)
            if (at < 0) at = 0
            val end = if (at == 0 && !core.startsWith(url.raw)) core.length else at + url.raw.length
            val piece = core.substring(at, end)
            val d = defangPiece(piece)
            if (d == piece) return@replace tok
            count++
            tok.substring(0, lead + at) + d + core.substring(end) + tok.substring(lead + core.length)
        }
        return Result(out, count)
    }

    private fun defangPiece(p: String): String {
        val sm = scheme.find(p)
        val prefix = sm?.value ?: ""
        val rest = p.substring(prefix.length)
        val authEnd = rest.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authEnd).replace(".", "[.]").replace("@", "[@]")
        val pre = prefix.replaceFirst(Regex("(?i)^http"), "hxxp").replaceFirst(Regex("(?i)^ftp"), "fxp")
        return pre + authority + rest.substring(authEnd)
    }
}
