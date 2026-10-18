package app.eye.android.core.notif

import android.content.Context
import android.content.SharedPreferences
import app.eye.android.core.MiniJson
import app.eye.android.data.HistoryVault
import app.eye.android.data.TextCrypto

/** Dove stanno i dati degli avvisi (SharedPreferences in app, una mappa nei test). */
interface AlertBackend {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

class PrefsAlertBackend(private val prefs: SharedPreferences) : AlertBackend {
    override fun get(key: String) = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/**
 * Testo e livello degli avvisi della Protezione automatica, indicizzati dall'id dell'avviso. Il testo sta su disco CIFRATO
 * (AES-GCM, Keystore) e vale finché l'utente non apre o scarta l'avviso ([take]/[discard]); [purge] elimina i dati orfani
 * oltre un'età massima. Il file è escluso dai backup. Nessun log di contenuti.
 */
class AlertStore(
    private val backend: AlertBackend,
    private val crypto: TextCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** false se non si è potuto cifrare: in quel caso non si salva nulla in chiaro. */
    @Synchronized
    fun put(id: Int, text: String, level: String, origin: String? = null, sender: String? = null): Boolean {
        val enc = runCatching { crypto.encrypt(MiniJson.stringify(mapOf("t" to text, "l" to level, "o" to origin, "s" to sender))) }.getOrNull() ?: return false
        backend.put(DATA + id, enc)
        backend.put(TIME + id, clock().toString())
        return true
    }

    /** Testo, app di origine e mittente di un avviso (origine/mittente null per avvisi salvati da versioni precedenti). */
    class AlertData(val text: String, val origin: String?, val sender: String?)

    /** Restituisce i dati e li cancella; null se già aperto/scartato, sconosciuto o illeggibile. */
    @Synchronized
    fun takeAlert(id: Int): AlertData? {
        val enc = backend.get(DATA + id)
        discard(id)
        val plain = enc?.let { crypto.decrypt(it) } ?: return null
        val m = MiniJson.parse(plain) as? Map<*, *> ?: return null
        return AlertData(m["t"] as? String ?: return null, m["o"] as? String, m["s"] as? String)
    }

    @Synchronized
    fun take(id: Int): String? = takeAlert(id)?.text

    @Synchronized
    fun discard(id: Int) {
        backend.remove(DATA + id)
        backend.remove(TIME + id)
    }

    /** Cancella i dati più vecchi di [maxAgeMs] (o senza data). Restituisce quanti avvisi ha tolto. */
    @Synchronized
    fun purge(maxAgeMs: Long = MAX_AGE_MS): Int {
        val now = clock()
        var n = 0
        for (k in backend.keys()) {
            if (!k.startsWith(DATA)) continue
            val id = k.removePrefix(DATA).toIntOrNull() ?: continue
            val at = backend.get(TIME + id)?.toLongOrNull()
            if (at == null || now - at > maxAgeMs) { discard(id); n++ }
        }
        for (k in backend.keys()) { // date rimaste senza dati
            if (!k.startsWith(TIME)) continue
            val id = k.removePrefix(TIME).toIntOrNull() ?: continue
            if (backend.get(DATA + id) == null) backend.remove(k)
        }
        return n
    }

    @Synchronized
    fun count(): Int = backend.keys().count { it.startsWith(DATA) }

    companion object {
        private const val DATA = "d"
        private const val TIME = "t"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        fun create(ctx: Context) = AlertStore(
            PrefsAlertBackend(ctx.getSharedPreferences("pending_alerts", Context.MODE_PRIVATE)), HistoryVault,
        )
    }
}

/** Riceve lo scarto (swipe / «Cancella tutto») dell'avviso e ne cancella il dato. Non esportato; l'Intent porta solo l'id. */
class AlertDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != AlertNotifier.ACTION_DISMISS_ALERT) return
        val id = intent.getIntExtra(AlertNotifier.EXTRA_ID, Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) return
        (context.applicationContext as? app.eye.android.EyeApp)?.container?.alerts?.discard(id)
    }
}
