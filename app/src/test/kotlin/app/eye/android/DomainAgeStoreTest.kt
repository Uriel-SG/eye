package app.eye.android

import app.eye.android.core.age.DomainAgeResult
import app.eye.android.core.age.DomainAgeService
import app.eye.android.core.age.RdapHttp
import app.eye.android.core.age.RdapHttpResult
import app.eye.android.data.DomainAgeJson
import app.eye.android.data.HistoryRepository
import app.eye.android.data.MIGRATION_6_7
import app.eye.android.data.TextCrypto
import app.eye.engine.enrich.DomainAgeEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/** Eta' del dominio: salvataggio in cronologia (cifrato), migrazione Room 6 -> 7, servizio RDAP con cache. */
class DomainAgeStoreTest {
    private object Rev : TextCrypto {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(stored: String): String? = stored.removePrefix("enc:").reversed()
    }

    private val res = DomainAgeResult(listOf(DomainAgeEntry("promo-x.top", 123L), DomainAgeEntry("a.it", null)), 999L, false)

    @Test fun jsonRoundTripAndRejectsGarbage() {
        assertEquals(res, DomainAgeJson.decode(DomainAgeJson.encode(res)))
        assertNull(DomainAgeJson.decode(""))
        assertNull(DomainAgeJson.decode("{"))
        assertNull(DomainAgeJson.decode("""{"d":[]}"""))
    }

    @Test fun storedEncryptedAndLevelOnlyRises() = runBlocking {
        val dao = FakeDao()
        val repo = HistoryRepository(dao, crypto = Rev)
        val id = repo.save("NESSUN_SEGNALE", "p", listOf("m"), "testo", "{}")
        assertEquals("", repo.observe().first().single().domainAge)
        val json = DomainAgeJson.encode(res)
        repo.setDomainAge(id, json)
        assertFalse("dominio in chiaro su disco", dao.rows.value.single().domainAge.contains("promo-x"))
        assertEquals(json, repo.observe().first().single().domainAge)
        repo.setLevel(id, "PROBABILE_TRUFFA")
        repo.setLevel(id, "SOSPETTO") // un rialzo piu' debole arrivato dopo non abbassa
        repo.setLevel(id, "NESSUN_SEGNALE")
        assertEquals("PROBABILE_TRUFFA", repo.observe().first().single().level)
        repo.setDomainAge(id, "")
        assertEquals("", repo.observe().first().single().domainAge)
    }

    @Test fun migration6to7AddsColumnWithoutDroppingData() {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, m, a ->
            if (m.name == "execSQL") sql += a!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_6_7.migrate(db)
        assertEquals(6, MIGRATION_6_7.startVersion); assertEquals(7, MIGRATION_6_7.endVersion)
        assertEquals(listOf("ALTER TABLE history ADD COLUMN domainAge TEXT NOT NULL DEFAULT ''"), sql)
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) })
    }

    // ---- servizio RDAP ----
    private val bootstrap = """{"services":[[["com"],["https://rdap.verisign.com/com/v1/"]]]}"""
    private val day = 24L * 60 * 60 * 1000

    private class FakeHttp(val bootstrap: String, val domain: () -> RdapHttpResult) : RdapHttp {
        val urls = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): RdapHttpResult {
            synchronized(urls) { urls += url }
            return if (url.endsWith("dns.json")) RdapHttpResult.Ok(bootstrap) else domain()
        }
    }

    private fun dir(): File = Files.createTempDirectory("rdap").toFile()
    private val reg = """{"events":[{"eventAction":"registration","eventDate":"2026-10-15T00:00:00Z"}]}"""

    @Test fun sendsOnlyTheDomainNameToTheRegistryAndCachesFor7Days() = runBlocking {
        var now = 1_000L * day
        val http = FakeHttp(bootstrap) { RdapHttpResult.Ok(reg) }
        val svc = DomainAgeService(dir(), http) { now }
        val r = svc.check(listOf("promo-x.com"))
        assertFalse(r.incomplete)
        assertEquals(java.time.Instant.parse("2026-10-15T00:00:00Z").toEpochMilli(), r.entries.single().registeredAtMs)
        assertTrue(http.urls.contains("https://rdap.verisign.com/com/v1/domain/promo-x.com"))
        assertTrue(http.urls.all { it.startsWith("https://") && "message" !in it })
        val calls = http.urls.size
        now += 6 * day
        svc.check(listOf("promo-x.com")); assertEquals("cache 7 giorni", calls, http.urls.size)
        now += 2 * day
        svc.check(listOf("promo-x.com")); assertEquals("scaduta: nuova richiesta", calls + 1, http.urls.size)
    }

    @Test fun tldWithoutRdapIsUnavailableNotAnError() = runBlocking {
        val http = FakeHttp(bootstrap) { error("non deve essere chiamato") }
        val r = DomainAgeService(dir(), http) { 5L }.check(listOf("poste-verifica.it"))
        assertFalse(r.incomplete)
        assertNull(r.entries.single().registeredAtMs)
    }

    @Test fun networkErrorIsIncompleteAndNotCached() = runBlocking {
        var fail = true
        val http = FakeHttp(bootstrap) { if (fail) RdapHttpResult.Failed else RdapHttpResult.Ok(reg) }
        val svc = DomainAgeService(dir(), http) { 1_000L * day }
        assertTrue(svc.check(listOf("promo-x.com")).incomplete)
        fail = false
        val r = svc.check(listOf("promo-x.com"))
        assertFalse(r.incomplete); assertEquals(1, r.entries.size); assertTrue(r.entries[0].registeredAtMs != null)
    }

    @Test fun notFoundIsCachedAsUnavailableAndAtMostThreeDomains() = runBlocking {
        val http = FakeHttp(bootstrap) { RdapHttpResult.NotFound }
        val r = DomainAgeService(dir(), http) { 7L }.check(listOf("a.com", "b.com", "c.com", "d.com"))
        assertEquals(3, r.entries.size)
        assertTrue(r.entries.all { it.registeredAtMs == null } && !r.incomplete)
    }

    @Test fun bootstrapIsCachedAndStaleOneUsedWhenDownloadFails() = runBlocking {
        var now = 1_000L * day
        val d = dir()
        val http = FakeHttp(bootstrap) { RdapHttpResult.NotFound }
        DomainAgeService(d, http) { now }.check(listOf("a.com"))
        now += 10 * day
        DomainAgeService(d, http) { now }.check(listOf("b.com"))
        assertEquals("bootstrap scaricato una volta", 1, http.urls.count { it.endsWith("dns.json") })
        now += 40 * day // scaduto (30 giorni) ma lo scarico fallisce: si usa quello vecchio
        val offline = object : RdapHttp {
            override fun get(url: String, maxBytes: Int) = if (url.endsWith("dns.json")) RdapHttpResult.Failed else RdapHttpResult.NotFound
        }
        val r = DomainAgeService(d, offline) { now }.check(listOf("c.com"))
        assertFalse(r.incomplete)
    }
}
