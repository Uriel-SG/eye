package app.eye.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Cronologia locale (7 giorni di default, a scelta 30 o sempre, solo su questo dispositivo, esclusa dai backup): esito, data, motivi, anteprima oscurata,
 * testo completo analizzato e Verdict serializzato. Per le immagini si salva solo il testo estratto, mai l'immagine.
 * Il testo completo si mostra oscurato (Redactor) salvo scelta esplicita dell'utente.
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** Nome di [app.eye.engine.Level]. */
    val level: String,
    val preview: String,
    /** Motivi separati da '\n'. */
    val reasons: String,
    /** Testo completo analizzato (per le immagini: solo il testo estratto). */
    val fullText: String = "",
    /** [VerdictJson] del verdetto delle regole; vuoto = non disponibile. */
    val verdictJson: String = "",
    /** Segnalibro «Salva»: le voci salvate non scadono mai (decide l utente). */
    @ColumnInfo(defaultValue = "0") val saved: Boolean = false,
    /** [AiOpinionJson] del secondo parere AI (già oscurato), cifrato su disco; vuoto = nessun parere salvato. */
    @ColumnInfo(defaultValue = "''") val aiOpinion: String = "",
    /** Eccezione dell'utente collegata a questa analisi: 0 = nessuna, 1 = affidabile (USR-01), 2 = sospetta (USR-02). */
    @ColumnInfo(defaultValue = "0") val exceptionKind: Int = 0,
    /** [NanoOpinionJson] del parere di Gemini Nano, cifrato su disco; vuoto = nessun parere salvato. */
    @ColumnInfo(defaultValue = "''") val nanoOpinion: String = "",
    /** [DomainAgeJson] dell'eta' dei domini (controllo RDAP), cifrato su disco; vuoto = non controllata. */
    @ColumnInfo(defaultValue = "''") val domainAge: String = "",
)

/** Riga dell'elenco: solo campi in chiaro, nessuna decifratura. */
data class HistoryListItem(
    val id: Long,
    val createdAt: Long,
    val level: String,
    val preview: String,
    val reasons: String,
    val saved: Boolean,
    val exceptionKind: Int,
)

/** Riga per la ricerca: [fullText] e' ancora cifrato. */
data class HistorySearchRow(
    val id: Long,
    val createdAt: Long,
    val level: String,
    val preview: String,
    val reasons: String,
    val saved: Boolean,
    val exceptionKind: Int,
    val fullText: String,
) {
    fun toItem() = HistoryListItem(id, createdAt, level, preview, reasons, saved, exceptionKind)
}

/** Filtro dell'elenco cronologia. */
enum class HistoryFilter(val mode: Int) { ALL(0), SAVED(1), EXCEPTIONS(2) }

/** Una pagina dell'elenco: [hasMore] = ci sono altre voci oltre il limite richiesto. */
data class HistoryListPage(val items: List<HistoryListItem>, val hasMore: Boolean) {
    companion object { val EMPTY = HistoryListPage(emptyList(), false) }
}

data class HistoryCounts(val total: Int, val saved: Int)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(e: HistoryEntity): Long

    /** Una sola voce per id (verdetto e schede): niente scansione della cronologia. */
    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<HistoryEntity?>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntity?

    /** Elenco paginato: solo colonne in chiaro (nessun dato cifrato letto). [mode]: 0 tutte, 1 salvate, 2 eccezioni. */
    @Query(
        "SELECT id, createdAt, level, preview, reasons, saved, exceptionKind FROM history " +
            "WHERE (saved = 1 OR createdAt >= :threshold) AND (:mode = 0 OR (:mode = 1 AND saved = 1) OR (:mode = 2 AND exceptionKind > 0)) " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    fun observeList(threshold: Long, mode: Int, limit: Int): Flow<List<HistoryListItem>>

    /** Come [observeList] ma con il testo cifrato, per la ricerca (decifrato in background e in cache). */
    @Query(
        "SELECT id, createdAt, level, preview, reasons, saved, exceptionKind, fullText FROM history " +
            "WHERE (saved = 1 OR createdAt >= :threshold) AND (:mode = 0 OR (:mode = 1 AND saved = 1) OR (:mode = 2 AND exceptionKind > 0)) " +
            "ORDER BY createdAt DESC",
    )
    fun observeSearchRows(threshold: Long, mode: Int): Flow<List<HistorySearchRow>>

    @Query("SELECT COUNT(*) FROM history WHERE saved = 1 OR createdAt >= :threshold")
    fun observeCount(threshold: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM history WHERE saved = 1")
    fun observeSavedCount(): Flow<Int>

    /** Scansione a blocchi per id (verifica delle voci illeggibili). */
    @Query("SELECT * FROM history WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun chunkAfter(afterId: Long, limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE saved = 1 OR createdAt >= :threshold ORDER BY createdAt DESC")
    fun observeSince(threshold: Long): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE createdAt < :threshold AND saved = 0")
    suspend fun deleteOlderThan(threshold: Long): Int

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE history SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    @Query("UPDATE history SET aiOpinion = :value WHERE id = :id")
    suspend fun setAiOpinion(id: Long, value: String)

    @Query("UPDATE history SET nanoOpinion = :value WHERE id = :id")
    suspend fun setNanoOpinion(id: Long, value: String)

    @Query("UPDATE history SET domainAge = :value WHERE id = :id")
    suspend fun setDomainAge(id: Long, value: String)

    /** Solo per ALZARE il livello (PROBABILE_TRUFFA > SOSPETTO > NESSUN_SEGNALE): mai abbassa. */
    @Query(
        "UPDATE history SET level = :level WHERE id = :id AND " +
            "(CASE :level WHEN 'PROBABILE_TRUFFA' THEN 0 WHEN 'SOSPETTO' THEN 1 ELSE 2 END) < " +
            "(CASE level WHEN 'PROBABILE_TRUFFA' THEN 0 WHEN 'SOSPETTO' THEN 1 ELSE 2 END)",
    )
    suspend fun setLevel(id: Long, level: String)

    /** Sovrascrive il livello (verdetto finale dell'AI, che puo' anche abbassarlo). */
    @Query("UPDATE history SET level = :level WHERE id = :id")
    suspend fun forceLevel(id: Long, level: String)

    @Query("UPDATE history SET exceptionKind = :kind WHERE id = :id")
    suspend fun setExceptionKind(id: Long, kind: Int)

    @Query("DELETE FROM history WHERE saved = 0")
    suspend fun deleteUnsaved()

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}

/** v2 -> v3: aggiunge il segnalibro. Non distruttiva: tutte le righe esistenti restano, non salvate. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history ADD COLUMN saved INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3 -> v4: aggiunge il secondo parere AI salvato (cifrato). Non distruttiva: le righe esistenti restano, senza parere. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history ADD COLUMN aiOpinion TEXT NOT NULL DEFAULT ''")
    }
}

/** v4 -> v5: marca le analisi diventate eccezioni. Non distruttiva: le righe esistenti restano, non marcate. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history ADD COLUMN exceptionKind INTEGER NOT NULL DEFAULT 0")
    }
}

/** v5 -> v6: aggiunge il parere di Gemini Nano salvato (cifrato). Non distruttiva: le righe esistenti restano, senza parere. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history ADD COLUMN nanoOpinion TEXT NOT NULL DEFAULT ''")
    }
}

/** v6 -> v7: aggiunge l'eta' dei domini salvata (cifrata). Non distruttiva: le righe esistenti restano, senza eta'. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history ADD COLUMN domainAge TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [HistoryEntity::class], version = 7, exportSchema = false)
abstract class EyeDb : RoomDatabase() {
    abstract fun history(): HistoryDao
}

/** Nessuna cifratura (solo test o dati già non sensibili). */
object NoCrypto : TextCrypto {
    override fun encrypt(plain: String) = plain
    override fun decrypt(stored: String): String? = stored
}

/**
 * [fullText] e [verdictJson] stanno su disco CIFRATI (AES-GCM, chiave Keystore dedicata); chi legge dal repository
 * riceve il testo in chiaro. Le voci non più leggibili (chiave invalidata) sono nascoste ed eliminate alla purge.
 */
class HistoryRepository(
    private val dao: HistoryDao,
    private val crypto: TextCrypto = NoCrypto,
    /** Giorni di conservazione (0 = sempre); può cambiare mentre l'app è aperta. */
    private val retentionDays: Flow<Int> = flowOf(RETENTION_DAYS),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun threshold(days: Int): Long = if (days <= 0) 0L else clock() - days * DAY_MS

    private val cache = DecryptCache(MAX_CACHE_ENTRIES, MAX_CACHE_CHARS)
    @Volatile private var scanned = false

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<HistoryEntity>> =
        retentionDays.flatMapLatest { d -> dao.observeSince(threshold(d)) }.map { rows -> rows.mapNotNull(::open) }.flowOn(Dispatchers.Default)

    /** Una sola voce, decifrata fuori dal thread principale; null se assente o illeggibile. */
    fun observeEntry(id: Long): Flow<HistoryEntity?> = dao.observeById(id).map { it?.let(::open) }.flowOn(Dispatchers.Default)

    suspend fun get(id: Long): HistoryEntity? = withContext(Dispatchers.Default) { dao.getById(id)?.let(::open) }

    /**
     * Elenco paginato (al piu' [limit] voci). Senza ricerca non si decifra nulla; con [query] si decifra, in background e con cache,
     * solo il testo delle voci candidate. Annullabile: un nuovo testo di ricerca interrompe la scansione precedente.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePage(filter: HistoryFilter, query: String, limit: Int): Flow<HistoryListPage> {
        val q = query.trim()
        return retentionDays.flatMapLatest { d ->
            val th = threshold(d)
            if (q.isEmpty()) dao.observeList(th, filter.mode, limit + 1).map { HistoryListPage(it.take(limit), it.size > limit) }
            else dao.observeSearchRows(th, filter.mode).map { rows ->
                val hits = ArrayList<HistoryListItem>()
                var more = false
                for (r in rows) {
                    currentCoroutineContext().ensureActive()
                    if (!r.preview.contains(q, true) && !r.reasons.contains(q, true) && !(plain(r.id, F_TEXT, r.fullText)?.contains(q, true) ?: false)) continue
                    if (hits.size == limit) { more = true; break }
                    hits.add(r.toItem())
                }
                HistoryListPage(hits, more)
            }
        }.flowOn(Dispatchers.Default)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeCounts(): Flow<HistoryCounts> =
        retentionDays.flatMapLatest { d -> combine(dao.observeCount(threshold(d)), dao.observeSavedCount(), ::HistoryCounts) }

    private fun plain(id: Long, field: Int, enc: String): String? {
        if (enc.isEmpty()) return ""
        cache.get(id, field, enc)?.let { return it }
        return crypto.decrypt(enc)?.also { cache.put(id, field, enc, it) }
    }

    private fun open(e: HistoryEntity): HistoryEntity? {
        val text = plain(e.id, F_TEXT, e.fullText) ?: return null
        val json = plain(e.id, F_VERDICT, e.verdictJson) ?: return null
        // un parere illeggibile non nasconde l'analisi: si perde solo il parere
        val ai = plain(e.id, F_AI, e.aiOpinion).orEmpty()
        val nano = plain(e.id, F_NANO, e.nanoOpinion).orEmpty()
        val age = plain(e.id, F_AGE, e.domainAge).orEmpty()
        return e.copy(fullText = text, verdictJson = json, aiOpinion = ai, nanoOpinion = nano, domainAge = age)
    }

    /** Solo la scadenza per data: economica, si puo' chiamare spesso. */
    suspend fun purgeExpired(): Int {
        val t = threshold(retentionDays.first())
        return if (t > 0) dao.deleteOlderThan(t) else 0
    }

    /**
     * Cancellazione fisica dei record più vecchi oltre la conservazione scelta (GDPR-07) e di quelli illeggibili.
     * La verifica delle voci illeggibili (decifra tutto) si fa una sola volta per processo, a blocchi.
     */
    suspend fun purge(): Int = withContext(Dispatchers.Default) {
        var n = purgeExpired()
        if (!scanned) {
            scanned = true
            var after = 0L
            while (true) {
                val chunk = dao.chunkAfter(after, SCAN_CHUNK)
                if (chunk.isEmpty()) break
                chunk.filter { open(it) == null }.forEach { dao.delete(it.id); n++ }
                after = chunk.last().id
            }
        }
        n
    }

    suspend fun save(
        level: String, preview: String, reasons: List<String>, fullText: String = "", verdictJson: String = "", saved: Boolean = false,
        exceptionKind: Int = 0,
    ): Long {
        purgeExpired()
        return dao.insert(
            HistoryEntity(
                createdAt = clock(), level = level, preview = preview, reasons = reasons.joinToString("\n"),
                fullText = if (fullText.isEmpty()) "" else crypto.encrypt(fullText),
                verdictJson = if (verdictJson.isEmpty()) "" else crypto.encrypt(verdictJson),
                saved = saved, exceptionKind = exceptionKind,
            ),
        )
    }

    /** Salva (cifrato) il parere AI nella voce [id]; [json] vuoto lo rimuove. */
    suspend fun setAiOpinion(id: Long, json: String) = dao.setAiOpinion(id, if (json.isEmpty()) "" else crypto.encrypt(json))

    /** Salva (cifrato) il parere di Gemini Nano nella voce [id]; [json] vuoto lo rimuove. */
    suspend fun setNanoOpinion(id: Long, json: String) = dao.setNanoOpinion(id, if (json.isEmpty()) "" else crypto.encrypt(json))

    /** Salva (cifrata) l'eta' dei domini nella voce [id]; [json] vuoto la rimuove. */
    suspend fun setDomainAge(id: Long, json: String) = dao.setDomainAge(id, if (json.isEmpty()) "" else crypto.encrypt(json))

    /** Livello mostrato in elenco: solo rialzi (parere di Gemini Nano, eta' del dominio), mai un abbassamento. */
    suspend fun setLevel(id: Long, level: String) = dao.setLevel(id, level)

    /** Verdetto finale dell'AI (chiave o Nano): il livello in elenco segue la scheda, anche se scende. */
    suspend fun setFinalLevel(id: Long, level: String) = dao.forceLevel(id, level)

    suspend fun setExceptionKind(id: Long, kind: Int) = dao.setExceptionKind(id, kind)

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun setSaved(id: Long, saved: Boolean) = dao.setSaved(id, saved)

    /** [includeSaved] false: le analisi salvate restano. */
    suspend fun deleteAll(includeSaved: Boolean = true) = if (includeSaved) dao.deleteAll() else dao.deleteUnsaved()

    companion object {
        const val RETENTION_DAYS = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_CACHE_ENTRIES = 2_500
        private const val MAX_CACHE_CHARS = 4_000_000
        private const val SCAN_CHUNK = 100
        private const val F_TEXT = 0
        private const val F_VERDICT = 1
        private const val F_AI = 2
        private const val F_NANO = 3
        private const val F_AGE = 4
    }
}

/**
 * Cache LRU dei campi decifrati, chiave (id, campo) e "timbro" economico del testo cifrato (lunghezza + hashCode):
 * una riga invariata non si decifra di nuovo. Limitata per numero di voci e per caratteri totali.
 */
class DecryptCache(private val maxEntries: Int, private val maxChars: Int) {
    private class Slot(val stamp: Long, val plain: String)
    private val map = LinkedHashMap<Long, Slot>(64, 0.75f, true)
    private var chars = 0

    private fun key(id: Long, field: Int) = id * 8 + field
    private fun stamp(enc: String) = (enc.length.toLong() shl 32) xor (enc.hashCode().toLong() and 0xffffffffL)

    @Synchronized fun get(id: Long, field: Int, enc: String): String? =
        map[key(id, field)]?.takeIf { it.stamp == stamp(enc) }?.plain

    @Synchronized fun put(id: Long, field: Int, enc: String, plain: String) {
        map.put(key(id, field), Slot(stamp(enc), plain))?.let { chars -= it.plain.length }
        chars += plain.length
        val it = map.entries.iterator()
        while ((map.size > maxEntries || chars > maxChars) && map.size > 1 && it.hasNext()) {
            val e = it.next(); chars -= e.value.plain.length; it.remove()
        }
    }

    @get:Synchronized val size: Int get() = map.size
}
