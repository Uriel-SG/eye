package app.eye.android.core.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.eye.android.EyeApp
import app.eye.android.core.notif.CampaignNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val Context.container get() = (applicationContext as EyeApp).container

/** Aggiornamento di regole, campagne e elenco siti malevoli per tutti: ogni 24 h con rete. Nessun dato inviato. */
class RulesUpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext.container
        val all = c.feedUpdater.updateAll(FeedUpdater.FREE_MIN_INTERVAL_MS) // regole, campagne, siti malevoli
        if (all.rules is FeedResult.Updated) c.reloadRules()
        if (all.blocklist is FeedResult.Updated) c.reloadBlocklist()
        if (all.numbers is FeedResult.Updated) c.reloadNumberlist()
        if (all.campaigns is FeedResult.Updated) c.reloadCampaigns()
        app.eye.android.core.notif.UpdateAvailableNotifier.check(applicationContext, c.settings) // nuova versione dell'app: una notifica discreta
        // solo la rete assente si riprova (backoff esponenziale di WorkManager); verifiche fallite: si aspetta il prossimo ciclo
        val r = all.summary
        if (r is FeedResult.Failed && r.why == FeedFailure.NETWORK) Result.retry() else Result.success()
    }
}

/**
 * Modello AI aggiornabile (feed firmato EYE-MODEL-v1): solo con rete NON a consumo, telefono in carica e batteria non scarica
 * (il file pesa decine di MB). Verifiche e autotest in processo isolato prima di attivarlo: vedi [ModelUpdater].
 */
class ModelUpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val r = applicationContext.container.modelUpdater.update()
        if (r is FeedResult.Failed && r.why == FeedFailure.NETWORK) Result.retry() else Result.success()
    }
}

/** Solo se l'utente li ha attivati: ogni 12 h controlla le campagne e avvisa per quelle nuove. */
class CampaignAlertWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext.container
        val r = c.feedUpdater.updateCampaigns()
        if (r is FeedResult.Failed && r.why == FeedFailure.NETWORK) return@withContext Result.retry()
        c.reloadCampaigns()
        val state = c.feedState
        val all = c.campaigns.value
        val seen = state.seenCampaignIds()
        if (seen == null) { // prima volta: parte dallo stato attuale, senza raffica di avvisi
            state.setSeenCampaignIds(all.map { it.id }.toSet())
            return@withContext Result.success()
        }
        val fresh = NewCampaigns.pick(all, seen, state.notifiedCampaignIds())
        fresh.forEach { CampaignNotifier.post(applicationContext, it.title) }
        if (fresh.isNotEmpty()) state.setNotifiedCampaignIds(state.notifiedCampaignIds() + fresh.map { it.id })
        Result.success()
    }
}

object FeedScheduler {
    private const val RULES = "eye_rules_update"
    private const val ALERTS = "eye_campaign_alerts"
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

    private const val MODEL = "eye_model_update"
    private val modelConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build() // decisione utente: anche con dati mobili e senza carica

    fun scheduleModel(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ModelUpdateWorker>(24, TimeUnit.HOURS).setConstraints(modelConstraints).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(MODEL, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun scheduleRules(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RulesUpdateWorker>(24, TimeUnit.HOURS).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(RULES, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Attivo solo se il toggle in Impostazioni è acceso. */
    fun setCampaignAlerts(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork(ALERTS); return }
        val req = PeriodicWorkRequestBuilder<CampaignAlertWorker>(12, TimeUnit.HOURS).setConstraints(net).build()
        wm.enqueueUniquePeriodicWork(ALERTS, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
