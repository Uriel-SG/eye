package app.eye.engine.provenance

import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture minime e sintetiche costruite in memoria (nessuna immagine reale). */
class ImageProvenanceTest {
    private val iptc = "http://cv.iptc.org/newscodes/digitalsourcetype/"
    private fun xmp(type: String?, extra: String = "") =
        "<?xpacket begin=\"\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-12/\">" +
            (type?.let { "<Iptc4xmpExt:DigitalSourceType>$iptc$it</Iptc4xmpExt:DigitalSourceType>" } ?: "") + extra +
            "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0xFF); o.write(marker)
        val len = payload.size + 2
        o.write(len shr 8); o.write(len and 0xFF); o.write(payload)
        return o.toByteArray()
    }

    private fun jpeg(vararg segs: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segs.forEach { o.write(it) }
        o.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3))
        o.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return o.toByteArray()
    }

    private fun xmpSeg(s: String) = seg(0xE1, ("http://ns.adobe.com/xap/1.0/\u0000$s").toByteArray())

    private fun exifSeg(software: String? = null, make: String? = null, bigEndian: Boolean = false): ByteArray {
        val entries = listOfNotNull(make?.let { 0x010F to it }, software?.let { 0x0131 to it })
        val le = !bigEndian
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { if (le) { o.write(v and 0xFF); o.write(v shr 8) } else { o.write(v shr 8); o.write(v and 0xFF) } }
        fun u32(v: Int) { if (le) { u16(v and 0xFFFF); u16(v ushr 16) } else { u16(v ushr 16); u16(v and 0xFFFF) } }
        o.write("Exif\u0000\u0000".toByteArray())
        o.write((if (le) "II" else "MM").toByteArray())
        u16(42); u32(8)
        u16(entries.size)
        var data = 8 + 2 + entries.size * 12 + 4
        val blobs = ByteArrayOutputStream()
        for ((tag, s) in entries) {
            val b = (s + "\u0000").toByteArray()
            u16(tag); u16(2); u32(b.size); u32(data)
            data += b.size
            blobs.write(b)
        }
        u32(0)
        o.write(blobs.toByteArray())
        return seg(0xE1, o.toByteArray())
    }

    /** Manifest C2PA sintetico: JUMBF con etichetta c2pa e CBOR con claim_generator_info e (opzionale) digitalSourceType. */
    private fun jumbf(generator: String, sourceType: String?): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("JP".toByteArray()); o.write(ByteArray(8)); o.write("jumb".toByteArray()); o.write("jumdc2pa".toByteArray())
        o.write("claim_generator_info".toByteArray()); o.write(0xA1); o.write(0x64); o.write("name".toByteArray())
        o.write(0x60 + generator.length); o.write(generator.toByteArray())
        sourceType?.let {
            o.write("digitalSourceType".toByteArray())
            val u = iptc + it
            o.write(0x78); o.write(u.length); o.write(u.toByteArray())
        }
        return o.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun be(v: Int) { o.write(v ushr 24); o.write(v shr 16 and 0xFF); o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
        be(data.size); o.write(type.toByteArray()); o.write(data)
        val c = CRC32(); c.update(type.toByteArray()); c.update(data)
        be(c.value.toInt())
        return o.toByteArray()
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        o.write(chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)))
        chunks.forEach { o.write(it) }
        o.write(chunk("IDAT", byteArrayOf(0))); o.write(chunk("IEND", ByteArray(0)))
        return o.toByteArray()
    }

    private fun itxt(key: String, text: String, compress: Boolean): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(key.toByteArray()); o.write(0); o.write(if (compress) 1 else 0); o.write(0); o.write(0); o.write(0)
        if (compress) {
            val d = Deflater(); d.setInput(text.toByteArray()); d.finish()
            val buf = ByteArray(8192); val n = d.deflate(buf); d.end(); o.write(buf, 0, n)
        } else o.write(text.toByteArray())
        return chunk("iTXt", o.toByteArray())
    }

    private fun webp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        for ((t, d) in chunks) {
            body.write(t.toByteArray())
            val n = d.size
            body.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), 0, 0)); body.write(d)
            if (n % 2 == 1) body.write(0)
        }
        val o = ByteArrayOutputStream()
        o.write("RIFF".toByteArray())
        val n = body.size()
        o.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), 0, 0)); o.write(body.toByteArray())
        return o.toByteArray()
    }

    @Test fun jpegXmpTrainedAlgorithmicMedia() {
        val r = ImageProvenance.analyze(jpeg(xmpSeg(xmp("trainedAlgorithmicMedia"))))
        assertEquals(ProvenanceKind.AI_DECLARED, r.kind)
        assertFalse(r.fromC2pa)
        assertTrue(ProvenanceMessages.body(r).contains("fonte: metadati"))
    }

    @Test fun compositeAndAlgorithmicAreAi() {
        for (t in listOf("compositeWithTrainedAlgorithmicMedia", "algorithmicMedia", "compositeSynthetic"))
            assertEquals(ProvenanceKind.AI_DECLARED, ImageProvenance.analyze(jpeg(xmpSeg(xmp(t)))).kind, t)
    }

    @Test fun digitalCaptureIsNotAi() {
        assertEquals(ProvenanceKind.NONE, ImageProvenance.analyze(jpeg(xmpSeg(xmp("digitalCapture")))).kind)
        assertEquals(ProvenanceKind.NONE, ImageProvenance.analyze(jpeg(xmpSeg(xmp("minorHumanEdits")))).kind)
    }

    @Test fun exifSoftwareToolBothEndiannesses() {
        for (be in listOf(false, true)) {
            val r = ImageProvenance.analyze(jpeg(exifSeg(software = "Midjourney V7", bigEndian = be)))
            assertEquals(ProvenanceKind.AI_DECLARED, r.kind)
            assertEquals("Midjourney", r.tool)
        }
    }

    @Test fun ordinaryExifIsNone() {
        val r = ImageProvenance.analyze(jpeg(exifSeg(software = "Adobe Photoshop 25.0", make = "Canon")))
        assertEquals(ProvenanceKind.NONE, r.kind)
    }

    @Test fun xmpToolWithoutSourceType() {
        val r = ImageProvenance.analyze(jpeg(xmpSeg(xmp(null, "<xmp:CreatorTool>Stable Diffusion 3</xmp:CreatorTool>"))))
        assertEquals(ProvenanceKind.AI_DECLARED, r.kind)
        assertEquals("Stable Diffusion", r.tool)
    }

    @Test fun c2paWithAiSource() {
        val r = ImageProvenance.analyze(jpeg(seg(0xEB, jumbf("Google C2PA Core Generator Library", "trainedAlgorithmicMedia"))))
        assertEquals(ProvenanceKind.AI_DECLARED, r.kind)
        assertTrue(r.fromC2pa)
        assertTrue(ProvenanceMessages.body(r).contains("fonte: Content Credentials"))
    }

    @Test fun c2paWithoutAi() {
        val r = ImageProvenance.analyze(jpeg(seg(0xEB, jumbf("Pixel Camera", "digitalCapture"))))
        assertEquals(ProvenanceKind.C2PA_NO_AI, r.kind)
        assertEquals("Pixel Camera", r.tool)
        assertTrue(ProvenanceMessages.body(r).startsWith("L'immagine contiene informazioni di provenienza (Content Credentials) che non indicano l'uso dell'intelligenza artificiale."))
        assertNull(ProvenanceMessages.reasonText(r))
    }

    @Test fun c2paGeneratorKnownAiTool() {
        val r = ImageProvenance.analyze(jpeg(seg(0xEB, jumbf("OpenAI DALL-E 3", null))))
        assertEquals(ProvenanceKind.AI_DECLARED, r.kind)
        assertEquals("DALL-E", r.tool)
    }

    @Test fun pngVariants() {
        assertEquals(ProvenanceKind.AI_DECLARED, ImageProvenance.analyze(png(chunk("caBX", jumbf("Gen", "trainedAlgorithmicMedia")))).kind)
        assertEquals(ProvenanceKind.AI_DECLARED, ImageProvenance.analyze(png(itxt("XML:com.adobe.xmp", xmp("trainedAlgorithmicMedia"), compress = false))).kind)
        assertEquals(ProvenanceKind.AI_DECLARED, ImageProvenance.analyze(png(itxt("XML:com.adobe.xmp", xmp("compositeWithTrainedAlgorithmicMedia"), compress = true))).kind)
        val d = ImageProvenance.analyze(png(chunk("tEXt", "parameters\u0000a cat\nSteps: 20, Sampler: Euler".toByteArray())))
        assertEquals("Stable Diffusion", d.tool)
        assertEquals(ProvenanceKind.NONE, ImageProvenance.analyze(png()).kind)
    }

    @Test fun webpVariants() {
        assertEquals(ProvenanceKind.AI_DECLARED, ImageProvenance.analyze(webp("XMP " to xmp("trainedAlgorithmicMedia").toByteArray())).kind)
        assertEquals(ProvenanceKind.C2PA_NO_AI, ImageProvenance.analyze(webp("C2PA" to jumbf("Cam", null))).kind)
    }

    @Test fun heicFallbackFindsXmp() {
        val b = "\u0000\u0000\u0000\u0018ftypheic".toByteArray() + xmp("trainedAlgorithmicMedia").toByteArray()
        assertEquals(ProvenanceKind.AI_DECLARED, ImageProvenance.analyze(b).kind)
    }

    @Test fun noMetadataSaysNoneWithCaution() {
        val r = ImageProvenance.analyze(jpeg())
        assertEquals(ProvenanceKind.NONE, r.kind)
        val t = ProvenanceMessages.body(r)
        assertTrue(t.contains("Nessuna informazione di provenienza trovata.") && t.contains("WhatsApp"))
    }

    @Test fun messagesNeverReassure() {
        for (r in listOf(ProvenanceReport(ProvenanceKind.AI_DECLARED, "X"), ProvenanceReport(ProvenanceKind.C2PA_NO_AI), ProvenanceReport(ProvenanceKind.NONE))) {
            val t = ProvenanceMessages.body(r).lowercase().replace("corrisponda al vero", "").replace("non significa che l'immagine sia autentica", "")
            assertFalse(Regex("\\b(vera|vero|sicura|sicuro|autentica|autentico)\\b").containsMatchIn(t), t)
        }
        assertNotNull(ProvenanceMessages.reasonText(ProvenanceReport(ProvenanceKind.AI_DECLARED)))
    }

    @Test fun readLimitIgnoresMetadataBeyond512Kb() {
        val pad = ByteArray(600 * 1024)
        val tail = xmp("trainedAlgorithmicMedia").toByteArray()
        assertEquals(ProvenanceKind.NONE, ImageProvenance.analyze(pad + tail).kind)
    }

    @Test fun fuzzNeverThrows() {
        val rnd = Random(42)
        val seeds = listOf(
            jpeg(xmpSeg(xmp("trainedAlgorithmicMedia")), exifSeg(software = "Midjourney"), seg(0xEB, jumbf("G", "trainedAlgorithmicMedia"))),
            png(chunk("caBX", jumbf("G", null)), itxt("XML:com.adobe.xmp", xmp("trainedAlgorithmicMedia"), true)),
            webp("XMP " to xmp("trainedAlgorithmicMedia").toByteArray(), "EXIF" to exifSeg("Midjourney").copyOfRange(4, 40)),
        )
        repeat(3000) {
            val s = seeds[rnd.nextInt(seeds.size)].copyOf()
            repeat(1 + rnd.nextInt(8)) { s[rnd.nextInt(s.size)] = rnd.nextInt(256).toByte() }
            ImageProvenance.analyze(if (rnd.nextBoolean()) s else s.copyOf(rnd.nextInt(s.size + 1)))
        }
        repeat(500) { ImageProvenance.analyze(ByteArray(rnd.nextInt(300)).also(rnd::nextBytes)) }
        ImageProvenance.analyze(ByteArray(0))
    }
}
