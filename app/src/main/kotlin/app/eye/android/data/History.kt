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
import kotlinx.coroutines.flow.Flow
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
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(e: HistoryEntity): Long

    @Query("SELECT * FROM history WHERE saved = 1 OR createdAt >= :threshold ORDER BY createdAt DESC")
    fun observeSince(threshold: Long): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE createdAt < :threshold AND saved = 0")
    suspend fun deleteOlderThan(threshold: Long): Int

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE history SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

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

@Database(entities = [HistoryEntity::class], version = 3, exportSchema = false)
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<HistoryEntity>> =
        retentionDays.flatMapLatest { d -> dao.observeSince(threshold(d)) }.map { rows -> rows.mapNotNull(::open) }

    private fun open(e: HistoryEntity): HistoryEntity? {
        val text = if (e.fullText.isEmpty()) "" else crypto.decrypt(e.fullText) ?: return null
        val json = if (e.verdictJson.isEmpty()) "" else crypto.decrypt(e.verdictJson) ?: return null
        return e.copy(fullText = text, verdictJson = json)
    }

    /** Cancellazione fisica dei record più vecchi oltre la conservazione scelta (GDPR-07) e di quelli illeggibili. */
    suspend fun purge(): Int {
        val t = threshold(retentionDays.first())
        var n = if (t > 0) dao.deleteOlderThan(t) else 0
        dao.observeSince(t).first().filter { open(it) == null }.forEach { dao.delete(it.id); n++ }
        return n
    }

    suspend fun save(
        level: String, preview: String, reasons: List<String>, fullText: String = "", verdictJson: String = "", saved: Boolean = false,
    ): Long {
        purge()
        return dao.insert(
            HistoryEntity(
                createdAt = clock(), level = level, preview = preview, reasons = reasons.joinToString("\n"),
                fullText = if (fullText.isEmpty()) "" else crypto.encrypt(fullText),
                verdictJson = if (verdictJson.isEmpty()) "" else crypto.encrypt(verdictJson),
                saved = saved,
            ),
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun setSaved(id: Long, saved: Boolean) = dao.setSaved(id, saved)

    /** [includeSaved] false: le analisi salvate restano. */
    suspend fun deleteAll(includeSaved: Boolean = true) = if (includeSaved) dao.deleteAll() else dao.deleteUnsaved()

    companion object {
        const val RETENTION_DAYS = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
