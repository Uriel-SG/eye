package app.eye.android.core.vision

import com.google.zxing.Binarizer
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Lettura QR con ZXing (Java puro, offline): nessun Play services, nessun download. */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    private val binarizers: List<(LuminanceSource) -> Binarizer> = listOf(::HybridBinarizer, ::GlobalHistogramBinarizer)

    /** Prova normale e invertita (QR chiaro su scuro), con due binarizzatori. */
    private fun tryDecode(src: LuminanceSource): String? {
        for (s in listOf(src, src.invert())) for (bin in binarizers) {
            try {
                return QRCodeReader().decode(BinaryBitmap(bin(s)), hints).text
            } catch (_: Exception) {
                // nessun QR con questa combinazione
            }
        }
        return null
    }

    /** Frame della camera: piano Y (width*height, senza padding) e rotazione da applicare (0/90/180/270). */
    fun decodeLuma(y: ByteArray, width: Int, height: Int, rotation: Int = 0): String? {
        var data = y
        var w = width
        var h = height
        when (((rotation % 360) + 360) % 360) {
            90 -> { data = rotate90(y, w, h); w = height; h = width }
            180 -> data = y.reversedArray()
            270 -> { data = rotate90(y.reversedArray(), w, h); w = height; h = width }
        }
        return tryDecode(PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false))
    }

    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (row in 0 until h) for (col in 0 until w) out[col * h + (h - 1 - row)] = src[row * w + col]
        return out
    }

    /** Immagine ARGB: prima a piena risoluzione, poi ridotta (QR molto grandi o rumorosi si leggono meglio). */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        tryDecode(RGBLuminanceSource(width, height, pixels))?.let { return it }
        var w = width
        var h = height
        var px = pixels
        repeat(3) {
            if (w < 200 || h < 200) return null
            val nw = w / 2
            val nh = h / 2
            val out = IntArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) out[y * nw + x] = px[(y * 2) * w + x * 2]
            px = out; w = nw; h = nh
            tryDecode(RGBLuminanceSource(w, h, px))?.let { return it }
        }
        return null
    }
}
