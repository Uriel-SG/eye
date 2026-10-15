package app.eye.android.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import androidx.core.content.FileProvider
import app.eye.android.R
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PdfStyle { TITLE, HEADING, BODY, MONO, NOTE }
data class PdfBlock(val style: PdfStyle, val text: String)

/** Testi del PDF, indipendenti da Context così si possono provare con i test JVM. */
fun interface PdfStr { fun get(id: Int, vararg args: Any): String }

/** Dati di un controllo da mettere nel PDF per la denuncia. */
data class PdfInput(
    val createdAt: Long,
    val appVersion: String,
    val rulesVersion: Long,
    val levelTitle: String,
    val reasons: List<String>,
    val steps: List<String>,
    val originalText: String,
    val domains: List<String>,
    val includePlainText: Boolean,
)

object ReportPdf {
    /** Impronta SHA-256 (esadecimale) del testo originale, UTF-8. */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Il testo analizzato: oscurato (Redactor) salvo scelta esplicita di includerlo in chiaro. */
    fun shownText(text: String, plain: Boolean): String = if (plain) text else Redactor.redact(text)

    fun fileName(createdAt: Long): String =
        "eye-report-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(createdAt)) + ".pdf"

    fun content(i: PdfInput, s: PdfStr, locale: Locale = Locale.getDefault()): List<PdfBlock> = buildList {
        add(PdfBlock(PdfStyle.TITLE, s.get(R.string.pdf_title)))
        add(PdfBlock(PdfStyle.BODY, s.get(R.string.pdf_date, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(i.createdAt)))))
        add(PdfBlock(PdfStyle.BODY, s.get(R.string.pdf_versions, i.appVersion, i.rulesVersion)))
        add(PdfBlock(PdfStyle.HEADING, s.get(R.string.pdf_outcome)))
        add(PdfBlock(PdfStyle.BODY, i.levelTitle))
        if (i.reasons.isNotEmpty()) {
            add(PdfBlock(PdfStyle.HEADING, s.get(R.string.verdict_why)))
            i.reasons.forEach { add(PdfBlock(PdfStyle.BODY, "- $it")) }
        }
        add(PdfBlock(PdfStyle.HEADING, s.get(R.string.verdict_todo)))
        i.steps.forEachIndexed { n, st -> add(PdfBlock(PdfStyle.BODY, "${n + 1}. $st")) }
        add(PdfBlock(PdfStyle.HEADING, s.get(if (i.includePlainText) R.string.pdf_text_plain else R.string.pdf_text_redacted)))
        if (i.includePlainText) add(PdfBlock(PdfStyle.NOTE, s.get(R.string.pdf_plain_warning)))
        add(PdfBlock(PdfStyle.MONO, shownText(i.originalText, i.includePlainText).ifBlank { s.get(R.string.pdf_no_text) }))
        add(PdfBlock(PdfStyle.HEADING, s.get(R.string.pdf_domains)))
        add(PdfBlock(PdfStyle.MONO, i.domains.distinct().joinToString("\n") { Redactor.defang(it) }.ifBlank { s.get(R.string.pdf_none) }))
        add(PdfBlock(PdfStyle.HEADING, s.get(R.string.pdf_hash)))
        add(PdfBlock(PdfStyle.MONO, sha256(i.originalText)))
        add(PdfBlock(PdfStyle.NOTE, s.get(R.string.pdf_hash_note)))
        add(PdfBlock(PdfStyle.NOTE, s.get(R.string.pdf_legal_note)))
        add(PdfBlock(PdfStyle.NOTE, s.get(R.string.pdf_report_to, POLIZIA_POSTALE_URL)))
    }

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    /** Disegna i blocchi su pagine A4 con PdfDocument (nessuna dipendenza) e scrive il PDF su [out]. */
    fun write(blocks: List<PdfBlock>, out: OutputStream) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
        }
        newPage()
        val width = PAGE_W - 2 * MARGIN
        try {
            blocks.forEach { b ->
                val paint = paint(b.style)
                val lh = paint.fontSpacing
                val before = if (b.style == PdfStyle.HEADING) 14f else 4f
                wrap(b.text, paint, width).forEachIndexed { n, line ->
                    val extra = if (n == 0) before else 0f
                    if (y + extra + lh > PAGE_H - MARGIN) newPage() else y += extra
                    y += lh
                    page!!.canvas.drawText(line, MARGIN, y - paint.descent(), paint)
                }
            }
            page?.let { doc.finishPage(it) }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun paint(s: PdfStyle) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (s == PdfStyle.NOTE) 0xFF444444.toInt() else 0xFF000000.toInt()
        when (s) {
            PdfStyle.TITLE -> { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
            PdfStyle.HEADING -> { textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
            PdfStyle.BODY -> textSize = 11f
            PdfStyle.MONO -> { textSize = 10f; typeface = Typeface.MONOSPACE }
            PdfStyle.NOTE -> { textSize = 9.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
        }
    }

    /** A capo per parole; le parole più larghe della riga (hash, URL) si spezzano a caratteri. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var cur = ""
            para.split(' ').forEach { word ->
                var w = word
                while (paint.measureText(w) > width) {
                    val n = paint.breakText(w, true, width, null).coerceAtLeast(1)
                    if (cur.isNotEmpty()) { lines += cur; cur = "" }
                    lines += w.substring(0, n); w = w.substring(n)
                }
                val trial = if (cur.isEmpty()) w else "$cur $w"
                if (cur.isNotEmpty() && paint.measureText(trial) > width) { lines += cur; cur = w } else cur = trial
            }
            lines += cur
        }
        return lines
    }

    /** Genera il PDF in cache/share e restituisce l'URI FileProvider (non esportato, nessun permesso storage). */
    fun saveToCache(context: Context, blocks: List<PdfBlock>, name: String): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name)
        f.outputStream().use { write(blocks, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    fun shareIntent(context: Context, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.share_chooser))
    }
}

/** Ricerca nella cronologia: solo sul testo già decifrato in memoria, nessun indice su disco. */
object HistorySearch {
    fun matches(query: String, vararg fields: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || fields.any { it.contains(q, ignoreCase = true) }
    }
}
