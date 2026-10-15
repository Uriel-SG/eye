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

    fun render(context: Context, result: CheckResult, showMessage: Boolean): Bitmap {
        val v = result.verdict
        val p = palette(v.level)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(p.container)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = p.solid
        c.drawRect(0f, 0f, 24f, H.toFloat(), fill) // barra colore sul bordo
        val textW = (W - 2 * M).toInt()

        // Intestazione
        drawEye(c, M, 72f, 64f, p.text)
        c.drawText(context.getString(R.string.app_name), M + 80f, 120f, tp(40f, p.text, true))
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM, java.util.Locale.ITALY).format(Date(result.createdAt))
        val dp = tp(28f, p.text)
        c.drawText(date, W - M - dp.measureText(date), 118f, dp)

        // Icona + titolo
        drawLevelIcon(c, v.level, M, 200f, 160f, p)
        val title = layout(context.getString(v.level.titleRes()), tp(88f, p.text, true), textW - 200, 2)
        c.drawLayout(title, M + 200f, 200f)
        val sub = layout(context.getString(v.level.cardTitleRes()), tp(36f, p.text), textW, 2)
        c.drawLayout(sub, M, 392f)

        // Estratto
        val cardTop = 520f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(RectF(M, cardTop, W - M, cardTop + 150f), 24f, 24f, white)
        val excerpt = if (showMessage) Redactor.preview(result.originalText, 220) else context.getString(R.string.card_message_hidden)
        c.drawLayout(layout(excerpt, tp(36f, 0xFF1B1B1F.toInt()), textW - 48, 3), M + 24f, cardTop + 20f)

        // Perché
        var y = 700f
        c.drawText(context.getString(R.string.card_why), M, y, tp(40f, p.text, true))
        y += 24f
        val reasons = v.reasonTexts(3)
        val reasonPaint = tp(40f, 0xFF1B1B1F.toInt())
        reasons.forEach { r ->
            fill.color = p.solid
            c.drawCircle(M + 24f, y + 36f, 24f, fill)
            val l = layout(r, reasonPaint, textW - 72, 2)
            c.drawLayout(l, M + 72f, y + 6f)
            y += maxOf(l.height + 12f, 72f)
        }

        // Cosa fare
        y = maxOf(y + 16f, 1060f)
        c.drawText(context.getString(R.string.card_todo), M, y, tp(40f, p.text, true))
        y += 16f
        v.todoSteps(context).take(2).forEach { s ->
            val l = layout("• $s", tp(40f, 0xFF1B1B1F.toInt()), textW, 2)
            if (y + l.height < 1215f) { c.drawLayout(l, M, y); y += l.height + 8f }
        }

        // Disclaimer e piè
        c.drawLayout(layout(context.getString(R.string.card_disclaimer), tp(26f, p.text), textW, 2), M, 1222f)
        c.drawText(context.getString(R.string.card_footer), M, 1318f, tp(26f, p.text, true))
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
