package app.eye.engine.provenance

/** Lettura minimale di un manifest C2PA (JUMBF/CBOR): solo cio' che il file dichiara, senza verifica di firme. */
internal class C2paInfo(val aiSource: Boolean, val generator: String?)

internal object C2pa {
    fun read(jumbf: ByteArray): C2paInfo {
        val text = String(jumbf, Charsets.ISO_8859_1)
        val ai = ImageProvenance.sourceTypes(text).any { it in ImageProvenance.AI_SOURCE_TYPES }
        return C2paInfo(ai, generator(jumbf))
    }

    /** claim_generator_info.name (v2) oppure claim_generator (v1): testo CBOR subito dopo la chiave. */
    private fun generator(b: ByteArray): String? {
        val info = Containers.indexOf(b, "claim_generator_info".toByteArray())
        if (info >= 0) {
            val n = Containers.indexOf(b, "name".toByteArray(), info + 20)
            if (n in 0..(info + 200)) cborText(b, n + 4)?.let { return it }
        }
        val g = Containers.indexOf(b, "claim_generator".toByteArray())
        if (g >= 0) cborText(b, g + 15)?.let { return it }
        return null
    }

    /** Stringa CBOR (major type 3) con lunghezza breve a [pos]; null se non e' una stringa plausibile. */
    private fun cborText(b: ByteArray, pos: Int): String? {
        if (pos >= b.size) return null
        val h = b[pos].toInt() and 0xFF
        if (h shr 5 != 3) return null
        var len = h and 0x1F
        var s = pos + 1
        if (len == 24) { if (s >= b.size) return null; len = b[s].toInt() and 0xFF; s++ } else if (len > 24) return null
        if (len == 0 || len > 120 || s + len > b.size) return null
        val t = String(b, s, len, Charsets.UTF_8)
        return t.takeIf { x -> x.none { it.isISOControl() } }
    }
}

internal class ExifInfo(val make: String?, val model: String?, val software: String?)

/** IFD0 di un blocco EXIF/TIFF: solo Make (0x010F), Model (0x0110), Software (0x0131). */
internal object Exif {
    fun read(raw: ByteArray): ExifInfo? {
        var o = 0
        if (raw.size >= 6 && String(raw, 0, 4, Charsets.ISO_8859_1) == "Exif") o = 6
        if (raw.size < o + 8) return null
        val le = when (String(raw, o, 2, Charsets.ISO_8859_1)) { "II" -> true; "MM" -> false; else -> return null }
        fun u16(i: Int): Int =
            if (i < 0 || i + 2 > raw.size) -1
            else if (le) (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
            else ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
        fun u32(i: Int): Long =
            if (i < 0 || i + 4 > raw.size) -1
            else if (le) u16(i).toLong() or (u16(i + 2).toLong() shl 16)
            else (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        if (u16(o + 2) != 42) return null
        val ifd = u32(o + 4)
        if (ifd < 0 || o + ifd > raw.size) return null
        val start = o + ifd.toInt()
        val n = u16(start)
        if (n < 0 || n > 200) return null
        var make: String? = null
        var model: String? = null
        var sw: String? = null
        for (k in 0 until n) {
            val e = start + 2 + k * 12
            val tag = u16(e)
            if (tag < 0) break
            if (tag != 0x010F && tag != 0x0110 && tag != 0x0131) continue
            if (u16(e + 2) != 2) continue // ASCII
            val count = u32(e + 4)
            if (count <= 0 || count > 256) continue
            val off = if (count <= 4) (e + 8).toLong() else o + u32(e + 8)
            if (off < 0 || off + count > raw.size) continue
            val s = String(raw, off.toInt(), count.toInt(), Charsets.ISO_8859_1).trimEnd('\u0000', ' ').takeIf { it.isNotBlank() }
            when (tag) { 0x010F -> make = s; 0x0110 -> model = s; else -> sw = s }
        }
        return ExifInfo(make, model, sw)
    }
}
