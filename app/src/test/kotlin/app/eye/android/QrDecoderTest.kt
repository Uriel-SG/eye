package app.eye.android

import app.eye.android.core.vision.QrDecoder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDecoderTest {
    private fun load(name: String): BufferedImage =
        ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/qr/$name")) { "manca $name" })

    private fun argb(img: BufferedImage): IntArray {
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return px
    }

    private fun decode(name: String): String? {
        val img = load(name)
        return QrDecoder.decodeArgb(argb(img), img.width, img.height)
    }

    @Test fun poste_lookalike_e_un_url_sospetto() {
        val raw = decode("qr2_poste_lookalike.png")
        assertNotNull(raw)
        assertTrue(raw!!, raw.startsWith("http"))
        assertTrue(raw, raw.contains("verifica-accesso-xq7k.com"))
    }

    @Test fun tutti_i_qr_del_kit_si_leggono() {
        for (n in listOf("qr1_parcheggio_truffa.png", "qr2_poste_lookalike.png", "qr3_ufficiale.png", "qr4_wifi.png")) {
            assertNotNull("non letto: $n", decode(n))
        }
    }

    @Test fun qr_wifi_resta_testo_wifi() {
        assertTrue(decode("qr4_wifi.png")!!.startsWith("WIFI:"))
    }

    @Test fun frame_camera_luminanza_con_rotazione() {
        val img = load("qr3_ufficiale.png")
        val w = img.width
        val h = img.height
        val y = ByteArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            val p = img.getRGB(c, r)
            y[r * w + c] = (((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3).toByte()
        }
        val expected = decode("qr3_ufficiale.png")
        assertEquals(expected, QrDecoder.decodeLuma(y, w, h, 0))
        // frame ruotato di 90 gradi dal sensore: la rotazione dichiarata lo raddrizza
        val rot = ByteArray(y.size)
        for (r in 0 until h) for (c in 0 until w) rot[c * h + (h - 1 - r)] = y[r * w + c]
        assertEquals(expected, QrDecoder.decodeLuma(rot, h, w, 270))
    }

    @Test fun immagine_senza_qr_da_null() {
        val img = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        assertNull(QrDecoder.decodeArgb(argb(img), 300, 300))
    }
}
