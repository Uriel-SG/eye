package app.eye.android.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.eye.engine.UrlInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import app.eye.engine.image.ImageFacts
import app.eye.engine.image.ImageLabel
import app.eye.engine.image.OcrBlock
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Contenuto di un QR: mai aperto in automatico (SEC-09). */
data class QrContent(val raw: String, val url: String?, val host: String?, val registrable: String?) {
    val isUrl get() = url != null

    companion object {
        /** Solo http/https sono URL (parsing del motore, stesso del browser); intent:, WIFI:, tel: ecc. restano testo. */
        fun parse(raw: String): QrContent {
            val t = raw.trim()
            val lower = t.lowercase()
            val web = lower.startsWith("http://") || lower.startsWith("https://")
            val host = if (web) UrlInfo.host(t) else null
            val reg = if (web) UrlInfo.registrableDomain(t) else null
            return if (host != null && reg != null && t.none { it.isWhitespace() }) QrContent(t, t, host, reg)
            else QrContent(t, null, null, null)
        }
    }
}

/** Esito tecnico di scansione/OCR, per messaggi chiari all'utente. */
sealed interface ScanError {
    data object Unavailable : ScanError // motore di lettura non utilizzabile
    data object Failed : ScanError
    data object Cancelled : ScanError
}

class OcrException(val error: ScanError) : Exception()

/** OCR sul telefono con ML Kit bundled via Play services (modelli scaricati da Play). L'immagine non viene mai salvata. */
object OcrReader {
    private const val MAX_SIDE = 2048

    /** Decodifica con downscale; ritorna null se non è un'immagine leggibile. */
    fun decode(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Con inJustDecodeBounds decodeStream restituisce SEMPRE null: si legge outWidth/outHeight, non il valore di ritorno.
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    suspend fun read(context: Context, uri: Uri): String {
        val bmp = decode(context, uri) ?: throw OcrException(ScanError.Failed)
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            return try {
                recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text
            } catch (e: OcrException) {
                throw e
            } catch (_: Exception) {
                throw OcrException(ScanError.Unavailable)
            } finally {
                recognizer.close()
            }
        } finally {
            bmp.recycle()
        }
    }


    /** Risultato di una immagine: testo OCR, primo QR trovato e fatti per riconoscere fumetti/illustrazioni (tutto opzionale). */
    data class ImageScan(val text: String, val qr: String?, val facts: ImageFacts = ImageFacts(0, 0))

    /** Esegue OCR, lettura QR ed etichettatura sulla stessa immagine, tutto sul telefono (modelli inclusi nell'app). */
    suspend fun scanImage(context: Context, uri: Uri): ImageScan {
        val bmp = decode(context, uri) ?: throw OcrException(ScanError.Failed)
        try {
            val qr = BarcodeReader.fromBitmap(bmp) // offline
            var blocks = emptyList<OcrBlock>()
            val text = try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val r = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                    blocks = r.textBlocks.mapNotNull { b -> b.boundingBox?.let { OcrBlock(b.text, it.left, it.top, it.right, it.bottom) } }
                    r.text
                } finally { recognizer.close() }
            } catch (e: Exception) {
                if (qr == null) throw OcrException(ScanError.Unavailable)
                ""
            }
            return ImageScan(text, qr, ImageFacts(bmp.width, bmp.height, ImageLabelReader.read(bmp), blocks, hasQr = qr != null))
        } finally {
            bmp.recycle()
        }
    }
}

/** Etichette dell'immagine con il modello di base ML Kit incluso nell'app (offline). Qualsiasi errore: nessuna etichetta. */
object ImageLabelReader {
    suspend fun read(bmp: Bitmap): List<ImageLabel> = try {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.3f).build())
        try {
            labeler.process(InputImage.fromBitmap(bmp, 0)).await().map { ImageLabel(it.text, it.confidence) }
        } finally {
            labeler.close()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

object BarcodeReader {
    fun fromBitmap(bmp: Bitmap): String? {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return app.eye.android.core.vision.QrDecoder.decodeArgb(px, bmp.width, bmp.height)
    }

    fun fromUri(context: Context, uri: Uri): String? {
        val bmp = OcrReader.decode(context, uri) ?: return null
        return try { fromBitmap(bmp) } finally { bmp.recycle() }
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
