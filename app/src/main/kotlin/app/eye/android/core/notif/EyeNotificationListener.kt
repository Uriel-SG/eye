package app.eye.android.core.notif

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.eye.android.BuildConfig
import app.eye.android.EyeApp
import app.eye.engine.Input
import app.eye.engine.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Protezione automatica: legge titolo e testo delle notifiche delle sole app scelte, le analizza sul telefono e, se
 * serve, pubblica un avviso. Non salva e non invia nulla; nessun contenuto nei log.
 * Esportato solo perché lo richiede il sistema (protetto da BIND_NOTIFICATION_LISTENER_SERVICE).
 *
 * Carico: i filtri per pacchetto avvengono PRIMA di toccare gli extras; le notifiche utili vanno in una coda piccola
 * (scarta le più vecchie) servita da UN solo worker che analizza in linea (mai in parallelo); il budget di tempo è quello
 * cooperativo del motore (RuleEngine) e c'è un tetto aggregato di analisi al minuto.
 */
class EyeNotificationListener : NotificationListenerService() {
    private val app get() = application as EyeApp
    private var watcher: Job? = null

    override fun onListenerConnected() {
        watcher?.cancel()
        // set monitorato tenuto in memoria: con la protezione spenta è vuoto e nessun extra viene letto
        watcher = app.appScope.launch {
            app.container.settings.settings.collect { st ->
                active = if (st.autoProtect) MonitoredApps.active(BuildConfig.DEBUG, st.autoDisabledApps) else emptySet()
            }
        }
        ensureConsumer()
    }

    override fun onListenerDisconnected() {
        watcher?.cancel()
        active = emptySet()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val pkg = s.packageName
        if (pkg == packageName || pkg !in active) return // niente extras delle app non monitorate (minimizzazione)
        val data = runCatching { read(s) }.getOrNull() ?: return
        ensureConsumer()
        queue.trySend(data) // DROP_OLDEST: mai bloccante, mai coda illimitata
    }

    private fun ensureConsumer() {
        synchronized(Companion) {
            if (consumer?.isActive == true) return
            consumer = app.appScope.launch(worker) {
                for (n in queue) {
                    try {
                        process(n)
                    } catch (_: Exception) {
                        // nessun log di contenuti
                    }
                }
            }
        }
    }

    private fun process(n: NotifData) {
        val g = gate(packageName)
        val text = g.accept(n, active) ?: return
        if (!g.allowAnalysis()) return // tetto aggregato al minuto
        // Analisi in linea sul worker seriale: nessun parallelismo, anche se la precedente fosse lenta.
        val verdict = app.container.checker.analyze(Input(text, Source.SHARE, declaredSender = n.title?.toString()?.take(120), origin = n.pkg))
        if (!NotificationGate.shouldAlert(verdict.level) || !g.allowAlert(n.pkg)) return
        val label = MonitoredApps.presets(BuildConfig.DEBUG).firstOrNull { it.pkg == n.pkg }?.label ?: n.pkg
        val id = alertId(n.pkg, n.title)
        // cifrato su disco finché l'avviso non è aperto o scartato; senza cifratura non si avvisa (mai testo in chiaro)
        if (!app.container.alerts.put(id, text, verdict.level.name, n.pkg, n.title?.toString()?.take(120))) return
        if (!AlertNotifier.post(applicationContext, label, verdict.level, id, loud = g.allowLoud())) app.container.alerts.discard(id)
    }

    private fun read(sbn: StatusBarNotification): NotifData {
        val n = sbn.notification
        val x: Bundle = n.extras
        val lines = runCatching { x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull { it != null }?.let { listOf(cap(it)!!) }.orEmpty() }.getOrDefault(emptyList())
        val last = runCatching {
            @Suppress("DEPRECATION")
            val arr = x.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Bundle>()
            cap(arr?.lastOrNull()?.getCharSequence("text")) // chiave «text» dei messaggi MessagingStyle
        }.getOrNull()
        return NotifData(
            pkg = sbn.packageName,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            title = cap(x.getCharSequence(Notification.EXTRA_TITLE)),
            text = cap(x.getCharSequence(Notification.EXTRA_TEXT)),
            bigText = cap(x.getCharSequence(Notification.EXTRA_BIG_TEXT)),
            lines = lines,
            lastMessage = last,
            links = runCatching { spanLinks(listOf(x.getCharSequence(Notification.EXTRA_TEXT), x.getCharSequence(Notification.EXTRA_BIG_TEXT)) + lines + listOf(last)) }.getOrDefault(emptyList()),
        )
    }

    /** URL reali dagli URLSpan (i ClickableSpan senza URL si ignorano): (testo visibile, url). */
    private fun spanLinks(parts: List<CharSequence?>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (c in parts) {
            val sp = (cap(c) as? android.text.Spanned) ?: continue
            for (u in sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java).take(20)) {
                val url = u.url ?: continue
                val a = sp.getSpanStart(u); val b = sp.getSpanEnd(u)
                out += (if (a in 0..b && b <= sp.length) sp.subSequence(a, b).toString() else "") to url
            }
        }
        return out
    }

    /** Taglia PRIMA di copiare: un'app ostile non può farci allocare megabyte. */
    private fun cap(c: CharSequence?): CharSequence? =
        if (c != null && c.length > NotificationText.MAX_CHARS) c.subSequence(0, NotificationText.MAX_CHARS) else c

    companion object {
        /** Pacchetti da controllare; vuoto = protezione spenta. */
        @Volatile private var active: Set<String> = emptySet()

        // coda piccola, scarta le più vecchie
        private val queue = Channel<NotifData>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        @Volatile private var consumer: Job? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        private val worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

        @Volatile private var gateInstance: NotificationGate? = null
        private fun gate(pkg: String) = gateInstance ?: synchronized(this) { gateInstance ?: NotificationGate(pkg).also { gateInstance = it } }
    }
}
