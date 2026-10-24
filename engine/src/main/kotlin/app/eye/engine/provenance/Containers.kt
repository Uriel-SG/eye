package app.eye.engine.provenance

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** Blocchi di metadati estratti da un'immagine; ogni lettura e' limitata al buffer e non lancia su dati malformati. */
internal class Parts(
    /** Payload C2PA/JUMBF (uno per immagine; vuoto se assente). */
    val jumbf: List<ByteArray>,
    /** Testi XMP (o testi PNG rilevanti) in chiaro. */
    val xmp: List<String>,
    /** Blocchi EXIF (TIFF, con o senza prefisso "Exif\0\0"). */
    val exif: List<ByteArray>,
)

internal object Containers {
    private val C2PA = "c2pa".toByteArray(Charsets.ISO_8859_1)
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    fun extract(b: ByteArray): Parts {
        val jumbf = ArrayList<ByteArray>(); val xmp = ArrayList<String>(); val exif = ArrayList<ByteArray>()
        when {
            b.size > 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 -> jpeg(b, jumbf, xmp, exif)
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> png(b, jumbf, xmp, exif)
            b.size > 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> webp(b, jumbf, xmp, exif)
            else -> fallback(b, jumbf, xmp)
        }
        return Parts(jumbf, xmp, exif)
    }

    private fun jpeg(b: ByteArray, jumbf: MutableList<ByteArray>, xmp: MutableList<String>, exif: MutableList<ByteArray>) {
        var p = 2
        val app11 = ByteArrayOutputStream()
        while (p + 4 <= b.size) {
            if (u8(b, p) != 0xFF) break
            val m = u8(b, p + 1)
            if (m == 0xFF) { p++; continue }
            if (m == 0xD9 || m == 0xDA) break // EOI / SOS: i metadati finiscono qui
            if (m in 0xD0..0xD8 || m == 0x01) { p += 2; continue }
            val len = (u8(b, p + 2) shl 8) or u8(b, p + 3)
            if (len < 2) break
            val s = p + 4
            val e = minOf(p + 2 + len, b.size)
            if (m == 0xE1 && e > s) {
                when {
                    startsWith(b, s, e, "Exif\u0000\u0000") -> exif += b.copyOfRange(s + 6, e)
                    startsWith(b, s, e, XMP_NS) -> xmp += String(b, s + XMP_NS.length, e - s - XMP_NS.length, Charsets.UTF_8)
                }
            } else if (m == 0xEB && e > s) app11.write(b, s, e - s)
            p += 2 + len
        }
        val j = app11.toByteArray()
        if (indexOf(j, C2PA) >= 0) jumbf += j
    }

    private fun png(b: ByteArray, jumbf: MutableList<ByteArray>, xmp: MutableList<String>, exif: MutableList<ByteArray>) {
        var p = 8
        while (p + 12 <= b.size) {
            val len = be32(b, p)
            if (len < 0) break
            val type = ascii(b, p + 4, 4)
            val s = p + 8
            val e = s.toLong() + len
            if (e > b.size) break
            val end = e.toInt()
            when (type) {
                "caBX" -> if (indexOf(b.copyOfRange(s, end), C2PA) >= 0) jumbf += b.copyOfRange(s, end)
                "eXIf" -> exif += b.copyOfRange(s, end)
                "iTXt" -> pngItxt(b, s, end)?.let { (k, v) -> pngText(k, v, xmp) }
                "tEXt" -> {
                    val z = (s until end).firstOrNull { b[it].toInt() == 0 }
                    if (z != null) pngText(String(b, s, z - s, Charsets.ISO_8859_1), String(b, z + 1, end - z - 1, Charsets.ISO_8859_1), xmp)
                }
            }
            p = end + 4
        }
    }

    /** Estrae (keyword, testo) da un chunk iTXt, decomprimendo zlib se necessario (limitato a 256 KB). */
    private fun pngItxt(b: ByteArray, s: Int, e: Int): Pair<String, String>? {
        var p = s
        val z = (p until e).firstOrNull { b[it].toInt() == 0 } ?: return null
        val key = String(b, p, z - p, Charsets.ISO_8859_1)
        p = z + 1
        if (p + 2 > e) return null
        val compressed = b[p].toInt() == 1
        p += 2
        repeat(2) { // lingua e keyword tradotta, terminate da NUL
            val n = (p until e).firstOrNull { b[it].toInt() == 0 } ?: return null
            p = n + 1
        }
        if (p > e) return null
        val raw = b.copyOfRange(p, e)
        val data = if (compressed) inflate(raw) ?: return null else raw
        return key to String(data, Charsets.UTF_8)
    }

    private fun pngText(key: String, value: String, xmp: MutableList<String>) {
        when {
            key == "XML:com.adobe.xmp" -> xmp += value
            key.equals("Software", true) -> xmp += "Software: $value"
            // Stable Diffusion (WebUI/Forge) scrive i parametri di generazione in un chunk "parameters"
            key.equals("parameters", true) && value.contains("Steps:") && value.contains("Sampler") -> xmp += "Software: Stable Diffusion"
        }
    }

    private fun webp(b: ByteArray, jumbf: MutableList<ByteArray>, xmp: MutableList<String>, exif: MutableList<ByteArray>) {
        var p = 12
        while (p + 8 <= b.size) {
            val type = ascii(b, p, 4)
            val len = le32(b, p + 4)
            if (len < 0) break
            val s = p + 8
            val e = s.toLong() + len
            if (e > b.size) break
            val end = e.toInt()
            when (type) {
                "XMP " -> xmp += String(b, s, end - s, Charsets.UTF_8)
                "EXIF" -> exif += b.copyOfRange(s, end)
                "C2PA" -> jumbf += b.copyOfRange(s, end)
            }
            p = end + (len and 1)
        }
    }

    /** HEIC/AVIF/altro: ricerca testuale del pacchetto XMP e della firma C2PA nel buffer. */
    private fun fallback(b: ByteArray, jumbf: MutableList<ByteArray>, xmp: MutableList<String>) {
        val a = indexOf(b, "<x:xmpmeta".toByteArray())
        if (a >= 0) {
            val endTag = "</x:xmpmeta>".toByteArray()
            val z = indexOf(b, endTag, a).let { if (it < 0) b.size else it + endTag.size }
            xmp += String(b, a, z - a, Charsets.UTF_8)
        }
        if (indexOf(b, "jumb".toByteArray()) >= 0 && indexOf(b, C2PA) >= 0) jumbf += b
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(raw)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished() && out.size() < 256 * 1024) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) { null } finally { inf.end() }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le32(b: ByteArray, i: Int) = (u8(b, i + 3) shl 24) or (u8(b, i + 2) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i)
    private fun ascii(b: ByteArray, i: Int, n: Int) = String(b, i, n, Charsets.ISO_8859_1)
    private fun startsWith(b: ByteArray, s: Int, e: Int, prefix: String): Boolean =
        e - s >= prefix.length && prefix.indices.all { b[s + it].toInt() == prefix[it].code }

    fun indexOf(b: ByteArray, pat: ByteArray, from: Int = 0): Int {
        if (pat.isEmpty()) return from
        var i = from
        while (i <= b.size - pat.size) {
            var k = 0
            while (k < pat.size && b[i + k] == pat[k]) k++
            if (k == pat.size) return i
            i++
        }
        return -1
    }
}
