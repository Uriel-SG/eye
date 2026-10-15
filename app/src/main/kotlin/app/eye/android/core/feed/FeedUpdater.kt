package app.eye.android.core.feed

import app.eye.engine.CampaignFeedVerifier
import app.eye.engine.FeedEntry
import app.eye.engine.FeedIndex
import app.eye.engine.RulePackStore
import app.eye.engine.RulePackVerifier
import app.eye.engine.install
import app.eye.engine.installCampaigns
import java.io.IOException

/** Stato leggero dei feed (ETag, ultimo controllo, id già visti). Mai dati dell'utente. */
interface FeedState {
    fun etag(consumer: String): String?
    fun setEtag(consumer: String, etag: String?)
    fun lastCampaignCheck(): Long
    fun setLastCampaignCheck(t: Long)
    /** null = mai inizializzato. */
    fun seenCampaignIds(): Set<String>?
    fun setSeenCampaignIds(ids: Set<String>)
    fun notifiedCampaignIds(): Set<String>
    fun setNotifiedCampaignIds(ids: Set<String>)
    fun clear()
}

enum class FeedFailure { NETWORK, BAD_INDEX, BAD_DOWNLOAD, REJECTED }

sealed interface FeedResult {
    data class Updated(val version: Long) : FeedResult
    data object UpToDate : FeedResult
    data class Failed(val why: FeedFailure) : FeedResult
}

/**
 * Scarica e installa i feed firmati (regole e campagne) come da engine/FEEDS.md. Bloccante: da chiamare fuori dal main
 * thread. La sicurezza sta nella firma e nell'anti-rollback del motore; qui solo limiti, ETag e integrità.
 * Qualsiasi errore lascia invariato lo stato precedente.
 */
class FeedUpdater(
    private val http: FeedHttp,
    baseUrl: String,
    private val rulesStore: RulePackStore,
    private val campaignStore: RulePackStore,
    private val state: FeedState,
    private val bundledRulesVersion: Long,
    private val rulesVerifier: RulePackVerifier = RulePackVerifier(),
    private val campaignVerifier: CampaignFeedVerifier = CampaignFeedVerifier(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private sealed interface Idx {
        data object Same : Idx
        class New(val index: FeedIndex, val etag: String?) : Idx
        class Bad(val why: FeedFailure) : Idx
    }

    private fun fetchIndex(consumer: String): Idx = try {
        when (val r = http.get(base + "version.json", state.etag(consumer), FeedIndex.MAX_BYTES)) {
            FeedResponse.NotModified -> Idx.Same
            is FeedResponse.Ok -> FeedIndex.parse(r.bytes).fold({ Idx.New(it, r.etag) }, { Idx.Bad(FeedFailure.BAD_INDEX) })
        }
    } catch (e: IOException) {
        Idx.Bad(FeedFailure.NETWORK)
    }

    private fun download(entry: FeedEntry, max: Int): ByteArray? = try {
        (http.get(base + entry.url, null, max) as? FeedResponse.Ok)?.bytes?.takeIf { entry.matches(it) }
    } catch (e: IOException) {
        null
    }

    fun updateRules(): FeedResult {
        val idx = when (val i = fetchIndex(RULES)) {
            Idx.Same -> return FeedResult.UpToDate
            is Idx.Bad -> return FeedResult.Failed(i.why)
            is Idx.New -> i
        }
        val entry = idx.index.rules
        if (entry.version <= maxOf(rulesStore.installedVersion(), bundledRulesVersion)) {
            state.setEtag(RULES, idx.etag)
            return FeedResult.UpToDate
        }
        val bytes = download(entry, RulePackVerifier.MAX_ENVELOPE) ?: return FeedResult.Failed(FeedFailure.BAD_DOWNLOAD)
        val set = rulesStore.install(rulesVerifier, bytes).getOrNull() ?: return FeedResult.Failed(FeedFailure.REJECTED)
        state.setEtag(RULES, idx.etag)
        return FeedResult.Updated(set.version)
    }

    /** [minIntervalMs]: non ricontrolla se l'ultimo controllo riuscito è più recente (Free: 1 ora). */
    fun updateCampaigns(minIntervalMs: Long = 0): FeedResult {
        val now = clock()
        if (minIntervalMs > 0 && now - state.lastCampaignCheck() in 0 until minIntervalMs) return FeedResult.UpToDate
        val idx = when (val i = fetchIndex(CAMPAIGNS)) {
            Idx.Same -> { state.setLastCampaignCheck(now); return FeedResult.UpToDate }
            is Idx.Bad -> return FeedResult.Failed(i.why)
            is Idx.New -> i
        }
        val entry = idx.index.campaigns
        if (entry.version <= campaignStore.installedVersion()) {
            state.setEtag(CAMPAIGNS, idx.etag)
            state.setLastCampaignCheck(now)
            return FeedResult.UpToDate
        }
        val bytes = download(entry, CampaignFeedVerifier.MAX_ENVELOPE) ?: return FeedResult.Failed(FeedFailure.BAD_DOWNLOAD)
        val feed = campaignStore.installCampaigns(campaignVerifier, bytes).getOrNull() ?: return FeedResult.Failed(FeedFailure.REJECTED)
        state.setEtag(CAMPAIGNS, idx.etag)
        state.setLastCampaignCheck(now)
        return FeedResult.Updated(feed.version)
    }

    companion object {
        const val RULES = "rules"
        const val CAMPAIGNS = "campaigns"
        const val FREE_MIN_INTERVAL_MS = 60 * 60 * 1000L
    }
}
