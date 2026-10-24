package app.eye.engine.image

import app.eye.engine.ActionVector
import app.eye.engine.AlertTier
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict

/** Etichetta dell'immagine (classificatore sul telefono) con confidenza 0..1. */
data class ImageLabel(val text: String, val confidence: Float)

/** Blocco di testo letto dall'OCR con il suo rettangolo (pixel dell'immagine). */
data class OcrBlock(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Fatti letti dall'immagine, tutti sul telefono: dimensioni, etichette, blocchi di testo, presenza di un QR. */
data class ImageFacts(
    val width: Int,
    val height: Int,
    val labels: List<ImageLabel> = emptyList(),
    val blocks: List<OcrBlock> = emptyList(),
    val hasQr: Boolean = false,
)

enum class ImageNature {
    /** Fumetto, vignetta, illustrazione, poster: il testo e' un racconto, non un messaggio rivolto a chi guarda. */
    ILLUSTRATION,
    /** Screenshot di una chat, di una notifica o di una pagina: il testo va controllato normalmente. */
    MESSAGE_SCREENSHOT,
    OTHER,
}

/**
 * Riconoscimento (euristico, mai certo) di fumetti e illustrazioni da etichette dell'immagine + layout dell'OCR.
 * Le etichette del modello di base ML Kit non hanno «Cartoon» o «Illustration»: i fumetti compaiono come «Comics»,
 * «Fiction» (personaggi di fantasia), «Mascot», «Poster», «Superhero», giocattoli/pupazzi. Soglie da validare su casi reali.
 */
object ImageNatureDetector {
    /** Etichetta (minuscola) -> peso: quanto indica un fumetto/illustrazione. */
    private val COMIC_LABELS = mapOf(
        "comics" to 1.0f, "fiction" to 0.9f, "mascot" to 0.9f, "superhero" to 0.9f, "supervillain" to 0.9f, "superman" to 0.9f,
        "poster" to 0.7f, "toy" to 0.6f, "doll" to 0.6f, "stuffed toy" to 0.6f, "plush" to 0.6f, "lego" to 0.6f,
        "dragon" to 0.6f, "dinosaur" to 0.6f, "clown" to 0.6f, "joker" to 0.6f, "cartoon" to 1.0f, "illustration" to 1.0f, "animation" to 0.9f,
    )
    private val SCREENSHOT_LABELS = setOf("screenshot", "web page", "mobile phone")
    private val clockOnly = Regex("^\\s*\\d{1,2}[:.]\\d{2}\\s*$")
    private val statusBar = Regex("\\b(?:lte|5g|4g|3g|wi-?fi)\\b|\\b\\d{1,3}\\s?%", RegexOption.IGNORE_CASE)

    const val ILLUSTRATION_NOTE = "L'immagine sembra un fumetto o un'illustrazione: il testo è stato valutato come racconto."
    const val ILLUSTRATION_REASON_ID = "IMG_ILLUSTRATION"
    const val NO_TEXT_REASON_ID = "IMG_NO_TEXT"
    const val NO_TEXT_NOTE = "Nessun testo da analizzare nell'immagine."

    fun comicScore(labels: List<ImageLabel>): Float =
        labels.maxOfOrNull { (COMIC_LABELS[it.text.trim().lowercase()] ?: 0f) * it.confidence } ?: 0f

    private fun screenshotScore(labels: List<ImageLabel>): Float =
        labels.filter { it.text.trim().lowercase() in SCREENSHOT_LABELS }.maxOfOrNull { it.confidence } ?: 0f

    /** Orari isolati e barre di stato (LTE, Wi-Fi, 40%): segno di uno screenshot di telefono. */
    private fun chatMarkers(blocks: List<OcrBlock>): Int =
        blocks.count { clockOnly.matches(it.text) || (it.text.length <= 20 && statusBar.containsMatchIn(it.text)) }

    /** Blocchi brevi sparsi su piu' colonne e righe: nuvolette e didascalie di un fumetto. */
    internal fun scatteredShortBlocks(f: ImageFacts): Int {
        if (f.width <= 0 || f.height <= 0) return 0
        val short = f.blocks.filter { it.text.trim().length in 3..90 }
        if (short.size < 4) return 0
        val xs = short.map { (it.left + it.right) / 2f / f.width }
        val ys = short.map { (it.top + it.bottom) / 2f / f.height }
        fun spread(v: List<Float>) = (v.maxOrNull() ?: 0f) - (v.minOrNull() ?: 0f)
        return if (spread(xs) >= 0.35f && spread(ys) >= 0.35f) short.size else 0
    }

    fun classify(f: ImageFacts): ImageNature {
        val comic = comicScore(f.labels)
        val shot = screenshotScore(f.labels)
        val chatLike = chatMarkers(f.blocks) >= 2 || (shot >= 0.5f && comic < 0.6f)
        if (chatLike) return ImageNature.MESSAGE_SCREENSHOT
        val bubbles = scatteredShortBlocks(f)
        return when {
            comic >= 0.55f -> ImageNature.ILLUSTRATION
            comic >= 0.30f && bubbles >= 5 -> ImageNature.ILLUSTRATION
            else -> ImageNature.OTHER
        }
    }
}

/**
 * Fumetto/illustrazione: se non c'e' nulla di concreto da fare (nessun link, numero, QR, email, installazione) il testo e' un
 * racconto e il livello e' NESSUN_SEGNALE, con una nota. Con link/QR/numero il controllo resta normale.
 */
object IllustrationPolicy {
    private val CONCRETE = setOf(ActionVector.LINK, ActionVector.LINK_OFFICIAL, ActionVector.LINK_BARE, ActionVector.PHONE, ActionVector.EMAIL, ActionVector.INSTALL)

    fun apply(v: Verdict, nature: ImageNature, hasQr: Boolean): Verdict {
        if (nature != ImageNature.ILLUSTRATION || hasQr) return v
        val concrete = v.extracted.urls.isNotEmpty() || v.extracted.phones.isNotEmpty() || v.extracted.ibans.isNotEmpty() || v.vectors.any { it in CONCRETE }
        if (concrete) return v
        val note = Reason(ImageNatureDetector.ILLUSTRATION_REASON_ID, ImageNatureDetector.ILLUSTRATION_NOTE, 0)
        if (v.level == Level.NESSUN_SEGNALE) return v.copy(reasons = v.reasons + note)
        return v.copy(
            level = Level.NESSUN_SEGNALE, score = 0, reasons = listOf(note), alertTier = AlertTier.NONE, category = null,
            actions = emptyList(),
        )
    }
}
