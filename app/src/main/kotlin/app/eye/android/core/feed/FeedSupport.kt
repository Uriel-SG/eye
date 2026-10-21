package app.eye.android.core.feed

import android.content.Context
import app.eye.engine.Analyzer
import app.eye.engine.Campaign
import app.eye.engine.Input
import app.eye.engine.Verdict

/** Analyzer sostituibile a caldo: dopo un aggiornamento regole si cambia il delegate senza riavviare. */
class SwappableAnalyzer(initial: Analyzer) : Analyzer {
    @Volatile var delegate: Analyzer = initial
    override fun analyze(input: Input): Verdict = delegate.analyze(input)
}

/** Stato dei feed su SharedPreferences dedicate (solo ETag, date e id di campagne pubbliche). */
class PrefsFeedState(context: Context) : FeedState {
    private val p = context.applicationContext.getSharedPreferences("feed_state", Context.MODE_PRIVATE)
    override fun etag(consumer: String) = p.getString("etag_$consumer", null)
    override fun setEtag(consumer: String, etag: String?) { p.edit().putString("etag_$consumer", etag).apply() }
    override fun lastCampaignCheck() = p.getLong("campaigns_checked", 0L)
    override fun setLastCampaignCheck(t: Long) { p.edit().putLong("campaigns_checked", t).apply() }
    override fun seenCampaignIds(): Set<String>? = p.getStringSet("seen_ids", null)?.toSet()
    override fun setSeenCampaignIds(ids: Set<String>) { p.edit().putStringSet("seen_ids", ids).apply() }
    override fun notifiedCampaignIds(): Set<String> = p.getStringSet("notified_ids", null)?.toSet().orEmpty()
    override fun setNotifiedCampaignIds(ids: Set<String>) { p.edit().putStringSet("notified_ids", ids).apply() }
    override fun lastFeedUpdate() = p.getLong("feeds_updated", 0L)
    override fun setLastFeedUpdate(t: Long) { p.edit().putLong("feeds_updated", t).apply() }
    override fun clear() { p.edit().clear().apply() }
}

object NewCampaigns {
    /**
     * Campagne «in corso» da notificare: né già viste dall'utente nel catalogo né già notificate.
     * [seen] null = prima volta: nessuna notifica (si parte dallo stato attuale).
     */
    fun pick(campaigns: List<Campaign>, seen: Set<String>?, notified: Set<String>, max: Int = 3): List<Campaign> {
        if (seen == null) return emptyList()
        return campaigns.filter { it.status == "in_corso" && it.id !in seen && it.id !in notified }
            .sortedByDescending { it.updatedAt }.take(max)
    }
}
