package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkDefangTest {
    private fun d(s: String) = LinkDefang.apply(s)

    @Test fun withScheme() {
        val r = d("Apri https://poste-x.top/login ora")
        assertEquals("Apri hxxps://poste-x[.]top/login ora", r.text)
        assertEquals(1, r.count)
    }

    @Test fun httpAndTrailingPunctuation() {
        assertEquals("Vai su (hxxp://evil[.]top/a.html).", d("Vai su (http://evil.top/a.html).").text)
    }

    @Test fun noScheme() {
        assertEquals("vai su bit[.]ly/abc", d("vai su bit.ly/abc").text)
        assertEquals("poste-x[.]top/login", d("poste-x.top/login").text)
    }

    @Test fun www() {
        assertEquals("www[.]esempio[.]it/x", d("www.esempio.it/x").text)
    }

    @Test fun punycode() {
        assertEquals("hxxps://xn--psta-bsa[.]com/", d("https://xn--psta-bsa.com/").text)
    }

    @Test fun multipleLinks() {
        val r = d("a http://uno.top e www.due.it/p, poi bit.ly/z")
        assertEquals("a hxxp://uno[.]top e www[.]due[.]it/p, poi bit[.]ly/z", r.text)
        assertEquals(3, r.count)
    }

    @Test fun plainTextAndEmailUnchanged() {
        val t = "Ciao, scrivimi a mario.rossi@example.com entro le 18.30. Costa 1,99 euro."
        val r = d(t)
        assertEquals(t, r.text)
        assertEquals(0, r.count)
    }

    @Test fun userInfoAtIsDefanged() {
        assertEquals("hxxps://banca[.]it[@]evil[.]top/x", d("https://banca.it@evil.top/x").text)
        assertTrue(d("https://banca.it@evil.top/x").text.contains("[@]"))
    }

    @Test fun idempotent() {
        val once = d("Apri https://poste-x.top/login e bit.ly/abc").text
        val twice = d(once)
        assertEquals(once, twice.text)
        assertEquals(0, twice.count)
    }
}
