package app.eye.android.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import app.eye.android.R
import app.eye.engine.Level
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Scheda verdetto condivisibile, PNG 1080x1350 (DESIGN §5). Colori light fissi, scala in px. */
object ShareCard {
    const val W = 1080
    const val H = 1350
    private const val M = 72f

    private class Palette(val container: Int, val text: Int, val solid: Int)

    private fun palette(l: Level) = when (l) {
        Level.PROBABILE_TRUFFA -> Palette(0xFFFDECEA.toInt(), 0xFF8C1D18.toInt(), 0xFFB3261E.toInt())
        Level.SOSPETTO -> Palette(0xFFFFF1D6.toInt(), 0xFF6B4500.toInt(), 0xFFF5B700.toInt())
        Level.NESSUN_SEGNALE -> Palette(0xFFDDF1F4.toInt(), 0xFF0B4F5C.toInt(), 0xFF0B6B7A.toInt())
    }

    private fun tp(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()

    private fun Canvas.drawLayout(l: StaticLayout, x: Float, y: Float) {
        save(); translate(x, y); l.draw(this); restore()
    }

    private const val INK = 0xFF1B1B1F.toInt()
    private const val GAP = 28f

    /** Un blocco di scheda: altezza misurata, disegno a una y data. */
    private class Block(val height: Float, val draw: (Canvas, Float) -> Unit)

    /**
     * Impagina dinamicamente: ogni testo è misurato con StaticLayout e i blocchi sono messi uno sotto l'altro,
     * quindi nulla si sovrappone; l'altezza del PNG cresce col contenuto (minimo [H]).
     */
    fun render(context: Context, result: CheckResult, showMessage: Boolean): Bitmap {
        val v = result.verdict
        val p = palette(v.level)
        val sum = ReportBuilder.summary(result)
        val textW = (W - 2 * M).toInt()
        val blocks = ArrayList<Block>()
        fun gap(h: Float = GAP) = blocks.add(Block(h) { _, _ -> })

        // Intestazione: occhio + nome + data
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM, java.util.Locale.ITALY).format(Date(result.createdAt))
        blocks += Block(72f) { c, y ->
            drawEye(c, M, y, 64f, p.text)
            c.drawText(context.getString(R.string.app_name), M + 80f, y + 48f, tp(40f, p.text, true))
            val dp = tp(28f, p.text)
            c.drawText(date, W - M - dp.measureText(date), y + 46f, dp)
        }
        gap(40f)

        // Icona + titolo (altezza = la maggiore dei due), poi sottotitolo sotto
        val title = layout(context.getString(v.level.titleRes()), tp(80f, p.text, true), textW - 200, 3)
        val rowH = maxOf(160f, title.height.toFloat())
        blocks += Block(rowH) { c, y ->
            drawLevelIcon(c, v.level, M, y, 160f, p)
            c.drawLayout(title, M + 200f, y + (rowH - title.height) / 2)
        }
        gap(20f)
        val sub = layout(context.getString(v.level.cardTitleRes()), tp(36f, p.text), textW, 3)
        blocks += Block(sub.height.toFloat()) { c, y -> c.drawLayout(sub, M, y) }
        gap(36f)

        // Cosa è stato analizzato (sempre presente), con estratto oscurato o testo integrale
        val label = layout(context.getString(R.string.card_analyzed), tp(28f, p.text, true), textW - 48, 1)
        val what = layout(sum.line, tp(38f, INK, true), textW - 48, 4)
        val bodyText = if (showMessage) result.originalText.trim().replace(Regex("""\s+"""), " ").take(700).let { if (it.length < result.originalText.trim().length) "$it…" else it }
        else sum.excerpt
        val bodyLabel = layout(context.getString(if (showMessage) R.string.card_full_message else R.string.card_excerpt), tp(26f, p.text, true), textW - 48, 1)
        val body = layout("«$bodyText»", tp(34f, 0xFF3A3A40.toInt()), textW - 48, if (showMessage) 9 else 4)
        val boxH = 24f + label.height + 8f + what.height + 20f + bodyLabel.height + 6f + body.height + 24f
        blocks += Block(boxH) { c, y ->
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            c.drawRoundRect(RectF(M, y, W - M, y + boxH), 24f, 24f, white)
            var yy = y + 24f
            c.drawLayout(label, M + 24f, yy); yy += label.height + 8f
            c.drawLayout(what, M + 24f, yy); yy += what.height + 20f
            c.drawLayout(bodyLabel, M + 24f, yy); yy += bodyLabel.height + 6f
            c.drawLayout(body, M + 24f, yy)
        }
        gap(40f)

        // Perché: sempre con contenuto (messaggio prudente se non ci sono motivi)
        val h40 = tp(40f, p.text, true)
        blocks += Block(52f) { c, y -> c.drawText(context.getString(R.string.card_why), M, y + 40f, h40) }
        gap(8f)
        val reasons = v.reasonTexts(3)
        if (reasons.isEmpty()) {
            val l = layout(context.getString(R.string.card_why_none), tp(36f, INK), textW, 5)
            blocks += Block(l.height.toFloat()) { c, y -> c.drawLayout(l, M, y) }
        } else reasons.forEach { r ->
            val l = layout(r, tp(36f, INK), textW - 72, 3)
            val h = maxOf(l.height.toFloat(), 48f)
            blocks += Block(h) { c, y ->
                val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.solid }
                c.drawCircle(M + 20f, y + 24f, 16f, f)
                c.drawLayout(l, M + 72f, y)
            }
            gap(14f)
        }
        gap(26f)

        // Cosa fare: 1-2 righe di consigli
        blocks += Block(52f) { c, y -> c.drawText(context.getString(R.string.card_todo), M, y + 40f, h40) }
        gap(8f)
        v.todoSteps(context).take(2).forEach { s ->
            val l = layout("• $s", tp(36f, INK), textW, 3)
            blocks += Block(l.height.toFloat()) { c, y -> c.drawLayout(l, M, y) }
            gap(10f)
        }
        gap(34f)

        // Disclaimer e piè
        val disc = layout(context.getString(R.string.card_disclaimer), tp(26f, p.text), textW, 3)
        blocks += Block(disc.height.toFloat()) { c, y -> c.drawLayout(disc, M, y) }
        gap(18f)
        blocks += Block(34f) { c, y -> c.drawText(context.getString(R.string.card_footer), M, y + 28f, tp(28f, p.text, true)) }

        val total = 72f + blocks.sumOf { it.height.toDouble() }.toFloat() + 48f
        val height = maxOf(H.toFloat(), total).toInt()
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(p.container)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.solid }
        c.drawRect(0f, 0f, 24f, height.toFloat(), bar) // barra colore sul bordo
        var y = 56f
        blocks.forEach { b -> b.draw(c, y); y += b.height }
        return bmp
    }

    private fun drawEye(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 6f }
        c.drawOval(RectF(x, y + size * 0.2f, x + size, y + size * 0.8f), st)
        st.style = Paint.Style.FILL
        c.drawCircle(x + size / 2, y + size / 2, size * 0.14f, st)
    }

    private fun drawLevelIcon(c: Canvas, level: Level, x: Float, y: Float, s: Float, p: Palette) {
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.solid }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (level == Level.SOSPETTO) Color.BLACK else Color.WHITE
            style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND
        }
        when (level) {
            Level.PROBABILE_TRUFFA -> {
                c.drawCircle(x + s / 2, y + s / 2, s / 2, f)
                c.drawLine(x + s * .32f, y + s * .32f, x + s * .68f, y + s * .68f, ink)
                c.drawLine(x + s * .68f, y + s * .32f, x + s * .32f, y + s * .68f, ink)
            }
            Level.SOSPETTO -> {
                val path = Path().apply { moveTo(x + s / 2, y); lineTo(x + s, y + s); lineTo(x, y + s); close() }
                c.drawPath(path, f)
                c.drawLine(x + s / 2, y + s * .38f, x + s / 2, y + s * .66f, ink)
                ink.style = Paint.Style.FILL
                c.drawCircle(x + s / 2, y + s * .8f, 8f, ink)
            }
            Level.NESSUN_SEGNALE -> {
                c.drawCircle(x + s / 2, y + s / 2, s / 2, f)
                ink.strokeWidth = 10f
                c.drawOval(RectF(x + s * .22f, y + s * .34f, x + s * .78f, y + s * .66f), ink)
                ink.style = Paint.Style.FILL
                c.drawCircle(x + s / 2, y + s / 2, s * .08f, ink)
            }
        }
    }

    /** Cancella tutte le schede PNG in cache (F-05). */
    fun clearCache(context: Context) {
        File(context.cacheDir, "share").listFiles()?.forEach { it.delete() }
    }

    /** Salva il PNG in cache/share e restituisce l'URI FileProvider (non esportato, SEC-20). */
    fun save(context: Context, bmp: Bitmap): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 3_600_000) it.delete() }
        val f = File(dir, "eye-scheda-${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    fun shareIntent(context: Context, uri: Uri, alt: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, alt)
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.share_chooser))
    }
}
