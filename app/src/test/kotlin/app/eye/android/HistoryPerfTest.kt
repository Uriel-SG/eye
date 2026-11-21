package app.eye.android

import app.eye.android.core.feed.NumbersStatus
import app.eye.android.core.feed.PurgeThrottle
import app.eye.android.data.DecryptCache
import app.eye.android.data.HistoryEntity
import app.eye.android.data.HistoryFilter
import app.eye.android.data.HistoryRepository
import app.eye.android.data.TextCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Prestazioni della cronologia lunga: quante decifrature servono davvero (dati di esempio, tutti fittizi). */
class HistoryPerfTest {
    private class CountingCrypto : TextCrypto {
        val decrypts = AtomicInteger()
        override fun encrypt(plain: String) = "v1:" + plain.reversed()
        override fun decrypt(stored: String): String? { decrypts.incrementAndGet(); return stored.removePrefix("v1:").reversed() }
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun filled(n: Int, crypto: CountingCrypto, retention: Int = 0): Pair<FakeDao, HistoryRepository> {
        val dao = FakeDao()
        val repo = HistoryRepository(dao, crypto, flowOf(retention)) { now }
        runBlocking {
            for (i in 1..n) {
                val id = dao.insert(
                    HistoryEntity(
                        createdAt = now - i * 1000L, level = "SOSPETTO", preview = "anteprima $i", reasons = "motivo",
                        fullText = crypto.encrypt("messaggio di esempio numero $i https://sito$i.example"),
                        verdictJson = crypto.encrypt("{\"v\":$i}"),
                        aiOpinion = crypto.encrypt("{\"a\":$i}"), nanoOpinion = crypto.encrypt("{\"n\":$i}"), domainAge = crypto.encrypt("{\"d\":$i}"),
                    ),
                )
                check(id == i.toLong())
            }
        }
        crypto.decrypts.set(0)
        return dao to repo
    }

    @Test fun openingAVerdictDecryptsOnlyThatRow() = runBlocking {
        val c = CountingCrypto()
        val (_, repo) = filled(3000, c)
        val e = repo.observeEntry(1500).first()!!
        assertEquals("messaggio di esempio numero 1500 https://sito1500.example", e.fullText)
        assertTrue("decifrature: ${c.decrypts.get()}", c.decrypts.get() <= 5)
    }

    @Test fun aDbUpdateDoesNotRedecryptUnchangedRows() = runBlocking {
        val c = CountingCrypto()
        val (_, repo) = filled(3000, c)
        repo.observeEntry(10).first()
        c.decrypts.set(0)
        repo.setAiOpinion(20, "{\"a\":\"nuovo\"}") // altra riga: la voce 10 resta in cache
        assertEquals("10", repo.observeEntry(10).first()!!.verdictJson.removePrefix("{\"v\":").removeSuffix("}"))
        assertEquals(0, c.decrypts.get())
        repo.setAiOpinion(10, "{\"a\":\"nuovo\"}") // stessa riga: solo il campo cambiato
        assertEquals("{\"a\":\"nuovo\"}", repo.observeEntry(10).first()!!.aiOpinion)
        assertTrue("decifrature: ${c.decrypts.get()}", c.decrypts.get() <= 1)
    }

    @Test fun firstPageDecryptsNothingAndIsBoundedByPageSize() = runBlocking {
        val c = CountingCrypto()
        val (_, repo) = filled(3000, c)
        val page = repo.observePage(HistoryFilter.ALL, "", 50).first()
        assertEquals(50, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("anteprima 1", page.items.first().preview)
        assertTrue(c.decrypts.get() <= 50)
        val more = repo.observePage(HistoryFilter.ALL, "", 3500).first()
        assertEquals(3000, more.items.size)
        assertFalse(more.hasMore)
    }

    @Test fun searchFindsInEncryptedTextAndRespectsLimit() = runBlocking {
        val c = CountingCrypto()
        val (_, repo) = filled(300, c)
        val hit = repo.observePage(HistoryFilter.ALL, "sito77.example", 50).first()
        assertEquals(listOf("anteprima 77"), hit.items.map { it.preview })
        val many = repo.observePage(HistoryFilter.ALL, "messaggio di esempio", 20).first()
        assertEquals(20, many.items.size)
        assertTrue(many.hasMore)
        val before = c.decrypts.get()
        repo.observePage(HistoryFilter.ALL, "sito77.example", 50).first() // seconda ricerca: testi gia' in cache
        assertEquals(before, c.decrypts.get())
    }

    @Test fun retentionAlwaysDeletesNothingAndSevenDaysDeletesOld() = runBlocking {
        val c = CountingCrypto()
        val (dao, repo) = filled(5, c, retention = 0)
        dao.insert(HistoryEntity(createdAt = now - 400 * day, level = "SOSPETTO", preview = "vecchia", reasons = ""))
        assertEquals(0, repo.purge())
        assertEquals(6, dao.rows.value.size)
        val weekly = HistoryRepository(dao, c, flowOf(7)) { now }
        assertEquals(1, weekly.purgeExpired())
        assertEquals(5, dao.rows.value.size)
    }

    @Test fun cacheIsBounded() {
        val cache = DecryptCache(maxEntries = 10, maxChars = 1_000_000)
        for (i in 1L..100L) cache.put(i, 0, "e$i", "p$i")
        assertEquals(10, cache.size)
        assertEquals("p100", cache.get(100, 0, "e100"))
        assertEquals(null, cache.get(1, 0, "e1"))
        assertEquals(null, cache.get(100, 0, "altro")) // testo cifrato cambiato: non si usa il valore in cache
        val small = DecryptCache(maxEntries = 100, maxChars = 10)
        small.put(1, 0, "a", "123456"); small.put(2, 0, "b", "123456")
        assertEquals(1, small.size)
    }

    @Test fun numbersStatusDecision() {
        assertEquals(NumbersStatus.INSTALLED, NumbersStatus.of(true, null))
        assertEquals(NumbersStatus.INSTALLED, NumbersStatus.of(true, false))
        assertEquals(NumbersStatus.PENDING_DOWNLOAD, NumbersStatus.of(false, true))
        assertEquals(NumbersStatus.NOT_AVAILABLE, NumbersStatus.of(false, false))
        assertEquals(NumbersStatus.NOT_AVAILABLE, NumbersStatus.of(false, null))
    }

    @Test fun purgeRunsAtMostOncePerDayInForeground() {
        assertTrue(PurgeThrottle.due(0L, now))
        assertFalse(PurgeThrottle.due(now - 1000, now))
        assertTrue(PurgeThrottle.due(now - day, now))
        assertTrue(PurgeThrottle.due(now + 5000, now)) // orologio tornato indietro
    }
}
