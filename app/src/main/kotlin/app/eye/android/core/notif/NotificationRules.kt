package app.eye.android.core.notif

import app.eye.engine.Level

/** App di messaggistica proposte per la Protezione automatica (mostrate solo se installate). */
data class MonitoredApp(val pkg: String, val label: String)

object MonitoredApps {
    val PRESETS = listOf(
        MonitoredApp("com.google.android.apps.messaging", "Messaggi Google"),
        MonitoredApp("com.samsung.android.messaging", "Samsung Messages"),
        MonitoredApp("com.whatsapp", "WhatsApp"),
        MonitoredApp("com.whatsapp.w4b", "WhatsApp Business"),
        MonitoredApp("org.telegram.messenger", "Telegram"),
        MonitoredApp("org.thoughtcrime.securesms", "Signal"),
        MonitoredApp("com.google.android.gm", "Gmail"),
        MonitoredApp("com.microsoft.office.outlook", "Outlook"),
        MonitoredApp("com.facebook.orca", "Messenger"),
    )

    /** Solo build debug: permette di provare con `adb shell cmd notification post` (package com.android.shell). */
    val DEBUG_EXTRA = MonitoredApp("com.android.shell", "Shell (debug)")

    fun presets(debug: Boolean): List<MonitoredApp> = if (debug) PRESETS + DEBUG_EXTRA else PRESETS

    /** Pacchetti da controllare: tutti i preset tranne quelli che l'utente ha disattivato. */
    fun active(debug: Boolean, disabled: Set<String>): Set<String> =
        presets(debug).map { it.pkg }.filterNot { it in disabled }.toSet()
}

/** Dati minimi di una notifica, già copiati dal sistema (testabili senza Android). */
class NotifData(
    val pkg: String,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    val title: CharSequence?,
    val text: CharSequence?,
    val bigText: CharSequence?,
    /** EXTRA_TEXT_LINES (stile inbox). */
    val lines: List<CharSequence> = emptyList(),
    /** Ultimo messaggio di una MessagingStyle. */
    val lastMessage: CharSequence? = null,
)

object NotificationText {
    const val MAX_CHARS = 10_000

    /** Titolo + corpo (ultimo messaggio se è una conversazione), troncato; null se non c'è testo. */
    fun compose(n: NotifData): String? {
        fun clean(c: CharSequence?) = c?.toString()?.trim().orEmpty()
        val body = listOf(clean(n.lastMessage), clean(n.bigText), clean(n.lines.lastOrNull()), clean(n.text)).firstOrNull { it.isNotEmpty() }.orEmpty()
        val title = clean(n.title)
        val full = when {
            body.isEmpty() -> title
            title.isEmpty() || body.startsWith(title) -> body
            else -> "$title\n$body"
        }
        return full.take(MAX_CHARS).ifBlank { null }
    }
}

/**
 * Filtri e limiti dell'analisi automatica. Stato solo in memoria (mai su disco): cache dei duplicati a breve termine
 * e ultimo avviso per app. Non è thread-safe di per sé: chiamare da un solo thread (o sincronizzare fuori).
 */
class NotificationGate(
    private val selfPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dedupeMs: Long = 60_000,
    private val alertIntervalMs: Long = 10_000,
    private val maxEntries: Int = 64,
    private val maxAnalysesPerMinute: Int = 30,
    private val loudIntervalMs: Long = 120_000,
) {
    private val seen = LinkedHashMap<String, Long>()
    private val lastAlert = HashMap<String, Long>()

    /** Testo da analizzare, oppure null se la notifica va ignorata. */
    @Synchronized
    fun accept(n: NotifData, monitored: Set<String>): String? {
        if (n.pkg == selfPackage || n.pkg !in monitored) return null
        if (n.ongoing || n.groupSummary) return null
        val text = NotificationText.compose(n) ?: return null
        val now = clock()
        val key = digest(n.pkg + "0000" + text)
        seen.entries.removeAll { now - it.value > dedupeMs }
        if (seen.containsKey(key)) return null
        seen[key] = now
        while (seen.size > maxEntries) seen.remove(seen.keys.first())
        return text
    }

    private var windowStart = Long.MIN_VALUE
    private var windowCount = 0
    private var lastLoud = Long.MIN_VALUE

    /** Budget aggregato: al massimo [maxAnalysesPerMinute] analisi al minuto in totale; oltre si salta fino al minuto dopo. */
    @Synchronized
    fun allowAnalysis(): Boolean {
        val now = clock()
        if (windowStart == Long.MIN_VALUE || now - windowStart >= 60_000) { windowStart = now; windowCount = 0 }
        if (windowCount >= maxAnalysesPerMinute) return false
        windowCount++
        return true
    }

    /** Un solo avviso con suono/heads-up ogni [loudIntervalMs] in totale; gli altri vanno sul canale silenzioso. */
    @Synchronized
    fun allowLoud(): Boolean {
        val now = clock()
        if (lastLoud != Long.MIN_VALUE && now - lastLoud < loudIntervalMs) return false
        lastLoud = now
        return true
    }
    @Synchronized
    fun allowAlert(pkg: String): Boolean { // al massimo un avviso ogni alertIntervalMs per app
        val now = clock()
        val last = lastAlert[pkg]
        if (last != null && now - last < alertIntervalMs) return false
        lastAlert[pkg] = now
        return true
    }

    companion object {
        fun shouldAlert(level: Level) = level != Level.NESSUN_SEGNALE
    }
}

/** Stato reale della protezione automatica, da mostrare all'utente. */
enum class AutoStatus { OFF, NEEDS_ACCESS, ALERTS_BLOCKED, ACTIVE }

fun autoStatus(wanted: Boolean, accessGranted: Boolean, alertsAllowed: Boolean): AutoStatus = when {
    !wanted -> AutoStatus.OFF
    !accessGranted -> AutoStatus.NEEDS_ACCESS
    !alertsAllowed -> AutoStatus.ALERTS_BLOCKED
    else -> AutoStatus.ACTIVE
}

/** Testi degli avvisi tenuti SOLO in memoria (max pochi, a scadenza): l'Intent porta soltanto una chiave casuale. */
class PendingAlertTexts(private val clock: () -> Long = System::currentTimeMillis, private val ttlMs: Long = 5 * 60_000L, private val max: Int = 5) {
    private val map = LinkedHashMap<String, Pair<Long, String>>()

    @Synchronized
    fun put(text: String): String {
        val now = clock()
        map.entries.removeAll { now - it.value.first > ttlMs }
        val key = java.util.UUID.randomUUID().toString()
        map[key] = now to text
        while (map.size > max) map.remove(map.keys.first())
        return key
    }

    /** Restituisce e rimuove il testo; null se scaduto, già aperto o sconosciuto. */
    @Synchronized
    fun take(key: String?): String? {
        val e = map.remove(key ?: return null) ?: return null
        return e.second.takeIf { clock() - e.first <= ttlMs }
    }

    companion object { val shared = PendingAlertTexts() }
}

/** SHA-256 (primi 16 byte, esadecimale): chiave di dedupe robusta a collisioni costruite ad arte. */
internal fun digest(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

/** Id stabile per app e conversazione: un nuovo avviso della stessa conversazione aggiorna quello esistente. */
fun alertId(pkg: String, title: CharSequence?): Int = 1000 + (digest(pkg + "\u0000" + title.toString()).take(6).toInt(16) and 0xffff)
