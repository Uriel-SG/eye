package app.eye.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * Reputazione dei domini: «questo host e' nell'elenco pubblico dei siti di truffa/malware?». Interrogata SUL TELEFONO
 * (nessun dato dell'utente esce): vedi [HashBlocklist]. [registrable] e' l'eTLD+1 di [host]; chi implementa controlla
 * l'host e i suoi domini padre fino al registrabile (su hosting condiviso il registrabile e' gia' il tenant).
 */
fun interface DomainReputation {
    fun isKnownMalicious(host: String, registrable: String): Boolean

    companion object {
        val NONE = DomainReputation { _, _ -> false }
    }
}

/**
 * Elenco di host malevoli come array ordinato di hash a 48 bit (primi 6 byte di SHA-256 dell'host ASCII minuscolo):
 * nessun oggetto per voce, ricerca binaria. Un falso positivo ha probabilita' ~N/2^48 per interrogazione.
 */
class HashBlocklist internal constructor(
    private val hashes: LongArray,
    val version: Long,
    val issuedAt: String?,
    val expiresAt: String?,
) : DomainReputation {
    val size: Int get() = hashes.size

    fun contains(host: String): Boolean = hashes.isNotEmpty() && java.util.Arrays.binarySearch(hashes, hashOf(host)) >= 0

    override fun isKnownMalicious(host: String, registrable: String): Boolean {
        var h = host
        while (true) {
            if (contains(h)) return true
            if (h == registrable) return false
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (h != registrable && !h.endsWith(".$registrable")) return false // mai oltre il registrabile
        }
    }

    /** Oltre la scadenza si continua a usare l'elenco (vecchio ma utile); l'app lo segnala in Impostazioni. */
    fun isStale(now: Instant): Boolean = try { Instant.parse(expiresAt!!) < now } catch (e: Exception) { false }

    companion object {
        const val HASH_BYTES = 6

        /** Primi 6 byte di SHA-256(host) come intero a 48 bit. */
        fun hashOf(host: String): Long {
            val d = MessageDigest.getInstance("SHA-256").digest(host.toByteArray(Charsets.US_ASCII))
            var v = 0L
            for (i in 0 until HASH_BYTES) v = (v shl 8) or (d[i].toLong() and 0xFF)
            return v
        }

        /** Elenco per i test: hash degli [hosts] gia' normalizzati. */
        fun of(hosts: Collection<String>, version: Long = 1, issuedAt: String? = null, expiresAt: String? = null) =
            HashBlocklist(hosts.map { hashOf(it) }.distinct().sorted().toLongArray(), version, issuedAt, expiresAt)
    }
}

/** Intestazione firmata dentro il payload dell'envelope (formato `EBL1`, vedi tools/blocklist/README.md). */
@Serializable
internal data class BlocklistHeader(
    val version: Long,
    val keyId: String,
    val issuedAt: String,
    val expiresAt: String,
    val count: Int,
    val hashBytes: Int,
)

/**
 * Verifica l'envelope del feed «siti malevoli» (format 3, dominio di firma proprio): stessi passi 1-5 di regole e
 * campagne ([SignedEnvelope]), poi parse del contenitore binario e coerenza con l'envelope. Anti-rollback indipendente.
 *
 * Payload (dopo gunzip): `"EBL1"` | u32 BE lunghezza intestazione | intestazione JSON | corpo. Corpo: primo hash a 6 byte,
 * poi per ogni hash successivo (strettamente crescente) la differenza u32 BE dal precedente; se non entra in
 * 32 bit (0xFFFFFFFF) segue l'hash assoluto a 6 byte.
 */
class BlocklistVerifier(
    pinnedKeys: Map<String, String> = PinnedKeys.production,
    /** Parametri del contenitore: di default l'elenco siti; l'elenco numeri usa format, dominio e magic propri (vedi [numberlistVerifier]). */
    private val format: Int = FORMAT,
    private val domain: String = DOMAIN,
    private val magic: ByteArray = MAGIC,
) {
    private val keys: Map<String, ByteArray> = pinnedKeys.mapValues { Base64.getDecoder().decode(it.value) }
        .also { m -> require(m.values.all { it.size == 32 }) { "chiave pubblica non valida" } }

    fun verify(bytes: ByteArray, installedVersion: Long): Result<HashBlocklist> = try {
        Result.success(doVerify(bytes, installedVersion))
    } catch (e: RulePackException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(RulePackException(RulePackException.Reason.MALFORMED, "envelope non valido"))
    }

    private fun bad(m: String): Nothing = throw RulePackException(RulePackException.Reason.INVALID_BLOCKLIST, m)

    private fun doVerify(bytes: ByteArray, installedVersion: Long): HashBlocklist {
        val (env, plain) = SignedEnvelope.open(
            bytes, installedVersion, keys, format = format, domain = domain, maxEnvelope = MAX_ENVELOPE, maxDecompressed = MAX_DECOMPRESSED,
        )
        if (plain.size < 8 || !(0..3).all { plain[it] == magic[it] }) bad("contenitore non riconosciuto")
        val buf = ByteBuffer.wrap(plain)
        val hl = buf.getInt(4)
        if (hl !in 2..MAX_HEADER || 8 + hl > plain.size) bad("intestazione non valida")
        val header = try {
            Json { ignoreUnknownKeys = false }.decodeFromString<BlocklistHeader>(String(plain, 8, hl, Charsets.UTF_8))
        } catch (e: Exception) {
            bad("intestazione malformata")
        }
        if (header.version != env.version || header.keyId != env.keyId) bad("version/keyId diversi dall'envelope")
        if (!CampaignFeedVerifier.validWindow(header.issuedAt, header.expiresAt)) bad("issuedAt/expiresAt non validi")
        if (header.hashBytes != HashBlocklist.HASH_BYTES || header.count !in 0..MAX_ENTRIES) bad("parametri fuori range")

        val out = LongArray(header.count)
        var pos = 8 + hl
        fun u48(): Long {
            if (pos + 6 > plain.size) bad("corpo troncato")
            var v = 0L
            repeat(6) { v = (v shl 8) or (plain[pos++].toLong() and 0xFF) }
            return v
        }
        if (header.count > 0) out[0] = u48()
        for (i in 1 until header.count) {
            if (pos + 4 > plain.size) bad("corpo troncato")
            val gap = buf.getInt(pos).toLong() and 0xFFFFFFFFL
            pos += 4
            val v = if (gap == ESCAPE) u48() else out[i - 1] + gap
            if (v <= out[i - 1] || v >= (1L shl 48)) bad("hash non ordinati")
            out[i] = v
        }
        if (pos != plain.size) bad("dati in eccesso")
        return HashBlocklist(out, env.version, header.issuedAt, header.expiresAt)
    }

    companion object {
        const val FORMAT = 3
        const val DOMAIN = "EYE-BLOCKLIST-v1\n"
        /** Envelope (base64 + JSON) e payload decompresso: l'elenco attuale pesa ~3,6 MB / ~2,7 MB. */
        const val MAX_ENVELOPE = 8 * 1024 * 1024
        const val MAX_DECOMPRESSED = 8 * 1024 * 1024
        const val MAX_ENTRIES = 1_500_000
        private const val MAX_HEADER = 1024
        private const val ESCAPE = 0xFFFFFFFFL
        private val MAGIC = "EBL1".toByteArray(Charsets.US_ASCII)
    }
}

/** Store del feed siti malevoli: stessa implementazione, file separati (anti-rollback indipendente). */
fun blocklistStore(dir: File): RulePackStore = FileRulePackStore(dir, "blocklist.eye.json", "blocklist.version")

/** Verifica [envelope] e, se valido, lo salva con swap atomico. */
fun RulePackStore.installBlocklist(verifier: BlocklistVerifier, envelope: ByteArray): Result<HashBlocklist> =
    verifier.verify(envelope, installedVersion()).onSuccess { save(envelope, it.version) }

/** Ricarica l'elenco salvato, riverificandone la firma. */
fun RulePackStore.loadBlocklist(verifier: BlocklistVerifier): HashBlocklist? {
    val bytes = readPack() ?: return null
    return verifier.verify(bytes, installedVersion() - 1).getOrNull()
}

/**
 * Reputazione dal feed installato, caricata alla prima interrogazione (in memoria resta solo l'array di hash).
 * [reload] dopo un aggiornamento. Senza elenco valido risponde sempre «non noto».
 */
class BlocklistHolder(
    private val store: RulePackStore,
    private val verifier: BlocklistVerifier = BlocklistVerifier(),
) : DomainReputation {
    @Volatile private var loaded = false
    @Volatile private var list: HashBlocklist? = null

    /** Elenco corrente (lo carica se serve), o null. */
    fun current(): HashBlocklist? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                list = runCatching { store.loadBlocklist(verifier) }.getOrNull()
                loaded = true
            }
        }
        return list
    }

    fun reload() = synchronized(this) { loaded = false; list = null }

    override fun isKnownMalicious(host: String, registrable: String): Boolean =
        current()?.isKnownMalicious(host, registrable) ?: false
}
