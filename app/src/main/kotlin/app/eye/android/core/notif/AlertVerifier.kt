package app.eye.android.core.notif

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.eye.android.BuildConfig
import app.eye.android.EyeApp
import app.eye.android.R
import app.eye.android.core.Redactor
import app.eye.android.core.ai.AiConfig
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiRequests
import app.eye.android.core.ai.AiService
import app.eye.android.data.KeyVault
import app.eye.android.data.VerdictJson
import app.eye.engine.Level
import app.eye.engine.Verdict
import app.eye.engine.llm.LegitGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Verifica opt-in con l'AI dell'utente prima di un avviso (testo oscurato, 8 s a tentativo, un secondo tentativo se la rete
 * torna, vedi [AiVerifyRetry]). Bloccante: chiamare da un thread di lavoro. Fail-safe: qualsiasi errore = avviso.
 * Se l'AI ritiene il messaggio legittimo l'analisi finisce in cronologia (se attiva) e l'esito e' [VerifyDecision.Dismiss].
 */
object AlertVerifier {
    fun verify(app: EyeApp, text: String, verdict: Verdict, deferrable: Boolean): VerifyDecision = try {
        if (verdict.level == Level.NESSUN_SEGNALE || LegitGate.hasGraveSignals(verdict)) VerifyDecision.Alert(AlertReason.GRAVE)
        else {
            val ctx = app.applicationContext
            val st = runBlocking { app.container.settings.settings.first() }
            val key = if (st.aiVerifyBeforeAlert && st.aiConfigured) st.aiKeyCipher?.let { KeyVault.decrypt(it) } else null
            val out: AiOutcome? = if (key == null) null else {
                val cfg = AiConfig(st.aiProvider, st.aiModel, st.aiBaseUrl, key)
                val safe = Redactor.redactKeepLinks(text)
                val svc = AiService()
                val host = AiRequests.build(cfg, "x")?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                // senza permesso di stato rete: la rete "c'e'" se il nome del servizio si risolve
                AiVerifyRetry({ host != null && runCatching { java.net.InetAddress.getByName(host) }.isSuccess }).run { ms -> svc.ask(cfg, safe, quickMs = ms) }
            }
            val d = VerifyPolicy.decide(out, deferrable) { op -> LegitGate.mayDismiss(verdict, true, op.confidence) }
            if (d is VerifyDecision.Dismiss && st.saveHistory) runBlocking {
                val note = ctx.getString(R.string.alert_not_sent_note)
                app.container.history.save(
                    verdict.level.name, Redactor.preview(text), listOf(note) + verdict.reasons.map { it.text }, text, VerdictJson.encode(verdict),
                )
            }
            d
        }
    } catch (_: Exception) {
        VerifyDecision.Alert(AlertReason.AI_ERROR)
    }

    /** Telefono a riposo (Doze) o schermo spento: la rete di solito non c'e'. */
    fun deviceIdle(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm != null && (pm.isDeviceIdleMode || !pm.isInteractive)
    } catch (_: RuntimeException) {
        false
    }

    /** Pubblica l'avviso gia' salvato in [AlertStore] (stessa notifica di EyeNotificationListener). false se non pubblicato. */
    fun publish(app: EyeApp, id: Int, level: Level, pkg: String, loud: Boolean): Boolean {
        val label = MonitoredApps.presets(BuildConfig.DEBUG).firstOrNull { it.pkg == pkg }?.label ?: pkg
        return AlertNotifier.post(app.applicationContext, label, level, id, loud)
    }

    private const val TAG_VERIFY = "alert-verify-"
    private const val TAG_DEADLINE = "alert-deadline-"

    /** Due lavori unici: verifica con rete e scadenza (30 min, senza vincoli). Il primo che parte prende l'avviso. */
    fun schedule(ctx: Context, id: Int) {
        val wm = WorkManager.getInstance(ctx)
        val data = workDataOf(DeferredAlertWorker.KEY_ID to id)
        wm.enqueueUniqueWork(
            TAG_VERIFY + id, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DeferredAlertWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
        wm.enqueueUniqueWork(
            TAG_DEADLINE + id, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DeferredAlertWorker>()
                .setInputData(workDataOf(DeferredAlertWorker.KEY_ID to id, DeferredAlertWorker.KEY_DEADLINE to true))
                .setInitialDelay(VerifyPolicy.DEADLINE_MINUTES, TimeUnit.MINUTES).build(),
        )
    }

    fun cancelDeadline(ctx: Context, id: Int) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(TAG_DEADLINE + id) } }
    fun cancelVerify(ctx: Context, id: Int) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(TAG_VERIFY + id) } }
}

/**
 * Esegue un avviso rimandato. Prende l'avviso in modo atomico ([AlertStore.claimPending]); se e' gia' stato preso da altri
 * non fa nulla. Modo verifica: ripete la verifica AI e poi pubblica o scarta; modo scadenza: pubblica. Qualsiasi errore: avviso.
 */
class DeferredAlertWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? EyeApp ?: return@withContext Result.success()
        val id = inputData.getInt(KEY_ID, Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) return@withContext Result.success()
        val deadline = inputData.getBoolean(KEY_DEADLINE, false)
        val store = app.container.alerts
        val pending = store.claimPending(id) ?: return@withContext Result.success()
        val level = runCatching { Level.valueOf(pending.level) }.getOrDefault(Level.SOSPETTO)
        var post = true
        try {
            if (!deadline) {
                AlertVerifier.cancelDeadline(applicationContext, id)
                val verdict = pending.verdictJson?.let { VerdictJson.decode(it) }
                if (verdict != null) when (val d = AlertVerifier.verify(app, pending.text, verdict, deferrable = false)) {
                    VerifyDecision.Dismiss -> { store.discard(id); post = false }
                    is VerifyDecision.Alert -> store.setReason(id, d.reason.name)
                    is VerifyDecision.Defer -> Unit
                }
            } else AlertVerifier.cancelVerify(applicationContext, id)
        } catch (_: Exception) {
            post = true
        }
        if (post) {
            val loud = EyeNotificationListener.allowLoud(applicationContext.packageName)
            if (!AlertVerifier.publish(app, id, level, pending.origin ?: "", loud)) store.discard(id)
        }
        Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_DEADLINE = "deadline"
    }
}
