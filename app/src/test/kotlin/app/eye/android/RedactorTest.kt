package app.eye.android

import app.eye.android.core.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private fun hostile(unit: String) = unit.repeat(10_000 / unit.length)

    @Test fun hostileInputsDoNotCrashAndAreFast() {
        val start = System.nanoTime()
        listOf("a.", "a.a.a.", "1 ", "1.2.3.", "a@", "- ", ".", "http://", "aa11 ", "x".repeat(10_000), "1-".repeat(5_000), "ab12 ".repeat(2_000)).forEach {
            val out = Redactor.redact(hostile(it).take(10_000))
            assertTrue(out.isNotEmpty())
            Redactor.preview(hostile(it))
        }
        assertTrue("troppo lento", (System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    @Test fun redactsPersonalData() {
        fun hidden(s: String, secret: String) = assertFalse("$secret in: ${Redactor.redact(s)}", Redactor.redact(s).contains(secret))
        hidden("codice OTP 482913 subito", "482913")
        hidden("il PIN 1234", "1234")
        hidden("scrivi a mario.rossi@azienda.eu", "mario")
        hidden("CF RSSMRA80A01H501U", "RSSMRA")
        hidden("iban it60x0542811101000000123456 grazie", "0542811")
        hidden("IBAN DE89 3704 0044 0532 0130 00", "3704")
        hidden("carta 4111 1111 1111 1111", "4111")
        hidden("vai su poste-verifica.app/login ora", "poste-verifica")
        hidden("vai su bancaintesa-sicura.eu.", "bancaintesa")
        hidden("chiama +39 333 1234567", "1234567")
    }

    @Test fun keepsOrdinaryText() {
        assertEquals("Ciao come stai? Ho 3 gatti.", Redactor.redact("Ciao come stai? Ho 3 gatti."))
        assertTrue(Redactor.redact("vai su https://x.top/a").contains(Redactor.LINK))
    }

    @Test fun defangDomain() = assertEquals("a[.]com", Redactor.defang("a.com"))
}
