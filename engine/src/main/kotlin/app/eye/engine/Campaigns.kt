package app.eye.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class CampaignSource(val name: String, val url: String)

@Serializable
data class Campaign(
    /** Stabile nel tempo: l'app lo usa per «gia' letta» e per i deep link. */
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    /** Segnali per riconoscerla. */
    val signals: List<String>,
    /** Cosa fare. */
    val actions: List<String>,
    /** Esempio anonimizzato (domini inventati). */
    val example: String,
    /** yyyy-MM-dd */
    val firstSeen: String,
    val updatedAt: String,
    val source: CampaignSource,
    /** [CampaignFeedVerifier.STATUSES] */
    val status: String,
)

@Serializable
data class CampaignFeed(
    val version: Long,
    /** Ripete il keyId dell'envelope (verificato). */
    val keyId: String,
    /** Emissione e scadenza (ISO-8601 UTC) nel payload firmato; non usare mai `createdAt` dell'envelope. */
    val issuedAt: String,
    val expiresAt: String,
    val campaigns: List<Campaign>,
)

/**
 * Il pacchetto e' scaduto a [now]? Non e' un motivo di rifiuto: l'app continua a usarlo e mostra
 * «non aggiornato da N giorni» (vedi [staleDays]). Senza `expiresAt` (pacchetto incluso nell'APK) non e' mai scaduto.
 */
private fun stale(expiresAt: String?, now: Instant): Boolean =
    expiresAt != null && runCatching { now.isAfter(Instant.parse(expiresAt)) }.getOrDefault(false)

private fun daysSince(issuedAt: String?, now: Instant): Long? =
    issuedAt?.let { runCatching { Duration.between(Instant.parse(it), now).toDays().coerceAtLeast(0) }.getOrNull() }

fun RuleSet.isStale(now: Instant): Boolean = stale(expiresAt, now)
/** Giorni dall'emissione (data firmata), null per il pacchetto incluso. */
fun RuleSet.staleDays(now: Instant): Long? = daysSince(issuedAt, now)
fun CampaignFeed.isStale(now: Instant): Boolean = stale(expiresAt, now)
fun CampaignFeed.staleDays(now: Instant): Long? = daysSince(issuedAt, now)

/**
 * Verifica il feed «campagne in corso»: stesso envelope Ed25519 dei pacchetti regole ma `format` 2 e
 * dominio di firma `EYE-CAMPAIGNS-v1\n`, quindi i due tipi di file non sono intercambiabili. Anti-rollback proprio
 * (passare la versione installata del feed campagne, non quella delle regole).
 */
class CampaignFeedVerifier(pinnedKeys: Map<String, String> = PinnedKeys.production) {
    private val keys: Map<String, ByteArray> = pinnedKeys.mapValues { Base64.getDecoder().decode(it.value) }
        .also { m -> require(m.values.all { it.size == 32 }) { "chiave pubblica non valida" } }

    fun verify(bytes: ByteArray, installedVersion: Long): Result<CampaignFeed> = try {
        Result.success(doVerify(bytes, installedVersion))
    } catch (e: RulePackException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(RulePackException(RulePackException.Reason.MALFORMED, "envelope non valido"))
    }

    private fun doVerify(bytes: ByteArray, installedVersion: Long): CampaignFeed {
        val (env, plain) = SignedEnvelope.open(bytes, installedVersion, keys, FORMAT, DOMAIN, MAX_ENVELOPE, MAX_DECOMPRESSED)
        val feed = try {
            payloadJson.decodeFromString<CampaignFeed>(plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw RulePackException(RulePackException.Reason.INVALID_CAMPAIGNS, "JSON campagne non valido")
        }
        if (feed.version != env.version || feed.keyId != env.keyId) {
            throw RulePackException(RulePackException.Reason.PAYLOAD_MISMATCH, "version/keyId del payload diversi dall'envelope")
        }
        validate(feed)
        return feed
    }

    companion object {
        const val FORMAT = 2
        const val DOMAIN = "EYE-CAMPAIGNS-v1\n"
        const val MAX_ENVELOPE = 256 * 1024
        const val MAX_DECOMPRESSED = 1024 * 1024
        const val MAX_CAMPAIGNS = 100
        val CATEGORIES = setOf("smishing", "phishing", "whatsapp", "malware", "investimenti", "acquisti", "altro")
        val STATUSES = setOf("in_corso", "conclusa")

        /** Domini ufficiali ammessi (host uguale o sottodominio). Tutto `.gov.it` e' ammesso. */
        val OFFICIAL_DOMAINS = listOf(
            "gov.it", "poliziadistato.it", "commissariatodips.it", "inps.it", "inail.it", "aci.it",
            "carabinieri.it", "bancaditalia.it", "consob.it", "ivass.it", "agcom.it", "garanteprivacy.it",
        )

        private val payloadJson = Json { ignoreUnknownKeys = true } // payload gia' firmato; campi futuri tollerati
        private val ID = Regex("^[a-z0-9][a-z0-9-]{2,63}$")
        private val DATE = Regex("^20\\d\\d-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$")
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

        /** true se [url] e' https, senza credenziali/porte strane, verso un dominio ufficiale ammesso. */
        fun isOfficialHttpsUrl(url: String): Boolean = try {
            if (url.length > 300 || url.any { it.code < 0x21 || it.code > 0x7e }) {
                false
            } else {
                val u = URI(url)
                val host = u.host
                u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) && host != null &&
                    HOST.matches(host) && OFFICIAL_DOMAINS.any { host == it || host.endsWith(".$it") }
            }
        } catch (e: Exception) {
            false
        }

        /** true se entrambe le date sono ISO-8601 UTC valide e issuedAt < expiresAt. */
        internal fun validWindow(issuedAt: String?, expiresAt: String?): Boolean = try {
            Instant.parse(issuedAt!!) < Instant.parse(expiresAt!!)
        } catch (e: Exception) {
            false
        }

        fun validate(feed: CampaignFeed) {
            fun bad(m: String): Nothing = throw RulePackException(RulePackException.Reason.INVALID_CAMPAIGNS, m)
            if (feed.version <= 0) bad("version deve essere > 0")
        if (!validWindow(feed.issuedAt, feed.expiresAt)) bad("issuedAt/expiresAt non validi")
            if (feed.campaigns.size > MAX_CAMPAIGNS) bad("troppe campagne")
            if (feed.campaigns.map { it.id }.toSet().size != feed.campaigns.size) bad("id duplicati")
            for (c in feed.campaigns) {
                val w = c.id
                if (!ID.matches(c.id)) bad("id non valido")
                if (c.title.isBlank() || c.title.length > 120) bad("$w: titolo")
                if (c.category !in CATEGORIES) bad("$w: categoria")
                if (c.summary.isBlank() || c.summary.length > 500) bad("$w: descrizione")
                if (c.example.length > 500) bad("$w: esempio")
                if (c.signals.size !in 1..8 || c.signals.any { it.isBlank() || it.length > 250 }) bad("$w: segnali")
                if (c.actions.size !in 1..8 || c.actions.any { it.isBlank() || it.length > 250 }) bad("$w: azioni")
                if (!DATE.matches(c.firstSeen) || !DATE.matches(c.updatedAt) || c.updatedAt < c.firstSeen) bad("$w: date")
                if (c.status !in STATUSES) bad("$w: stato")
                if (c.source.name.isBlank() || c.source.name.length > 100) bad("$w: nome fonte")
                if (!isOfficialHttpsUrl(c.source.url)) bad("$w: URL fonte non ammesso")
            }
        }
    }
}

/** Riga dell'indice pubblico version.json. */
data class FeedEntry(val version: Long, val sha256: String, val url: String) {
    /** true se [bytes] ha lo sha256 dichiarato (integrita'/cache; l'autenticita' e' la firma). */
    fun matches(bytes: ByteArray): Boolean = FeedIndex.sha256Hex(bytes) == sha256
}

/** [blocklist] opzionale: un indice senza (o con voce malformata) non blocca regole e campagne. */
data class FeedIndex(val rules: FeedEntry, val campaigns: FeedEntry, val blocklist: FeedEntry? = null, val numbers: FeedEntry? = null, val model: FeedEntry? = null) {
    companion object {
        const val MAX_BYTES = 4 * 1024
        private val SHA = Regex("^[0-9a-f]{64}$")
        private val FILE = Regex("^[a-z0-9][a-z0-9._-]{0,63}\\.json$")

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Parser robusto di version.json (non firmato: e' solo un indice). Mai eccezioni: Result.failure. */
        fun parse(bytes: ByteArray): Result<FeedIndex> = try {
            require(bytes.size <= MAX_BYTES) { "version.json troppo grande" }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            Result.success(FeedIndex(entry(root, "rules"), entry(root, "campaigns"), runCatching { entry(root, "blocklist") }.getOrNull(), runCatching { entry(root, "numbers") }.getOrNull(), runCatching { entry(root, "model") }.getOrNull()))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("version.json non valido"))
        }

        private fun entry(root: JsonObject, name: String): FeedEntry {
            val o = root.getValue(name).jsonObject
            val vp = o.getValue("version").jsonPrimitive
            require(!vp.isString)
            val sha = o.getValue("sha256").jsonPrimitive.also { require(it.isString) }.content
            val url = o.getValue("url").jsonPrimitive.also { require(it.isString) }.content
            val v = vp.long
            require(v > 0 && SHA.matches(sha) && FILE.matches(url) && ".." !in url)
            return FeedEntry(v, sha, url)
        }
    }
}
