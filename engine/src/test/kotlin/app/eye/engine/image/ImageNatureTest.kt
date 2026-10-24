package app.eye.engine.image

import app.eye.engine.ActionVector
import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageNatureTest {
    private fun block(t: String, x: Int, y: Int) = OcrBlock(t, x, y, x + 200, y + 60)
    /** Nuvolette sparse su una tavola 1000x1000. */
    private val bubbles = listOf(
        block("Accesso anomalo rilevato", 50, 40), block("Tranquillo, e' il gatto", 600, 80), block("Rete isolata", 80, 500),
        block("Ottimo lavoro di squadra", 620, 520), block("Sistemi bonificati", 300, 850), block("Fine", 700, 900),
    )

    @Test fun comicLabelAndBubblesIsIllustration() {
        val f = ImageFacts(1000, 1000, listOf(ImageLabel("Comics", 0.8f)), bubbles)
        assertEquals(ImageNature.ILLUSTRATION, ImageNatureDetector.classify(f))
    }

    @Test fun weakLabelNeedsBubbles() {
        val weak = listOf(ImageLabel("Poster", 0.5f)) // 0.35
        assertEquals(ImageNature.ILLUSTRATION, ImageNatureDetector.classify(ImageFacts(1000, 1000, weak, bubbles)))
        assertEquals(ImageNature.OTHER, ImageNatureDetector.classify(ImageFacts(1000, 1000, weak, bubbles.take(2))))
    }

    @Test fun noLabelsIsNotIllustrationEvenWithBubbles() {
        assertEquals(ImageNature.OTHER, ImageNatureDetector.classify(ImageFacts(1000, 1000, emptyList(), bubbles)))
    }

    @Test fun chatScreenshotIsNeverIllustration() {
        val chat = listOf(block("21:47", 20, 10), block("Ciao mamma", 20, 200), block("Mi fai un bonifico?", 600, 400), block("21:49", 800, 600), block("Visualizzato", 600, 900))
        val labels = listOf(ImageLabel("Comics", 0.9f))
        assertEquals(ImageNature.MESSAGE_SCREENSHOT, ImageNatureDetector.classify(ImageFacts(1000, 1000, labels, chat)))
        assertEquals(ImageNature.MESSAGE_SCREENSHOT, ImageNatureDetector.classify(ImageFacts(1000, 1000, listOf(ImageLabel("Screenshot", 0.9f)), bubbles)))
    }

    private fun verdict(level: Level, vectors: Set<ActionVector>, urls: List<String> = emptyList()) = Verdict(
        level, 40, listOf(Reason("BAN-02", "Ti chiedono di verificare i dati", 25)), listOf("Non cliccare"), Extracted(urls = urls),
        "banca", 14, vectors,
    )

    @Test fun illustrationWithoutConcreteActionBecomesNoSignal() {
        val v = IllustrationPolicy.apply(verdict(Level.SOSPETTO, setOf(ActionVector.CREDENTIALS)), ImageNature.ILLUSTRATION, hasQr = false)
        assertEquals(Level.NESSUN_SEGNALE, v.level)
        assertEquals(ImageNatureDetector.ILLUSTRATION_NOTE, v.reasons.single().text)
        assertEquals(0, v.reasons.single().weight)
    }

    @Test fun illustrationWithLinkQrOrPhoneKeepsNormalCheck() {
        val withLink = verdict(Level.SOSPETTO, setOf(ActionVector.LINK), listOf("poste-verifica.top"))
        assertEquals(Level.SOSPETTO, IllustrationPolicy.apply(withLink, ImageNature.ILLUSTRATION, hasQr = false).level)
        assertEquals(Level.SOSPETTO, IllustrationPolicy.apply(verdict(Level.SOSPETTO, setOf(ActionVector.CREDENTIALS)), ImageNature.ILLUSTRATION, hasQr = true).level)
        assertEquals(Level.SOSPETTO, IllustrationPolicy.apply(verdict(Level.SOSPETTO, setOf(ActionVector.PHONE)), ImageNature.ILLUSTRATION, hasQr = false).level)
    }

    @Test fun otherImagesAreUntouchedAndNoSignalGetsTheNote() {
        val v = verdict(Level.SOSPETTO, setOf(ActionVector.CREDENTIALS))
        assertTrue(IllustrationPolicy.apply(v, ImageNature.OTHER, false) === v)
        val none = IllustrationPolicy.apply(verdict(Level.NESSUN_SEGNALE, emptySet()), ImageNature.ILLUSTRATION, false)
        assertTrue(none.reasons.any { it.ruleId == ImageNatureDetector.ILLUSTRATION_REASON_ID })
    }
}
