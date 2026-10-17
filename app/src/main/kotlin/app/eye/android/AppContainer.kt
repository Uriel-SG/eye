package app.eye.android

import android.content.Context
import androidx.room.Room
import app.eye.android.core.feed.FeedConfig
import app.eye.android.core.feed.FeedUpdater
import app.eye.android.core.feed.PrefsFeedState
import app.eye.android.core.feed.SwappableAnalyzer
import app.eye.android.core.feed.UrlConnectionFeedHttp
import app.eye.android.data.EyeDb
import app.eye.android.data.HistoryRepository
import app.eye.android.core.ai.RemoteTextClassifier
import app.eye.android.data.SettingsStore
import app.eye.engine.Campaign
import app.eye.engine.CampaignFeedVerifier
import app.eye.engine.FileRulePackStore
import app.eye.engine.RuleEngine
import app.eye.engine.RulePackVerifier
import app.eye.engine.campaignStore
import app.eye.engine.loadCampaigns
import app.eye.engine.isStale
import app.eye.engine.loadVerified
import app.eye.engine.staleDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/** Unico punto di composizione (motore reale: regole installate e firmate se valide, altrimenti quelle incluse). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    private val feedDir = File(appContext.filesDir, "feeds")
    val rulesStore = FileRulePackStore(feedDir)
    val campaignStore = campaignStore(feedDir)
    val feedState = PrefsFeedState(appContext)
    private val bundled = RuleEngine.loadBundled()

    /** Il pacchetto installato vale solo se la firma è valida (riverificata) e la versione supera quella inclusa. */
    private val _rulesStaleDays = MutableStateFlow<Long?>(null)
    /** Giorni dall'emissione se le regole installate sono scadute (mai un rifiuto: restano in uso); null = nessun avviso. */
    val rulesStaleDays: StateFlow<Long?> = _rulesStaleDays

    /** Modello AI sul telefono (segnale aggiuntivo): si carica da solo, in background; se fallisce restano le regole. */
    val classifier = RemoteTextClassifier(appContext).also { it.warmUp() }

    private fun bestEngine(): RuleEngine {
        val installed = runCatching { rulesStore.loadVerified(RulePackVerifier()) }.getOrNull()
        val use = if (installed != null && installed.version > bundled.version) installed else bundled
        val now = java.time.Instant.now()
        _rulesStaleDays.value = if (use.isStale(now)) use.staleDays(now) else null
        return RuleEngine(use, classifier = classifier)
    }

    val analyzer = SwappableAnalyzer(bestEngine())
    fun reloadRules() { analyzer.delegate = bestEngine() }

    val feedUpdater = FeedUpdater(
        UrlConnectionFeedHttp(FeedConfig.allowCleartext), FeedConfig.baseUrl, rulesStore, campaignStore, feedState, bundled.version,
    )

    private val _campaigns = MutableStateFlow<List<Campaign>>(emptyList())
    /** Campagne del feed firmato già installato (vuoto se mai scaricato). */
    val campaigns: StateFlow<List<Campaign>> = _campaigns
    private val _campaignStaleDays = MutableStateFlow<Long?>(null)
    val campaignStaleDays: StateFlow<Long?> = _campaignStaleDays
    fun reloadCampaigns() {
        val feed = runCatching { campaignStore.loadCampaigns(CampaignFeedVerifier()) }.getOrNull()
        val now = java.time.Instant.now()
        _campaignStaleDays.value = if (feed != null && feed.isStale(now)) feed.staleDays(now) else null
        _campaigns.value = feed?.campaigns.orEmpty()
    }

    private val db = Room.databaseBuilder(context, EyeDb::class.java, "eye.db")
        .addMigrations(app.eye.android.data.MIGRATION_2_3)
        .fallbackToDestructiveMigrationFrom(true, 1) // solo lo schema v1 (mai pubblicato) può essere ricreato
        .build()
    val settings = SettingsStore(context)
    val history = HistoryRepository(
        db.history(), crypto = app.eye.android.data.HistoryVault,
        retentionDays = settings.settings.map { it.retention.days }.distinctUntilChanged(),
    )
}
