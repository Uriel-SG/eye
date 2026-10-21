package app.eye.android

import app.eye.android.core.Catalog
import app.eye.android.core.CheckOutcome
import app.eye.android.core.CheckUseCase
import app.eye.android.core.Redactor
import app.eye.android.data.HistoryDao
import app.eye.android.data.HistoryEntity
import app.eye.android.data.HistoryRepository
import app.eye.engine.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDao : HistoryDao {
    val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())
    private var next = 1L
    override fun observeAll(): Flow<List<HistoryEntity>> = rows
    override fun observeSince(threshold: Long): Flow<List<HistoryEntity>> =
        rows.map { l -> l.filter { it.saved || it.createdAt >= threshold } }
    override suspend fun insert(e: HistoryEntity): Long { val id = next++; rows.value += e.copy(id = id); return id }
    override suspend fun deleteOlderThan(threshold: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filter { it.saved || it.createdAt >= threshold }
        return before - rows.value.size
    }
    override suspend fun delete(id: Long) { rows.value = rows.value.filter { it.id != id } }
    override suspend fun setSaved(id: Long, saved: Boolean) { rows.value = rows.value.map { if (it.id == id) it.copy(saved = saved) else it } }
    override suspend fun setAiOpinion(id: Long, value: String) { rows.value = rows.value.map { if (it.id == id) it.copy(aiOpinion = value) else it } }
    override suspend fun setExceptionKind(id: Long, kind: Int) { rows.value = rows.value.map { if (it.id == id) it.copy(exceptionKind = kind) else it } }
    override suspend fun setNanoOpinion(id: Long, value: String) { rows.value = rows.value.map { if (it.id == id) it.copy(nanoOpinion = value) else it } }
    override suspend fun setDomainAge(id: Long, value: String) { rows.value = rows.value.map { if (it.id == id) it.copy(domainAge = value) else it } }
    private fun rank(l: String) = when (l) { "PROBABILE_TRUFFA" -> 0; "SOSPETTO" -> 1; else -> 2 }
    override suspend fun setLevel(id: Long, level: String) { rows.value = rows.value.map { if (it.id == id && rank(level) < rank(it.level)) it.copy(level = level) else it } }
    override suspend fun deleteUnsaved() { rows.value = rows.value.filter { it.saved } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

class CoreTests {
    private val day = 24L * 60 * 60 * 1000

    @Test fun purgeRemovesOnlyOlderThanSevenDays() = runBlocking {
        val dao = FakeDao()
        val now = 100 * day
        dao.insert(HistoryEntity(createdAt = now - 8 * day, level = "SOSPETTO", preview = "a", reasons = ""))
        dao.insert(HistoryEntity(createdAt = now - 6 * day, level = "SOSPETTO", preview = "b", reasons = ""))
        val removed = HistoryRepository(dao) { now }.purge()
        assertEquals(1, removed)
        assertEquals(listOf("b"), dao.rows.value.map { it.preview })
    }

    @Test fun deleteAllClears() = runBlocking {
        val dao = FakeDao()
        val repo = HistoryRepository(dao) { 5 * day }
        repo.save("SOSPETTO", "x", listOf("m"))
        repo.deleteAll()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun historyStoresOnlyRedactedPreview() = runBlocking {
        val dao = FakeDao()
        val uc = CheckUseCase(FakeAnalyzer(), HistoryRepository(dao), { true })
        val text = "Urgente! Accedi a https://poste-verifica.com/x e chiama +39 333 1234567 subito"
        assertTrue(uc.run(text, Source.PASTE) is CheckOutcome.Done)
        val saved = dao.rows.value.single().preview
        assertFalse(saved.contains("poste-verifica"))
        assertFalse(saved.contains("1234567"))
        assertTrue(saved.contains(Redactor.LINK))
    }

    @Test fun noHistoryWhenDisabled() = runBlocking {
        val dao = FakeDao()
        CheckUseCase(FakeAnalyzer(), HistoryRepository(dao), { false }).run("ciao https://x.com", Source.PASTE)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun rejectsTooLongAndEmpty() = runBlocking {
        val uc = CheckUseCase(FakeAnalyzer(), HistoryRepository(FakeDao()), { true })
        assertEquals(CheckOutcome.TooLong, uc.run("a".repeat(CheckUseCase.MAX_CHARS + 1), Source.SHARE))
        assertEquals(CheckOutcome.Empty, uc.run("   ", Source.SHARE))
    }

    @Test fun previewTruncates() {
        assertTrue(Redactor.preview("x".repeat(500)).length <= 120)
    }

    @Test fun catalogHasEnoughCards() {
        assertTrue(Catalog.cards.size >= 10)
        assertNotNull(Catalog.byId("romance-scam"))
        assertEquals(Catalog.cards.size, Catalog.cards.map { it.id }.toSet().size)
        assertTrue(Catalog.ongoing().isNotEmpty())
        assertTrue(Catalog.cards.all { c -> c.reportLinks.all { it.url.startsWith("https://") } })
    }
}
