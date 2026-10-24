package app.eye.android

import app.eye.android.core.ContentKind
import app.eye.android.core.ShareTexts
import app.eye.engine.Reason
import app.eye.engine.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextsTest {
    private val sms = "+39 333 1234567: BRT: pacco in giacenza, paga 1,99 € su https://brt-consegna.com/x entro oggi"

    private fun summary(text: String = sms, source: Source? = Source.SHARE, urls: List<String> = listOf("https://brt-consegna.com/x")) =
        ShareTexts.summary(text, source, listOf("brt-consegna.com"), urls, listOf("1,99 €"))

    @Test fun summaryLineSaysWhatWasAnalyzedWithoutLeakingNumbers() {
        val s = summary()
        assertEquals("Messaggio da +39…[oscurato] con link a brt-consegna[.]com e importo 1,99 €", s.line)
        assertFalse(s.line.contains("1234567"))
        assertFalse(s.excerpt.contains("1234567"))
        assertFalse(s.excerpt.contains("https://"))
    }

    @Test fun kindsAreDetected() {
        assertEquals(ContentKind.QR, summary("x", Source.QR).kind)
        assertEquals(ContentKind.SCREENSHOT, summary("ciao", Source.OCR).kind)
        assertEquals(ContentKind.EMAIL, summary("Da: a@b.it\nOggetto: ciao\ntesto", Source.PASTE).kind)
        assertEquals(ContentKind.LINK, summary("https://x.com/a", Source.PASTE).kind)
    }

    @Test fun emailSenderIsObscured() {
        assertEquals("[oscurato]@poste-x[.]top", ShareTexts.sender("Da: Mario Rossi <mario.rossi@poste-x.top>\nOggetto: x"))
    }

    @Test fun familyTextAlwaysHasSummaryVerdictReasonsAndSteps() {
        val t = ShareTexts.familyText("Probabile truffa", summary(), listOf("Link sospetto"), listOf("Non cliccare", "Chiama la banca"))
        assertTrue(t.contains("Ho controllato con Eye: Messaggio da +39…[oscurato]"))
        assertTrue(t.contains("Verdetto: Probabile truffa."))
        assertTrue(t.contains("- Link sospetto"))
        assertTrue(t.contains("- Chiama la banca"))
        assertFalse(t.contains("1234567"))
    }

    @Test fun noReasonsGivesPrudentMessage() {
        assertTrue(ShareTexts.familyText("Nessun segnale evidente", summary(), emptyList(), listOf("a")).contains(ShareTexts.PRUDENT))
    }

    @Test fun reportRedactsUnlessFullTextRequested() {
        val args = { full: String? -> ShareTexts.report("Sospetto", "oggi", summary(), listOf("m"), listOf("s"), 7L, "disc", full) }
        assertFalse(args(null).contains("1234567"))
        assertTrue(args(null).contains("Estratto (oscurato)"))
        assertTrue(args(sms).contains("1234567"))
    }

    @Test fun feedbackEmailHasFullUnredactedDataAndMetadata() {
        val b = body(Reason("R1", "Link strano", 30))
        assertTrue(b.contains(sms))
        assertTrue(b.contains("Punteggio: 83"))
        assertTrue(b.contains("Regole scattate: R1"))
        assertTrue(b.contains("Domini estratti: brt-consegna.com"))
        assertTrue(b.contains("Sorgente: OCR"))
        assertTrue(b.contains("Versione app: 1.0.1"))
        assertTrue(b.contains("Versione regole: 12"))
    }

    private fun body(vararg r: Reason) = ShareTexts.feedbackBody(
        sms, "Probabile truffa", ShareTexts.UserView.SCAM, summary().line, 83, r.toList(), listOf("brt-consegna.com"), Source.OCR, "1.0.1", 12L, "oggi",
    )

    @Test fun mailtoUriEncodesSubjectAndBody() {
        val u = ShareTexts.mailtoUri("a@b.it", "Eye – falso allarme (x)", "riga1\nè & = ?")
        assertEquals("mailto:a@b.it?subject=Eye%20%E2%80%93%20falso%20allarme%20%28x%29&body=riga1%0A%C3%A8%20%26%20%3D%20%3F", u)
    }

    @Test fun mailtoUriTruncatesLongBodyWithNote() {
        val u = ShareTexts.mailtoUri("a@b.it", "s", "Il tuo commento:\n" + "è".repeat(5000), maxBody = 8000)
        val enc = u.substringAfter("&body=")
        assertTrue(enc.length <= 8000)
        assertTrue(enc.endsWith(ShareTexts.percentEncode(ShareTexts.TRUNCATED_NOTE)))
        assertTrue(enc.startsWith("Il%20tuo%20commento"))
        val short = ShareTexts.mailtoUri("a@b.it", "s", "breve")
        assertFalse(short.contains("%5B"))
    }

    @Test fun feedbackSubjectForEveryCombination() {
        val v = ShareTexts.UserView.entries
        for (l in listOf("Probabile truffa", "Sospetto", "Nessun segnale evidente")) {
            assertEquals("Eye – possibile truffa non riconosciuta (verdetto: $l)", ShareTexts.feedbackSubject(v[0], l))
            assertEquals("Eye – falso allarme (verdetto: $l)", ShareTexts.feedbackSubject(v[1], l))
            assertEquals("Eye – segnalazione verdetto (altro)", ShareTexts.feedbackSubject(v[2], l))
        }
    }

    @Test fun feedbackBodySectionsAreInOrder() {
        val b = body(Reason("R1", "Link strano", 30), Reason("AI-01", "Il classificatore alza il rischio", 20))
        val idx = listOf(
            ShareTexts.COMMENT_LABEL, "Analizzato: Messaggio da", "Verdetto di Eye: Probabile truffa", "Secondo te il messaggio è: Una truffa",
            "Motivi principali:", "- Link strano", "Motivo del classificatore AI: Il classificatore alza", ShareTexts.TECH_SEPARATOR,
            ShareTexts.TECH_LABEL, "Punteggio: 83", "Messaggio analizzato:",
        ).map { b.indexOf(it).also { i -> assertTrue("manca: $it", i >= 0) } }
        assertEquals(idx.sorted(), idx)
        assertTrue(b.lines().take(4).all { it.isEmpty() || it == ShareTexts.COMMENT_LABEL })
        assertTrue(b.endsWith(ShareTexts.REPORT_END))
    }

    @Test fun safeOutgoingDefangsAndAddsNoteOnlyWhenNeeded() {
        val out = ShareTexts.safeOutgoing(sms)
        assertTrue(out.contains("hxxps://brt-consegna[.]com/x"))
        assertTrue(out.endsWith("(link resi non cliccabili per sicurezza)"))
        assertEquals(out, ShareTexts.safeOutgoing(out))
        val plain = "Ciao, ci vediamo alle 18.30"
        assertEquals(plain, ShareTexts.safeOutgoing(plain))
    }

    @Test fun reportBlockIsMachineReadableAndAnonymized() {
        val txt = "Gentile Mario Rossi, chiama +39 333 1234567 o scrivi a mario@x.it, IBAN IT60X0542811101000000123456. Vai su https://brt-consegna.com/x"
        val b = ShareTexts.feedbackBody(txt, "Sospetto", ShareTexts.UserView.LEGIT, "Messaggio", 40, listOf(Reason("R1", "m", 10)), listOf("brt-consegna.com"), Source.SHARE, "1.7.0", 5L, "oggi")
        val block = b.substringAfter(ShareTexts.REPORT_BEGIN + "\n").substringBefore("\n" +ShareTexts.REPORT_END)
        assertTrue(b.contains(ShareTexts.CONSENT_NOTE))
        assertTrue(block.startsWith("{") && block.endsWith("}"))
        assertTrue(block.contains("\"user\":\"false_positive\""))
        assertTrue(block.contains("\"ruleIds\":[\"R1\"]"))
        assertTrue(block, block.contains("[nome]"))
        for (pii in listOf("Mario", "Rossi", "1234567", "mario@x.it", "IT60X05428", "https://brt")) assertFalse(pii, block.contains(pii))
    }
}
