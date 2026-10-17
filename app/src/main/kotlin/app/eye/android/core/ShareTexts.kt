package app.eye.android.core

import app.eye.engine.Reason
import app.eye.engine.Source

/** Tipo di contenuto analizzato, per dire all'utente (e al destinatario) COSA è stato controllato. */
enum class ContentKind(val label: String) {
    MESSAGE("Messaggio"), EMAIL("Email"), LINK("Link"), QR("Codice QR"), SCREENSHOT("Screenshot"),
}

/** Sintesi di ciò che è stato analizzato: mittente oscurato, domini defanged, estratto oscurato. */
data class AnalyzedSummary(
    val kind: ContentKind,
    val sender: String?,
    val domains: List<String>,
    val amount: String?,
    val excerpt: String,
) {
    /** Es. «Messaggio da +39…[oscurato] con link a brt-consegna[.]com e importo 1,99 €». */
    val line: String
        get() = buildString {
            append(kind.label)
            sender?.let { append(" da ").append(it) }
            if (domains.isNotEmpty()) {
                val d = domains.joinToString(", ")
                append(if (kind == ContentKind.LINK || kind == ContentKind.QR) " verso " else " con link a ").append(d)
            }
            amount?.let { append(if (domains.isNotEmpty()) " e importo " else " con importo ").append(it) }
        }
}

/** Costruzione dei testi di condivisione, report e segnalazione. Puro Kotlin, testabile su JVM. */
object ShareTexts {
    const val OBSCURED = "[oscurato]"
    const val FOOTER = "Controllato con Eye"
    const val PRUDENT = "Non abbiamo riconosciuto segnali noti, ma non è una garanzia. Se non aspettavi questo messaggio, non cliccare e verifica dai canali ufficiali."
    const val REPORT_INTRO = "Segnalazione di un verdetto di Eye che ritengo sbagliato. Il messaggio qui sotto è completo e non oscurato: prima di inviare togli i dati personali che non vuoi condividere."

    private val header = Regex("""(?im)^\s*(?:da|from|mittente)\s*:\s*(.+)$""")
    private val subjectHeader = Regex("""(?im)^\s*(?:oggetto|subject)\s*:""")
    private val leadingPhone = Regex("""^\s*(\+?\d[\d ]{6,18}\d)\s*[:\-]""")
    private val emailIn = Regex("""[^\s<>@"']+@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})""")

    fun kindOf(text: String, source: Source?, hasSingleUrl: Boolean): ContentKind = when {
        source == Source.QR || text.contains("[QR]") -> ContentKind.QR
        source == Source.OCR -> ContentKind.SCREENSHOT
        subjectHeader.containsMatchIn(text.take(600)) -> ContentKind.EMAIL
        hasSingleUrl && text.trim().none { it.isWhitespace() } -> ContentKind.LINK
        else -> ContentKind.MESSAGE
    }

    /** Mittente se riconoscibile, sempre oscurato (numeri e parte locale delle email). */
    fun sender(text: String): String? {
        val head = text.take(600)
        header.find(head)?.groupValues?.get(1)?.trim()?.let { v ->
            emailIn.find(v)?.let { return "$OBSCURED@" + Redactor.defang(it.groupValues[1].lowercase()) }
            maskPhone(v)?.let { return it }
            val clean = Redactor.redact(v).take(30).trim()
            if (clean.isNotEmpty() && v.length <= 30 && !clean.contains('[')) return clean
        }
        leadingPhone.find(head)?.let { return maskPhone(it.groupValues[1]) }
        return null
    }

    private fun maskPhone(v: String): String? {
        val t = v.trim()
        val digits = t.filter { it.isDigit() }
        if (digits.length < 7 || t.any { it.isLetter() }) return null
        return if (t.startsWith("+")) "+" + digits.take(2) + "…" + OBSCURED else "[numero oscurato]"
    }

    fun summary(text: String, source: Source?, domains: List<String>, urls: List<String>, amounts: List<String>): AnalyzedSummary =
        AnalyzedSummary(
            kind = kindOf(text, source, urls.size == 1),
            sender = sender(text),
            domains = domains.distinct().take(3).map(Redactor::defang),
            amount = amounts.firstOrNull(),
            excerpt = Redactor.preview(text, 160),
        )

    /** Testo per «Manda a un familiare»: cosa è stato analizzato, verdetto, motivi, cosa fare. */
    fun familyText(title: String, s: AnalyzedSummary, reasons: List<String>, steps: List<String>): String = buildString {
        appendLine("Ho controllato con Eye: ${s.line}.")
        appendLine("Verdetto: $title.")
        appendLine()
        appendLine("Motivi:")
        if (reasons.isEmpty()) appendLine(PRUDENT) else reasons.take(3).forEach { appendLine("- $it") }
        appendLine()
        appendLine("Cosa fare:")
        steps.take(2).forEach { appendLine("- $it") }
        appendLine()
        append(FOOTER)
    }

    fun familySubject(title: String, s: AnalyzedSummary): String = "Eye – $title: ${s.line}".take(120)

    /** Report copiabile. Il testo originale compare solo se [fullText] è non nullo (scelta esplicita). */
    fun report(
        title: String, date: String, s: AnalyzedSummary, reasons: List<String>, steps: List<String>,
        rulesVersion: Long, disclaimer: String, fullText: String?,
    ): String = buildString {
        appendLine("Report Eye")
        appendLine("Data: $date")
        appendLine("Analizzato: ${s.line}")
        appendLine("Verdetto: $title")
        appendLine()
        appendLine(if (fullText != null) "Messaggio completo: " + fullText.take(2000) else "Estratto (oscurato): " + s.excerpt)
        appendLine()
        appendLine("Perché:")
        if (reasons.isEmpty()) appendLine(PRUDENT) else reasons.forEach { appendLine("- $it") }
        if (s.domains.isNotEmpty()) appendLine("Domini: " + s.domains.joinToString(", "))
        appendLine()
        appendLine("Cosa fare ora:")
        steps.forEachIndexed { i, st -> appendLine("${i + 1}. $st") }
        appendLine()
        appendLine("Versione regole: $rulesVersion")
        appendLine(disclaimer)
        append(FOOTER)
    }

    /** Cosa pensa l'utente del messaggio («Secondo te questo messaggio è…»). */
    enum class UserView(val label: String) { SCAM("Una truffa"), LEGIT("Un messaggio legittimo"), OTHER("Altro") }

    fun feedbackSubject(view: UserView, levelTitle: String): String = when (view) {
        UserView.SCAM -> "Eye – possibile truffa non riconosciuta (verdetto: $levelTitle)"
        UserView.LEGIT -> "Eye – falso allarme (verdetto: $levelTitle)"
        UserView.OTHER -> "Eye – segnalazione verdetto (altro)"
    }

    fun sourceLabel(source: Source?): String = when (source) {
        Source.SHARE -> "condiviso (o notifica)"
        Source.PASTE -> "incollato"
        Source.QR -> "QR"
        Source.OCR -> "OCR (immagine)"
        null -> "non disponibile"
    }

    const val TRUNCATED_NOTE = "\n[messaggio troncato: il testo completo è negli appunti]"
    const val MAX_MAILTO_BODY = 8000

    /** Percent-encoding UTF-8 (Uri.encode non è disponibile nei test JVM); a capo diventa %0A. */
    fun percentEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    /** mailto: con oggetto e corpo codificati; se il corpo codificato supera [maxBody] taglia la coda e aggiunge una nota. */
    fun mailtoUri(to: String, subject: String, body: String, maxBody: Int = MAX_MAILTO_BODY): String {
        var text = body.replace("\r\n", "\n")
        if (percentEncode(text).length > maxBody) {
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (percentEncode(text.substring(0, mid) + TRUNCATED_NOTE).length <= maxBody) lo = mid else hi = mid - 1
            }
            if (lo > 0 && text[lo - 1].isHighSurrogate()) lo--
            text = text.substring(0, lo) + TRUNCATED_NOTE
        }
        return "mailto:$to?subject=${percentEncode(subject)}&body=${percentEncode(text)}"
    }

    const val COMMENT_LABEL = "Il tuo commento:"
    const val TECH_LABEL = "Dettagli tecnici"
    const val TECH_SEPARATOR = "----------------------------------------"

    /** Corpo dell'email: commento da compilare, sintesi leggibile, poi dettagli tecnici con il testo completo. */
    fun feedbackBody(
        fullText: String, levelTitle: String, view: UserView, summaryLine: String, score: Int, reasons: List<Reason>,
        domains: List<String>, source: Source?, appVersion: String, rulesVersion: Long, date: String,
    ): String = buildString {
        appendLine(COMMENT_LABEL)
        appendLine()
        appendLine()
        appendLine()
        appendLine("Analizzato: $summaryLine")
        appendLine("Verdetto di Eye: $levelTitle")
        appendLine("Secondo te il messaggio è: ${view.label}")
        val shown = reasons.filter { it.text.isNotBlank() }
        val ai = shown.filter { it.ruleId.startsWith("AI-") }
        val main = shown - ai.toSet()
        if (main.isNotEmpty()) {
            appendLine("Motivi principali:")
            main.take(3).forEach { appendLine("- ${it.text}") }
        }
        ai.forEach { appendLine("Motivo del classificatore AI: ${it.text}") }
        appendLine()
        appendLine(TECH_SEPARATOR)
        appendLine(TECH_LABEL)
        appendLine(REPORT_INTRO)
        appendLine()
        appendLine("Punteggio: $score")
        appendLine("Regole scattate: " + reasons.joinToString { it.ruleId }.ifBlank { "-" })
        appendLine("Domini estratti: " + domains.distinct().joinToString(", ").ifBlank { "-" })
        appendLine("Sorgente: ${sourceLabel(source)}")
        appendLine("Versione app: $appVersion")
        appendLine("Versione regole: $rulesVersion")
        appendLine("Data: $date")
        appendLine()
        appendLine("Messaggio analizzato:")
        append(fullText)
    }
}
