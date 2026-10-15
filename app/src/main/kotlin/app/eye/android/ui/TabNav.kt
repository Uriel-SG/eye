package app.eye.android.ui

/**
 * Regole di navigazione della barra inferiore, pure (testabili su JVM).
 * Home deve sempre rispondere: se è nello stack si torna lì svuotando tutto ciò che sta sopra,
 * altrimenti si riparte da uno stack con la sola Home. Le altre voci stanno sopra Home, senza saveState/restoreState.
 */
object TabNav {
    sealed interface Plan {
        /** popBackStack(HOME, inclusive = false). */
        data object PopToHome : Plan
        /** Home non è nello stack: stack nuovo con la sola Home. */
        data object ResetToHome : Plan
        /** navigate(target) con popUpTo(HOME) e launchSingleTop. */
        data class Open(val route: String) : Plan
    }

    fun plan(stack: List<String>, target: String): Plan = when {
        target == Route.HOME -> if (Route.HOME in stack) Plan.PopToHome else Plan.ResetToHome
        else -> Plan.Open(target)
    }

    /** Stack risultante (per i test): stesso effetto delle chiamate eseguite da AppNav. */
    fun apply(stack: List<String>, target: String): List<String> = when (val p = plan(stack, target)) {
        Plan.PopToHome -> stack.take(stack.indexOf(Route.HOME) + 1)
        Plan.ResetToHome -> listOf(Route.HOME)
        is Plan.Open -> {
            val base = if (Route.HOME in stack) stack.take(stack.indexOf(Route.HOME) + 1) else stack
            if (base.lastOrNull() == p.route) base else base + p.route
        }
    }
}
