package app.eye.engine.provenance


/**
 * Provenienza dichiarata di un'immagine, letta SOLO dai metadati (nessuna rete, nessuna verifica crittografica):
 * C2PA / Content Credentials (JPEG APP11, PNG caBX, WebP "C2PA", ricerca di ripiego per altri contenitori), XMP/IPTC
 * DigitalSourceType, EXIF Software/Make/Model. Mai un verdetto di autenticita': riporta cio' che il file DICHIARA.
 */
enum class ProvenanceKind { AI_DECLARED, C2PA_NO_AI, NONE }

data class ProvenanceReport(
    val kind: ProvenanceKind,
    /** Strumento noto (es. "Midjourney") o nome del claim_generator C2PA, se presente. */
    val tool: String? = null,
    /** Il segno AI viene da un manifest C2PA (altrimenti da XMP/EXIF). */
    val fromC2pa: Boolean = false,
)

object ImageProvenance {
    /** Limite di lettura: solo l'inizio del file, dove stanno i metadati. */
    const val MAX_BYTES = 512 * 1024
    const val SYNTHID_URL = "https://synthid.com"

    /** DigitalSourceType che indicano contenuto generato o composto da AI / algoritmi generativi. */
    internal val AI_SOURCE_TYPES = setOf("trainedalgorithmicmedia", "compositewithtrainedalgorithmicmedia", "algorithmicmedia", "compositesynthetic")

    /** Strumenti AI noti: frammento minuscolo -> nome mostrato. Elenco breve, da aggiornare. */
    private val AI_TOOLS = listOf(
        "midjourney" to "Midjourney", "dall-e" to "DALL-E", "dalle" to "DALL-E", "openai" to "OpenAI", "chatgpt" to "ChatGPT",
        "stable diffusion" to "Stable Diffusion", "stablediffusion" to "Stable Diffusion", "dreamstudio" to "DreamStudio",
        "firefly" to "Adobe Firefly", "imagen" to "Google Imagen", "gemini" to "Google Gemini", "nano banana" to "Google Gemini",
        "leonardo.ai" to "Leonardo AI", "ideogram" to "Ideogram", "copilot designer" to "Microsoft Designer",
        "bing image creator" to "Bing Image Creator", "stability.ai" to "Stability AI",
    )

    fun analyze(input: ByteArray): ProvenanceReport = try {
        analyzeImpl(if (input.size > MAX_BYTES) input.copyOf(MAX_BYTES) else input)
    } catch (_: Throwable) {
        ProvenanceReport(ProvenanceKind.NONE)
    }

    private fun analyzeImpl(b: ByteArray): ProvenanceReport {
        val c = Containers.extract(b)
        val c2pa = c.jumbf.map { C2pa.read(it) }
        val c2paPresent = c.jumbf.isNotEmpty()
        val xmpText = c.xmp.joinToString("\n")
        val exif = c.exif.mapNotNull { Exif.read(it) }
        val exifText = exif.flatMap { listOf(it.software, it.make, it.model) }.filterNotNull().joinToString("\n")

        val c2paAi = c2pa.any { it.aiSource } || c2pa.any { it.generator?.let(::toolIn) != null }
        val xmpAi = sourceTypes(xmpText).any { it in AI_SOURCE_TYPES }
        val textTool = toolIn(xmpText) ?: toolIn(exifText)

        val tool = c2pa.firstNotNullOfOrNull { it.generator?.let(::toolIn) } ?: textTool
        return when {
            c2paAi -> ProvenanceReport(ProvenanceKind.AI_DECLARED, tool ?: c2pa.firstNotNullOfOrNull { it.generator }?.takeIf { c2pa.any { m -> m.aiSource } }, fromC2pa = true)
            xmpAi || textTool != null -> ProvenanceReport(ProvenanceKind.AI_DECLARED, tool, fromC2pa = false)
            c2paPresent -> ProvenanceReport(ProvenanceKind.C2PA_NO_AI, c2pa.firstNotNullOfOrNull { it.generator }, fromC2pa = true)
            else -> ProvenanceReport(ProvenanceKind.NONE)
        }
    }

    /** Valori DigitalSourceType (ultimo segmento dell'URI, minuscolo) trovati in [text]. */
    internal fun sourceTypes(text: String): List<String> {
        val out = ArrayList<String>()
        val key = "digitalsourcetype/"
        val lower = text.lowercase()
        var i = lower.indexOf(key)
        while (i >= 0) {
            var j = i + key.length
            while (j < lower.length && lower[j].isLetter()) j++
            out += lower.substring(i + key.length, j)
            i = lower.indexOf(key, j)
        }
        return out
    }

    internal fun toolIn(text: String): String? {
        val t = text.lowercase()
        return AI_TOOLS.firstOrNull { (frag, _) -> containsWord(t, frag) }?.second
    }

    private fun containsWord(t: String, w: String): Boolean {
        var i = t.indexOf(w)
        while (i >= 0) {
            val before = i == 0 || !t[i - 1].isLetterOrDigit()
            val end = i + w.length
            val after = end >= t.length || !t[end].isLetterOrDigit()
            if (before && after) return true
            i = t.indexOf(w, i + 1)
        }
        return false
    }
}

/** Testi mostrati all'utente: professionali, impersonali, mai «autentica/vera/sicura». */
object ProvenanceMessages {
    const val REASON_ID = "IMG_PROVENANCE_AI"

    fun body(r: ProvenanceReport): String = when (r.kind) {
        ProvenanceKind.AI_DECLARED -> {
            val src = if (r.fromC2pa) "Content Credentials" else "metadati"
            val tool = r.tool?.let { ": $it" } ?: ""
            "L'immagine dichiara di essere stata creata o modificata con l'intelligenza artificiale (fonte: $src$tool)."
        }
        ProvenanceKind.C2PA_NO_AI ->
            "L'immagine contiene informazioni di provenienza (Content Credentials) che non indicano l'uso dell'intelligenza artificiale. " +
                "Dichiarano l'origine del file, ma non garantiscono che il contenuto corrisponda al vero."
        ProvenanceKind.NONE ->
            "Nessuna informazione di provenienza trovata. Questo non significa che l'immagine sia autentica: " +
                "le app di messaggistica come WhatsApp rimuovono spesso questi dati."
    }

    /** Motivo informativo (peso 0) da aggiungere al verdetto solo se l'immagine dichiara l'uso di AI. */
    fun reasonText(r: ProvenanceReport): String? = if (r.kind == ProvenanceKind.AI_DECLARED) body(r) else null

    const val VIDEO_ADVICE =
        "I video possono essere alterati o generati con l'intelligenza artificiale e Eye non li analizza. " +
            "Se il video chiede denaro o dati, richiama la persona su un numero che gia' conosci e concorda in famiglia una parola segreta da usare nei casi dubbi."
}
