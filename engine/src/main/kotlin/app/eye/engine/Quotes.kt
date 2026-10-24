package app.eye.engine

import java.util.regex.Pattern

/**
 * Meta-discorso: frasi tra virgolette (" ' « » “ ”) o introdotte da «per esempio / ad esempio» sono citazioni (di una
 * truffa, di un titolo, di un avviso), non richieste rivolte al lettore. [strip] le sostituisce con uno spazio;
 * le richieste vere (fuori dalle virgolette) restano. Puro Kotlin.
 */
internal object Quotes {
    private val spans = Pattern.compile(
        "\"[^\"\\n]{3,200}\"" +
            "|«[^»\\n]{3,200}»" +
            "|“[^”\\n]{3,200}”" +
            "|‘[^’\\n]{3,200}’" +
            // apice singolo: apre solo dopo un non-carattere (non l'apostrofo di «l'ordine») e chiude prima di un non-carattere
            "|(?<![\\p{L}\\p{N}])'[^'\\n]{3,200}'(?![\\p{L}\\p{N}])" +
            // introduzione esplicita di un esempio: fino a fine frase
            "|(?i:\\b(?:per esempio|ad esempio|esempio:|es\\.:?)[^.!?\\n]{0,200})",
    )

    fun strip(raw: String): String = spans.matcher(raw).replaceAll(" ")
}
