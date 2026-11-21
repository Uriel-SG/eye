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
    @Volatile private var lastPurge = 0L
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (app.eye.android.core.ai.isAiProcess(this)) return // processo ":ai": solo il modello, niente app
        container = AppContainer(this)
        app.eye.android.core.feed.FeedScheduler.scheduleRules(this)
        app.eye.android.core.feed.FeedScheduler.scheduleModel(this)
        app.eye.android.core.feed.PurgeScheduler.schedule(this) // pulizia giornaliera anche senza riavvio
        // al ritorno in primo piano, al massimo una volta al giorno
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                val now = System.currentTimeMillis()
                if (!app.eye.android.core.feed.PurgeThrottle.due(lastPurge, now)) return
                lastPurge = now
                appScope.launch { runCatching { container.purgeStorage() } }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
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
            lastPurge = System.currentTimeMillis()
            container.purgeStorage() // GDPR-07: purge all'avvio (cronologia e avvisi orfani oltre 30 giorni)
            app.eye.android.core.ShareCard.clearCache(this@EyeApp) // F-05
        }
    }
}
