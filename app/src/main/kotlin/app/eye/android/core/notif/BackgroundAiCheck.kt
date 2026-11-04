package app.eye.android.core.notif

import android.content.SharedPreferences
import app.eye.android.EyeApp
import app.eye.android.core.Redactor
import app.eye.android.core.ai.AiConfig
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiRequests
import app.eye.android.core.ai.AiService
import app.eye.android.data.KeyVault
import app.eye.engine.ActionVector
import app.eye.engine.Level
import app.eye.engine.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Contatori su disco (solo numeri, nessun contenuto): finestra oraria e giornaliera delle chiamate AI in background. */
interface CounterStore {
    fun get(key: String): Long
    fun put(key: String, value: Long)
}

class PrefsCounterStore(private val prefs: SharedPreferences) : CounterStore {
    override fun get(key: String) = prefs.getLong(key, 0L)
    override fun put(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
}

/** Tetto rigido: al massimo [perHour] chiamate all'ora e [perDay] al giorno (finestre fisse che ripartono da sole). */
class AiCallLimiter(private val store: CounterStore, private val clock: () -> Long = System::currentTimeMillis,
    private val perHour: Int = 20, private val perDay: Int = 100) {
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        val h = window("h", now, HOUR_MS)
        val d = window("d", now, DAY_MS)
        if (h >= perHour || d >= perDay) return false
        store.put("h_n", h + 1L)
        store.put("d_n", d + 1L)
        return true
    }

    /** Conteggio della finestra [name] (la riavvia se scaduta). */
    private fun window(name: String, now: Long, len: Long): Long {
        val start = store.get(name + "_t")
        if (start == 0L || now - start >= len || now < start) {
            store.put(name + "_t", now)
            store.put(name + "_n", 0L)
            return 0L
        }
        return store.get(name + "_n")
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}

/**
 * Controllo AI in background per chi ha una chiave e «verifica prima di un avviso» attivo: oltre a poter FERMARE un avviso,
 * l'AI puo' farne partire uno per un messaggio dubbio che chiede un'azione e che le regole non hanno fatto avvisare.
 * Solo additivo: qualsiasi errore, limite o parere incerto = nessun avviso. Testo oscurato come nella verifica prima dell'avviso.
 */
object BackgroundAiCheck {
    /** TRUFFA con confidenza >= 0.7, oppure SOSPETTO con confidenza >= 0.9. */
    const val SCAM_CONFIDENCE = 0.7
    const val SUSPECT_CONFIDENCE = 0.9

    private val WEAK_VECTORS = setOf(ActionVector.LINK_OFFICIAL, ActionVector.LINK_BARE)

    /**
     * Vale la pena chiedere all'AI: c'e' un'azione richiesta (chiamare, cliccare, pagare, codici, rispondere, installare;
     * i soli link a siti ufficiali o «nudi» non bastano) E almeno un indizio (un motivo delle regole, un importo o un IBAN).
     * Mai per NESSUN_SEGNALE senza azione.
     */
    fun eligible(v: Verdict): Boolean =
        v.vectors.any { it !in WEAK_VECTORS } &&
            (v.reasons.isNotEmpty() || v.extracted.amounts.isNotEmpty() || v.extracted.ibans.isNotEmpty())

    /** Livello dell'avviso deciso dal parere dell'AI, o null (nessun avviso). */
    fun levelFor(outcome: AiOutcome?): Level? {
        val op = (outcome as? AiOutcome.Success)?.opinion ?: return null
        if (op.confidence.isNaN()) return null
        return when {
            op.judgement == AiJudgement.TRUFFA && op.confidence >= SCAM_CONFIDENCE -> Level.PROBABILE_TRUFFA
            op.judgement == AiJudgement.SOSPETTO && op.confidence >= SUSPECT_CONFIDENCE -> Level.SOSPETTO
            else -> null
        }
    }

    /** Bloccante (thread di lavoro del listener). Mai lancia. */
    fun check(app: EyeApp, text: String, verdict: Verdict, limiter: AiCallLimiter? = null): Level? = try {
        if (!eligible(verdict)) null
        else {
            val st = runBlocking { app.container.settings.settings.first() }
            val key = if (st.aiVerifyBeforeAlert && st.aiConfigured) st.aiKeyCipher?.let { KeyVault.decrypt(it) } else null
            val lim = limiter ?: AiCallLimiter(PrefsCounterStore(app.applicationContext.getSharedPreferences("ai_bg_counters", android.content.Context.MODE_PRIVATE)))
            if (key == null || !lim.tryAcquire()) null
            else {
                val cfg = AiConfig(st.aiProvider, st.aiModel, st.aiBaseUrl, key)
                val safe = Redactor.redactKeepLinks(text)
                val svc = AiService()
                val host = AiRequests.build(cfg, "x")?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                levelFor(AiVerifyRetry({ host != null && runCatching { java.net.InetAddress.getByName(host) }.isSuccess }).run { ms -> svc.ask(cfg, safe, quickMs = ms) })
            }
        }
    } catch (_: Exception) {
        null
    }
}
