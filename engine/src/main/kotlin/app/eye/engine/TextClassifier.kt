package app.eye.engine

/** Classificatore di testo (truffa/legittimo). Implementazioni nell'app; qui solo il contratto. */
fun interface TextClassifier {
    /** Probabilita' [0,1] che il testo sia una truffa; null = non disponibile (modello non caricato, errore). */
    fun scamProbability(text: String): Float?
}
