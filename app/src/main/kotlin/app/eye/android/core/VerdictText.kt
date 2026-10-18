package app.eye.android.core

import android.content.Context
import app.eye.android.R
import app.eye.engine.Level
import app.eye.engine.Verdict
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Un controllo completato, con l'anteprima già oscurata (mai il testo integrale oltre la sessione). */
data class CheckResult(
    val verdict: Verdict,
    /** Testo originale, solo in memoria durante la sessione di verdetto. */
    val originalText: String,
    val createdAt: Long,
    /** Il testo era oltre il limite: sono stati controllati solo i primi MAX_CHARS caratteri. */
    val truncated: Boolean = false,
    /** Riga di cronologia di questo controllo, se è stato salvato. */
    val historyId: Long? = null,
    /** Origine del testo; null se il controllo viene dalla cronologia. */
    val source: app.eye.engine.Source? = null,
    /** App di origine e mittente (solo avvisi della Protezione automatica). */
    val origin: String? = null,
    val sender: String? = null,
)

fun Level.titleRes(): Int = when (this) {
    Level.PROBABILE_TRUFFA -> R.string.verdict_title_scam
    Level.SOSPETTO -> R.string.verdict_title_suspicious
    Level.NESSUN_SEGNALE -> R.string.verdict_title_none
}

fun Level.subtitleRes(): Int = when (this) {
    Level.PROBABILE_TRUFFA -> R.string.verdict_sub_scam
    Level.SOSPETTO -> R.string.verdict_sub_suspicious
    Level.NESSUN_SEGNALE -> R.string.verdict_sub_none
}

fun Level.familyLineRes(): Int = when (this) {
    Level.PROBABILE_TRUFFA -> R.string.verdict_family_one_line_scam
    Level.SOSPETTO -> R.string.verdict_family_one_line_suspicious
    Level.NESSUN_SEGNALE -> R.string.verdict_family_one_line_none
}

fun Level.defaultActionRes(): List<Int> = when (this) {
    Level.PROBABILE_TRUFFA -> listOf(R.string.todo_scam_1, R.string.todo_scam_2, R.string.todo_scam_3, R.string.todo_scam_4)
    Level.SOSPETTO -> listOf(R.string.todo_susp_1, R.string.todo_susp_2, R.string.todo_susp_3)
    Level.NESSUN_SEGNALE -> listOf(R.string.todo_none_1, R.string.todo_none_2)
}

fun Level.cardTitleRes(): Int = when (this) {
    Level.PROBABILE_TRUFFA -> R.string.card_title_scam
    Level.SOSPETTO -> R.string.card_title_suspicious
    Level.NESSUN_SEGNALE -> R.string.card_title_none
}

/** «Cosa fare ora»: azioni del motore se presenti (max 4), altrimenti i testi di DESIGN §4. */
fun Verdict.todoSteps(context: Context): List<String> =
    actions.filter { it.isNotBlank() }.ifEmpty { level.defaultActionRes().map(context::getString) }.take(4)

fun Verdict.reasonTexts(max: Int = 5): List<String> = reasons.map { it.text }.filter { it.isNotBlank() }.take(max)

/** Motivi con testo non vuoto (max [max]), con il peso: serve a colorare l'icona. */
fun Verdict.reasonItems(max: Int = 5): List<app.eye.engine.Reason> = reasons.filter { it.text.isNotBlank() }.take(max)

/** RISK: aumenta il rischio (icona rossa); REDUCES: lo riduce (peso negativo, icona verde); NEUTRAL: informativo. */
enum class ReasonTone { RISK, NEUTRAL, REDUCES }

fun reasonTone(weight: Int): ReasonTone = when {
    weight > 0 -> ReasonTone.RISK
    weight < 0 -> ReasonTone.REDUCES
    else -> ReasonTone.NEUTRAL
}

object ReportBuilder {
    fun summary(r: CheckResult): AnalyzedSummary {
        val e = r.verdict.extracted
        return ShareTexts.summary(r.originalText, r.source, e.domains, e.urls, e.amounts)
    }

    /** «Manda a un familiare»: cosa è stato analizzato (oscurato), verdetto, motivi e cosa fare. */
    fun shareText(context: Context, r: CheckResult): String =
        ShareTexts.familyText(context.getString(r.verdict.level.titleRes()), summary(r), r.verdict.reasonTexts(3), r.verdict.todoSteps(context))

    fun shareSubject(context: Context, r: CheckResult): String =
        ShareTexts.familySubject(context.getString(r.verdict.level.titleRes()), summary(r))

    private fun dateTime(t: Long, locale: Locale) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(t))

    /** Report copiabile; il testo originale solo se [includeMessage] (scelta esplicita). */
    fun report(context: Context, r: CheckResult, includeMessage: Boolean = false, locale: Locale = Locale.getDefault()): String {
        val v = r.verdict
        return ShareTexts.report(
            context.getString(v.level.titleRes()), dateTime(r.createdAt, locale), summary(r), v.reasonTexts(3), v.todoSteps(context),
            v.rulesVersion, context.getString(R.string.verdict_disclaimer_short), if (includeMessage) r.originalText else null,
        )
    }

    /** «Il verdetto è sbagliato?»: email con il testo COMPLETO, che l'utente rivede prima di inviare. */
    fun feedbackBody(context: Context, r: CheckResult, view: ShareTexts.UserView, locale: Locale = Locale.getDefault()): String {
        val v = r.verdict
        return ShareTexts.feedbackBody(
            r.originalText, context.getString(v.level.titleRes()), view, summary(r).line, v.score, v.reasons, v.extracted.domains, r.source,
            app.eye.android.BuildConfig.VERSION_NAME, v.rulesVersion, dateTime(r.createdAt, locale),
        )
    }

    fun feedbackSubject(context: Context, r: CheckResult, view: ShareTexts.UserView): String =
        ShareTexts.feedbackSubject(view, context.getString(r.verdict.level.titleRes()))
}
