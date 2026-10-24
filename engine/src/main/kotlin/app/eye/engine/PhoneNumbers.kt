package app.eye.engine

import java.io.File
import java.security.MessageDigest

/** Tipo di numero (piano di numerazione italiano + internazionale). Solo [MOBILE], [LANDLINE] e [INTL] possono stare nell'elenco. */
enum class NumberKind {
    MOBILE, LANDLINE,
    /** 800, 803: gratuiti per chi chiama, mai segnalati di per se'. */
    TOLL_FREE,
    /** 89x, 144, 166, +979, +88x: sovrapprezzo / non tracciabili. */
    PREMIUM,
    /** Meno di 6 cifre (112, 116xxx, 1xx servizi): mai segnalati. */
    SHORT,
    /** Altre numerazioni italiane (840/848/199 a costo condiviso, 178 personali...): mai segnalate di per se'. */
    OTHER_IT,
    /** Numero estero (+cc). */
    INTL,
}

/** Numero normalizzato E.164 (`+39333...`; i fissi italiani tengono lo 0). */
data class PhoneNumber(val e164: String, val kind: NumberKind) {
    /** Puo' comparire nell'elenco e nel confronto: mai numeri verdi, brevi, a costo condiviso o a sovrapprezzo. */
    val isListable: Boolean get() = kind == NumberKind.MOBILE || kind == NumberKind.LANDLINE || kind == NumberKind.INTL
}

object PhoneNumbers {
    /** Normalizza [raw] (anche `0039 333-123.4567`, `+39 (0)2 1234567`, `333 1234567`). null se non e' un numero plausibile. */
    fun parse(raw: String): PhoneNumber? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 40) return null
        val hasPlus = s.startsWith("+")
        if (s.any { !(it.isDigit() || it in " .-()/+") } || s.count { it == '+' } > (if (hasPlus) 1 else 0) || (hasPlus && s.indexOf('+') != 0)) return null
        var d = s.filter { it.isDigit() }
        var intl = hasPlus
        if (!intl && d.startsWith("00")) { d = d.substring(2); intl = true }
        if (!intl) return italian(d)
        if (d.startsWith("39")) {
            return italian(d.substring(2)) // lo 0 dei fissi resta: +39 02 1234567
        }
        if (d.length !in 8..15 || d.startsWith("0")) return null
        val premium = PREMIUM_INTL.any { d.startsWith(it) }
        return PhoneNumber("+$d", if (premium) NumberKind.PREMIUM else NumberKind.INTL)
    }

    private val PREMIUM_INTL = listOf("979", "881", "882", "883", "870", "871", "872", "873", "874", "875", "876", "877", "878")

    private fun italian(nat: String): PhoneNumber? {
        if (nat.isEmpty() || nat.length > 12) return null
        val e = "+39$nat"
        return when {
            nat.length < 6 -> PhoneNumber(e, NumberKind.SHORT)
            nat.startsWith("800") || nat.startsWith("803") -> PhoneNumber(e, NumberKind.TOLL_FREE)
            nat.startsWith("89") && nat.length in 6..10 || nat.startsWith("144") || nat.startsWith("166") -> PhoneNumber(e, NumberKind.PREMIUM)
            nat[0] == '3' -> if (nat.length in 9..10) PhoneNumber(e, NumberKind.MOBILE) else null
            nat[0] == '0' -> if (nat.length in 6..11) PhoneNumber(e, NumberKind.LANDLINE) else null
            nat[0] == '1' || nat[0] == '8' -> PhoneNumber(e, NumberKind.OTHER_IT)
            else -> null
        }
    }
}

/**
 * Reputazione dei numeri: «questo numero e' nell'elenco pubblico dei numeri usati per truffe?». Interrogata SUL TELEFONO
 * (nessun numero dell'utente esce). [e164] e' sempre gia' normalizzato e listabile.
 */
fun interface NumberReputation {
    fun isKnownSpam(e164: String): Boolean

    companion object {
        val NONE = NumberReputation { false }
    }
}

/** Stesso contenitore dell'elenco siti (hash a 48 bit, ricerca binaria) con magic `ENL1`, format 4 e dominio `EYE-NUMBERS-v1`. */
class HashNumberlist internal constructor(private val list: HashBlocklist) : NumberReputation {
    val size: Int get() = list.size
    val version: Long get() = list.version
    val issuedAt: String? get() = list.issuedAt
    fun isStale(now: java.time.Instant) = list.isStale(now)

    override fun isKnownSpam(e164: String): Boolean = list.contains(KEY_PREFIX + e164)

    companion object {
        /** Separa lo spazio degli hash da quello degli host. Deve coincidere con tools/numbers/build_numbers.py. */
        const val KEY_PREFIX = "tel:"

        fun of(e164s: Collection<String>, version: Long = 1) = HashNumberlist(HashBlocklist.of(e164s.map { KEY_PREFIX + it }, version))
    }
}

const val NUMBERS_FORMAT = 4
const val NUMBERS_DOMAIN = "EYE-NUMBERS-v1\n"

/** Verificatore del feed «numeri»: anti-rollback e firma indipendenti da quelli dei siti (format e dominio diversi). */
fun numberlistVerifier(pinnedKeys: Map<String, String> = PinnedKeys.production) =
    BlocklistVerifier(pinnedKeys, NUMBERS_FORMAT, NUMBERS_DOMAIN, "ENL1".toByteArray(Charsets.US_ASCII))

fun numberlistStore(dir: File): RulePackStore = FileRulePackStore(dir, "numbers.eye.json", "numbers.version")

fun RulePackStore.installNumberlist(verifier: BlocklistVerifier, envelope: ByteArray): Result<HashNumberlist> =
    installBlocklist(verifier, envelope).map { HashNumberlist(it) }

/** Elenco numeri salvato, caricato alla prima interrogazione. Senza elenco valido risponde sempre «non noto». */
class NumberlistHolder(
    private val store: RulePackStore,
    private val verifier: BlocklistVerifier = numberlistVerifier(),
) : NumberReputation {
    @Volatile private var loaded = false
    @Volatile private var list: HashNumberlist? = null

    fun current(): HashNumberlist? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                list = runCatching { store.loadBlocklist(verifier)?.let { HashNumberlist(it) } }.getOrNull()
                loaded = true
            }
        }
        return list
    }

    fun reload() = synchronized(this) { loaded = false; list = null }

    override fun isKnownSpam(e164: String): Boolean = current()?.isKnownSpam(e164) ?: false
}
