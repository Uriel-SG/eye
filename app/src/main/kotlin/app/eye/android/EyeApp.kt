package app.eye.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EyeApp : Application() {
    lateinit var container: AppContainer
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (app.eye.android.core.ai.isAiProcess(this)) return // processo ":ai": solo il modello, niente app
        container = AppContainer(this)
        app.eye.android.core.feed.FeedScheduler.scheduleRules(this)
        // il servizio notifiche è attivo solo se la Protezione automatica è accesa (V3-06)
        appScope.launch {
            container.settings.settings.map { it.autoProtect }.distinctUntilChanged()
                .collect { app.eye.android.core.notif.ListenerControl.setEnabled(this@EyeApp, it) }
        }
        appScope.launch { container.reloadCampaigns() }
        // Avvisi nuove campagne: attivi solo se l'utente accende il toggle (spento di default).
        appScope.launch {
            container.settings.settings.map { it.alertCampaigns }.distinctUntilChanged()
                .collect { app.eye.android.core.feed.FeedScheduler.setCampaignAlerts(this@EyeApp, it) }
        }
        appScope.launch {
            container.history.purge() // GDPR-07: purge all'avvio
            app.eye.android.core.ShareCard.clearCache(this@EyeApp) // F-05
        }
    }
}
