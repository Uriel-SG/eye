package app.eye.android

import app.eye.android.data.HistoryRepository
import app.eye.android.data.MIGRATION_5_6
import app.eye.android.data.NanoOpinionJson
import app.eye.android.data.TextCrypto
import app.eye.engine.llm.LlmOpinion
import app.eye.engine.llm.LlmVerdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Parere di Gemini Nano salvato in cronologia (cifrato) e migrazione Room 5 -> 6. */
class NanoStoreTest {
    private object Rev : TextCrypto {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(stored: String): String? = stored.removePrefix("enc:").reversed()
    }

    private val op = LlmOpinion(LlmVerdict.TRUFFA, 0.9, "Chiede un pagamento", listOf("Non pagare", "Blocca il numero"))

    @Test fun jsonRoundTripAndRejectsGarbage() {
        assertEquals(op, NanoOpinionJson.decode(NanoOpinionJson.encode(op, 1L)))
        assertNull(NanoOpinionJson.decode(""))
        assertNull(NanoOpinionJson.decode("{"))
        assertNull(NanoOpinionJson.decode("""{"v":"boh","e":"x"}"""))
        assertNull(NanoOpinionJson.decode("""{"v":"TRUFFA"}"""))
    }

    @Test fun storedEncryptedAndSurvivesReopen() = runBlocking {
        val dao = FakeDao()
        val repo = HistoryRepository(dao, crypto = Rev)
        val id = repo.save("NESSUN_SEGNALE", "p", listOf("m"), "testo", "{}")
        assertEquals("", repo.observe().first().single().nanoOpinion)
        val json = NanoOpinionJson.encode(op, 5L)
        repo.setNanoOpinion(id, json)
        repo.setLevel(id, "SOSPETTO")
        assertFalse("parere in chiaro su disco", dao.rows.value.single().nanoOpinion.contains("pagamento"))
        val row = repo.observe().first().single()
        assertEquals(json, row.nanoOpinion)
        assertEquals("SOSPETTO", row.level)
        assertEquals(op, NanoOpinionJson.decode(row.nanoOpinion))
        repo.setNanoOpinion(id, "")
        assertEquals("", repo.observe().first().single().nanoOpinion)
    }

    @Test fun unreadableNanoOpinionDoesNotHideTheEntry() = runBlocking {
        val dao = FakeDao()
        val bad = object : TextCrypto {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = if (stored == "nano") null else stored
        }
        val repo = HistoryRepository(dao, crypto = bad)
        val id = repo.save("SOSPETTO", "p", listOf("m"), "testo", "{}")
        dao.setNanoOpinion(id, "nano")
        val row = repo.observe().first().single()
        assertEquals("", row.nanoOpinion)
        assertEquals("testo", row.fullText)
    }

    @Test fun migration5to6AddsColumnWithoutDroppingData() {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, m, a ->
            if (m.name == "execSQL") sql += a!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_5_6.migrate(db)
        assertEquals(5, MIGRATION_5_6.startVersion); assertEquals(6, MIGRATION_5_6.endVersion)
        assertEquals(listOf("ALTER TABLE history ADD COLUMN nanoOpinion TEXT NOT NULL DEFAULT ''"), sql)
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) })
    }
}
