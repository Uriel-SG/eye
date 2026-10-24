package app.eye.android.core

import android.content.Context
import android.net.Uri
import app.eye.engine.provenance.ImageProvenance
import app.eye.engine.provenance.ProvenanceReport

/** Legge sul telefono i primi byte dell'immagine (limite fisso) e ne estrae la provenienza dichiarata. Nessuna rete, nessun salvataggio. */
object ImageProvenanceReader {
    fun read(context: Context, uri: Uri): ProvenanceReport? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(ImageProvenance.MAX_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            ImageProvenance.analyze(buf.copyOf(n))
        }
    } catch (_: Exception) { null }
}
