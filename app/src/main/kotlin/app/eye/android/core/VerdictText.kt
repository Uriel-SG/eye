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
    /** «Testo di condivisione» (DESIGN §4). Nessun contenuto del messaggio. */
    fun shareText(context: Context, v: Verdict): String {
        val reasons = v.reasonTexts(3).joinToString(" ").ifBlank { context.getString(R.string.share_text_no_reasons) }
        return context.getString(R.string.share_text, context.getString(v.level.titleRes()), reasons)
    }

    /** Report copiabile: esito, motivi, domini oscurati e passi. Il testo originale solo se [includeMessage] (scelta esplicita). */
    fun report(context: Context, r: CheckResult, includeMessage: Boolean = false, locale: Locale = Locale.getDefault()): String = buildString {
        val v = r.verdict
        appendLine(context.getString(R.string.report_header))
        appendLine(context.getString(R.string.report_date, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(r.createdAt))))
        appendLine(context.getString(R.string.report_verdict, context.getString(v.level.titleRes())))
        appendLine()
        if (includeMessage) appendLine(context.getString(R.string.verdict_preview) + ": " + r.originalText.take(2000))
        val reasons = v.reasonTexts()
        if (reasons.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.verdict_why))
            reasons.forEach { appendLine("- $it") }
        }
        if (v.extracted.domains.isNotEmpty()) appendLine(context.getString(R.string.report_domains, v.extracted.domains.distinct().joinToString(", ") { Redactor.defang(it) }))
        appendLine()
        appendLine(context.getString(R.string.verdict_todo))
        v.todoSteps(context).forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
        appendLine()
        appendLine(context.getString(R.string.report_rules_version, v.rulesVersion))
        append(context.getString(R.string.verdict_disclaimer_short))
    }

    /** «Il verdetto è sbagliato?»: solo esito, versione regole, motivi. Il messaggio lo aggiunge l'utente. */
    fun wrongVerdictBody(context: Context, v: Verdict): String = context.getString(
        R.string.wrong_body,
        context.getString(v.level.titleRes()),
        v.rulesVersion,
        v.reasons.joinToString { it.ruleId }.ifBlank { "-" },
    )
}
