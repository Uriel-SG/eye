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
import app.eye.engine.BlocklistHolder
import app.eye.engine.Campaign
import app.eye.engine.CampaignFeedVerifier
import app.eye.engine.FileRulePackStore
import app.eye.engine.RuleEngine
import app.eye.engine.RulePackVerifier
import app.eye.engine.blocklistStore
import app.eye.engine.campaignStore
import app.eye.engine.loadCampaigns
import app.eye.engine.isStale
import app.eye.engine.loadVerified
import app.eye.engine.staleDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/** Unico punto di composizione (motore reale: regole installate e firmate se valide, altrimenti quelle incluse). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    private val feedDir = File(appContext.filesDir, "feeds")
    val rulesStore = FileRulePackStore(feedDir)
    val campaignStore = campaignStore(feedDir)
    val blocklistStore = blocklistStore(feedDir)
    /** Elenco locale di siti malevoli noti: si carica alla prima interrogazione (anche dalle notifiche in background), offline. */
    val blocklist = BlocklistHolder(blocklistStore)
    val feedState = PrefsFeedState(appContext)
    private val bundled = RuleEngine.loadBundled()

    /** Il pacchetto installato vale solo se la firma è valida (riverificata) e la versione supera quella inclusa. */
    private val _rulesStaleDays = MutableStateFlow<Long?>(null)
    /** Giorni dall'emissione se le regole installate sono scadute (mai un rifiuto: restano in uso); null = nessun avviso. */
    val rulesStaleDays: StateFlow<Long?> = _rulesStaleDays
    private val _rulesVersion = MutableStateFlow(bundled.version)
    /** Versione delle regole in uso (incluse o aggiornate). */
    val rulesVersion: StateFlow<Long> = _rulesVersion

    /** Modello AI sul telefono (segnale aggiuntivo): si carica da solo, in background; se fallisce restano le regole. */
    val classifier = RemoteTextClassifier(appContext).also { it.warmUp() }

    private fun bestEngine(): RuleEngine {
        val installed = runCatching { rulesStore.loadVerified(RulePackVerifier()) }.getOrNull()
        val use = if (installed != null && installed.version > bundled.version) installed else bundled
        val now = java.time.Instant.now()
        _rulesStaleDays.value = if (use.isStale(now)) use.staleDays(now) else null
        lists = use.lists
        _rulesVersion.value = use.version
        return RuleEngine(use, classifier = classifier, reputation = blocklist)
    }

    /** Liste del motore in uso (popolari, ufficiali, shortener): escludono questi domini dal controllo dell'eta'. */
    @Volatile var lists: app.eye.engine.RuleLists = bundled.lists
        private set

    val analyzer = SwappableAnalyzer(bestEngine())
    fun reloadRules() { analyzer.delegate = bestEngine() }

    /** Riassunto dell'elenco siti malevoli per Impostazioni › Info; null = nessun elenco scaricato. Carica l'elenco: da thread secondario. */
    class BlocklistSummary(val entries: Int, val issuedAt: String?, val stale: Boolean)
    fun blocklistSummary(): BlocklistSummary? = blocklist.current()?.let { BlocklistSummary(it.size, it.issuedAt, it.isStale(java.time.Instant.now())) }
    fun reloadBlocklist() = blocklist.reload()

    /** Eccezioni dell'utente (cifrate su disco). */
    val exceptions = app.eye.android.data.ExceptionStore.create(appContext)
    /** Regole + AI, poi le eccezioni dell'utente: è l'analizzatore usato da tutta l'app. */
    val checker: app.eye.engine.Analyzer = app.eye.engine.ExceptionAwareAnalyzer(analyzer, { exceptions.snapshot() })

    /** Espansione dei link accorciati (solo verso i servizi di accorciamento, mai il sito finale). */
    val shortLinks = app.eye.android.core.links.ShortLinkService(
        app.eye.engine.ShortLinkResolver(
            app.eye.android.core.links.UrlConnectionShortLinkTransport(FeedConfig.allowCleartext),
            { lists.shorteners.toHashSet() },
        ),
    )

    val feedUpdater = FeedUpdater(
        UrlConnectionFeedHttp(FeedConfig.allowCleartext), FeedConfig.baseUrl, rulesStore, campaignStore, feedState, bundled.version,
        blocklistStore = blocklistStore,
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

    /** Eta' dei domini (RDAP): cache e bootstrap in filesDir (esclusa dai backup), solo in primo piano. */
    val domainAge = app.eye.android.core.age.DomainAgeService(File(appContext.filesDir, "rdap"))

    private val db = Room.databaseBuilder(context, EyeDb::class.java, "eye.db")
        .addMigrations(app.eye.android.data.MIGRATION_2_3, app.eye.android.data.MIGRATION_3_4, app.eye.android.data.MIGRATION_4_5, app.eye.android.data.MIGRATION_5_6, app.eye.android.data.MIGRATION_6_7)
        .fallbackToDestructiveMigrationFrom(true, 1) // solo lo schema v1 (mai pubblicato) può essere ricreato
        .build()
    /** Avvisi della Protezione automatica in attesa: testo cifrato su disco finché l'utente non apre o scarta l'avviso. */
    val alerts = app.eye.android.core.notif.AlertStore.create(appContext)
    val settings = SettingsStore(context)
    val history = HistoryRepository(
        db.history(), crypto = app.eye.android.data.HistoryVault,
        retentionDays = settings.settings.map { it.retention.days }.distinctUntilChanged(),
    )
}
