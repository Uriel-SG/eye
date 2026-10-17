package app.eye.android

import app.eye.android.core.Catalog
import app.eye.android.core.feed.FeedFailure
import app.eye.android.core.feed.FeedHttp
import app.eye.android.core.feed.FeedResponse
import app.eye.android.core.feed.FeedResult
import app.eye.android.core.feed.FeedState
import app.eye.android.core.feed.FeedTooLargeException
import app.eye.android.core.feed.FeedUpdater
import app.eye.android.core.feed.NewCampaigns
import app.eye.android.core.feed.UrlConnectionFeedHttp
import app.eye.android.core.notif.NotifData
import app.eye.android.core.notif.NotificationGate
import app.eye.android.core.notif.alertId
import app.eye.engine.Campaign
import app.eye.engine.CampaignFeedVerifier
import app.eye.engine.CampaignSource
import app.eye.engine.FeedIndex
import app.eye.engine.RulePackStore
import app.eye.engine.RulePackVerifier
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.GZIPOutputStream

private class TestKey(val id: String) {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val pubB64: String = Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())
    val pinned = mapOf(id to pubB64)

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    fun envelope(format: Int, domain: String, version: Long, plain: String): ByteArray {
        val payload = gzip(plain.toByteArray())
        val msg = "$domain$id\n$version\n".toByteArray() + payload
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(msg); sign() }
        val b = Base64.getEncoder()
        return """{"format":$format,"alg":"Ed25519","keyId":"$id","version":$version,"createdAt":"2026-10-15","payload":"${b.encodeToString(payload)}","sig":"${b.encodeToString(sig)}"}""".toByteArray()
    }

    fun campaigns(version: Long, vararg ids: String, status: String = "in_corso"): ByteArray {
        val items = ids.joinToString(",") {
            """{"id":"$it","title":"Titolo $it","category":"smishing","summary":"Riassunto","signals":["s1"],"actions":["a1"],"example":"es","firstSeen":"2026-10-01","updatedAt":"2026-10-1${ids.indexOf(it)}","source":{"name":"Polizia Postale","url":"https://www.commissariatodips.it/x"},"status":"$status"}"""
        }
        return envelope(2, "EYE-CAMPAIGNS-v1\n", version, """{"version":$version,"keyId":"$id","issuedAt":"2026-10-15T00:00:00Z","expiresAt":"2026-11-29T00:00:00Z","campaigns":[$items]}""")
    }

    fun rules(version: Long): ByteArray {
        val bundled = javaClass.classLoader.getResource("rules/rules.json")!!.readText()
        // Sostituisce la versione qualunque sia quella delle regole incluse.
        val plain = bundled.replaceFirst(Regex("\"version\":\\s*\\d+,"), "\"version\": $version,\n \"keyId\": \"$id\",")
        return envelope(1, "EYE-RULES-v1\n", version, plain)
    }
}

private class MemStore : RulePackStore {
    var version = 0L
    var pack: ByteArray? = null
    override fun installedVersion() = version
    override fun readPack() = pack
    override fun save(envelope: ByteArray, version: Long) { pack = envelope; this.version = version }
}

private class MemState : FeedState {
    val etags = HashMap<String, String?>()
    var checked = 0L
    var seen: Set<String>? = null
    var notified: Set<String> = emptySet()
    override fun etag(consumer: String) = etags[consumer]
    override fun setEtag(consumer: String, etag: String?) { etags[consumer] = etag }
    override fun lastCampaignCheck() = checked
    override fun setLastCampaignCheck(t: Long) { checked = t }
    override fun seenCampaignIds() = seen
    override fun setSeenCampaignIds(ids: Set<String>) { seen = ids }
    override fun notifiedCampaignIds() = notified
    override fun setNotifiedCampaignIds(ids: Set<String>) { notified = ids }
    override fun clear() { etags.clear(); checked = 0; seen = null; notified = emptySet() }
}

/** Server finto: path -> corpo; supporta ETag/304 e fallimenti di rete. */
private class FakeHttp : FeedHttp {
    val files = HashMap<String, ByteArray>()
    var etag: String? = "v1"
    var offline = false
    val requests = ArrayList<String>()
    override fun get(url: String, etag: String?, maxBytes: Int): FeedResponse {
        requests += url.substringAfterLast('/')
        if (offline) throw IOException("offline")
        val body = files[url.substringAfterLast('/')] ?: throw IOException("HTTP 404")
        if (url.endsWith("version.json") && etag != null && etag == this.etag) return FeedResponse.NotModified
        if (body.size > maxBytes) throw FeedTooLargeException()
        return FeedResponse.Ok(body, if (url.endsWith("version.json")) this.etag else null)
    }
}

class FeedTests {
    private val key = TestKey("test-a")
    private val base = "https://example.test/data/"

    private class Env(val http: FakeHttp, val rules: MemStore, val camp: MemStore, val state: MemState, val updater: FeedUpdater)

    private fun env(bundled: Long = 1, clock: () -> Long = { 1_000_000 }): Env {
        val http = FakeHttp(); val r = MemStore(); val c = MemStore(); val s = MemState()
        return Env(http, r, c, s, FeedUpdater(http, base, r, c, s, bundled, RulePackVerifier(key.pinned), CampaignFeedVerifier(key.pinned), clock))
    }

    private fun sha(b: ByteArray) = FeedIndex.sha256Hex(b)
    private fun index(rules: ByteArray?, rv: Long, camp: ByteArray?, cv: Long) = """{"rules":{"version":$rv,"sha256":"${sha(rules ?: ByteArray(1))}","url":"rules.eye.json"},"campaigns":{"version":$cv,"sha256":"${sha(camp ?: ByteArray(1))}","url":"campaigns.eye.json"}}""".toByteArray()

    private fun Env.publish(rules: ByteArray? = null, rv: Long = 1, camp: ByteArray? = null, cv: Long = 1) {
        http.files["version.json"] = index(rules, rv, camp, cv)
        rules?.let { http.files["rules.eye.json"] = it }
        camp?.let { http.files["campaigns.eye.json"] = it }
    }

    @Test fun campaignsInstallThenNotModified() {
        val e = env(); val pack = key.campaigns(1, "pacco-x")
        e.publish(camp = pack)
        assertEquals(FeedResult.Updated(1), e.updater.updateCampaigns())
        assertEquals(1L, e.camp.version)
        e.http.requests.clear()
        assertEquals(FeedResult.UpToDate, e.updater.updateCampaigns()) // 304: solo version.json, nessun download
        assertEquals(listOf("version.json"), e.http.requests)
    }

    @Test fun invalidSignatureIsRejectedAndStateUntouched() {
        val e = env(); val other = TestKey("test-a") // stessa id, chiave diversa
        val forged = other.campaigns(1, "falsa")
        e.publish(camp = forged)
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateCampaigns())
        assertEquals(0L, e.camp.version); assertNull(e.camp.pack); assertNull(e.state.etag("campaigns"))
    }

    @Test fun rollbackIsRefused() {
        val e = env()
        e.publish(camp = key.campaigns(5, "uno"), cv = 5)
        assertEquals(FeedResult.Updated(5), e.updater.updateCampaigns())
        // indice che mente: dichiara v6 ma serve il file v3 (vecchio ma validamente firmato)
        val old = key.campaigns(3, "due")
        e.http.etag = "v2"; e.publish(camp = old, cv = 6)
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateCampaigns())
        assertEquals(5L, e.camp.version)
        // indice con versione non superiore: nemmeno si scarica
        e.http.etag = "v3"; e.http.requests.clear(); e.publish(camp = old, cv = 5)
        assertEquals(FeedResult.UpToDate, e.updater.updateCampaigns())
        assertFalse("campaigns.eye.json" in e.http.requests)
    }

    @Test fun truncatedOrWrongHashDownloadIsNotInstalled() {
        val e = env(); val pack = key.campaigns(1, "uno")
        e.publish(camp = pack)
        e.http.files["campaigns.eye.json"] = pack.copyOf(pack.size - 5)
        assertEquals(FeedResult.Failed(FeedFailure.BAD_DOWNLOAD), e.updater.updateCampaigns())
        assertEquals(0L, e.camp.version)
    }

    @Test fun networkFailureAndBadIndex() {
        val e = env(); e.http.offline = true
        assertEquals(FeedResult.Failed(FeedFailure.NETWORK), e.updater.updateCampaigns())
        assertEquals(FeedResult.Failed(FeedFailure.NETWORK), e.updater.updateRules())
        e.http.offline = false; e.http.files["version.json"] = "{ non json".toByteArray()
        assertEquals(FeedResult.Failed(FeedFailure.BAD_INDEX), e.updater.updateRules())
        e.http.files["version.json"] = ByteArray(FeedIndex.MAX_BYTES + 1)
        assertEquals(FeedResult.Failed(FeedFailure.NETWORK), e.updater.updateRules()) // oltre il tetto: scartato
    }

    @Test fun rulesOnlyWhenNewerThanBundledAndInstalled() {
        val e = env(bundled = 2)
        e.publish(rules = key.rules(2), rv = 2)
        e.http.requests.clear()
        assertEquals(FeedResult.UpToDate, e.updater.updateRules()) // non supera quella inclusa
        assertFalse("rules.eye.json" in e.http.requests)
        e.http.etag = "v2"; e.publish(rules = key.rules(3), rv = 3)
        assertEquals(FeedResult.Updated(3), e.updater.updateRules())
        assertEquals(3L, e.rules.version)
        e.http.etag = "v3"; e.publish(rules = key.rules(3), rv = 3)
        assertEquals(FeedResult.UpToDate, e.updater.updateRules())
    }

    @Test fun rulesAndCampaignsEnvelopesAreNotInterchangeable() {
        val e = env()
        e.publish(rules = key.campaigns(9, "x"), rv = 9) // file campagne servito come regole
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateRules())
        assertEquals(0L, e.rules.version)
    }

    @Test fun freeRefreshIsRateLimitedToOneHour() {
        var now = 10_000_000L
        val e = env(clock = { now })
        e.publish(camp = key.campaigns(1, "uno"))
        assertTrue(e.updater.updateCampaigns(FeedUpdater.FREE_MIN_INTERVAL_MS) is FeedResult.Updated)
        e.http.requests.clear(); now += 30 * 60_000
        assertEquals(FeedResult.UpToDate, e.updater.updateCampaigns(FeedUpdater.FREE_MIN_INTERVAL_MS))
        assertTrue(e.http.requests.isEmpty())
        now += 31 * 60_000
        e.updater.updateCampaigns(FeedUpdater.FREE_MIN_INTERVAL_MS)
        assertFalse(e.http.requests.isEmpty())
    }

    // --- merge catalogo
    private fun campaign(id: String, updated: String = "2026-10-10", status: String = "in_corso") = Campaign(
        id, "Titolo $id", "whatsapp", "Riassunto", listOf("s"), listOf("a"), "es", "2026-10-01", updated,
        CampaignSource("Polizia Postale", "https://www.commissariatodips.it/x"), status,
    )

    @Test fun mergeKeepsStaticCardsAndCampaignWinsOnSameId() {
        val merged = Catalog.merge(listOf(campaign("nuova-a"), campaign("pacco-sms", "2026-10-14")))
        assertEquals(Catalog.cards.size + 1, merged.size) // pacco-sms sostituita, nuova-a aggiunta
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        assertTrue(Catalog.cards.all { s -> merged.any { it.id == s.id } }) // nessuna scheda statica persa
        val replaced = merged.first { it.id == "pacco-sms" }
        assertEquals("Titolo pacco-sms", replaced.name); assertEquals("2026-10-14", replaced.updatedAt)
        assertEquals("https://www.commissariatodips.it/x", replaced.reportLinks.single().url)
        assertEquals(app.eye.android.core.ScamCategory.WHATSAPP, merged.first { it.id == "nuova-a" }.category)
        assertEquals("pacco-sms", merged.first().id) // la più recente per prima
    }

    @Test fun mergeWithNoFeedIsTheStaticCatalog() {
        assertEquals(Catalog.cards, Catalog.merge(emptyList()))
        assertTrue(Catalog.merge(listOf(campaign("conclusa-x", status = "conclusa"))).first { it.id == "conclusa-x" }.ongoing.not())
    }

    @Test fun newCampaignsOnlyInProgressUnseenUnnotified() {
        val l = listOf(campaign("a"), campaign("b"), campaign("c", status = "conclusa"), campaign("d"))
        assertTrue(NewCampaigns.pick(l, null, emptySet()).isEmpty()) // prima volta: nessuna raffica
        assertEquals(setOf("b", "d"), NewCampaigns.pick(l, setOf("a"), emptySet()).map { it.id }.toSet())
        assertEquals(listOf("d"), NewCampaigns.pick(l, setOf("a"), setOf("b")).map { it.id })
        assertEquals(1, NewCampaigns.pick(l, setOf("a"), emptySet(), max = 1).size)
    }

    // --- client HTTP reale su server locale
    @Test fun httpClientHandles304LimitsAndCleartext() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/ok") { ex ->
            if (ex.requestHeaders.getFirst("If-None-Match") == "\"e1\"") { ex.sendResponseHeaders(304, -1) } else {
                ex.responseHeaders.add("ETag", "\"e1\""); ex.sendResponseHeaders(200, 5); ex.responseBody.write("hello".toByteArray())
            }
            ex.close()
        }
        srv.createContext("/big") { ex -> ex.sendResponseHeaders(200, 100); ex.responseBody.write(ByteArray(100)); ex.close() }
        srv.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "http://evil.example/"); ex.sendResponseHeaders(302, -1); ex.close() }
        srv.start()
        try {
            val url = "http://127.0.0.1:${srv.address.port}"
            val http = UrlConnectionFeedHttp(allowCleartext = true)
            val r = http.get("$url/ok", null, 100) as FeedResponse.Ok
            assertArrayEquals("hello".toByteArray(), r.bytes); assertEquals("\"e1\"", r.etag)
            assertTrue(http.get("$url/ok", "\"e1\"", 100) is FeedResponse.NotModified)
            try { http.get("$url/big", null, 10); fail() } catch (_: FeedTooLargeException) {}
            try { http.get("$url/redir", null, 10); fail() } catch (_: IOException) {} // nessun redirect seguito
            try { http.get("$url/missing", null, 10); fail() } catch (_: IOException) {}
            try { UrlConnectionFeedHttp(allowCleartext = false).get("$url/ok", null, 100); fail() } catch (e: IOException) { assertEquals("solo https", e.message) }
        } finally { srv.stop(0) }
    }

    // --- audit V3: carico e minimizzazione sulle notifiche
    private fun n(text: String, pkg: String = "com.whatsapp", title: String = "Mario") = NotifData(pkg, false, false, title, text, null)

    @Test fun burstOf100DistinctNotificationsIsCappedPerMinute() {
        var now = 0L
        val g = NotificationGate("x", clock = { now })
        val mon = setOf("com.whatsapp")
        var analysed = 0
        repeat(100) { i -> if (g.accept(n("messaggio $i"), mon) != null && g.allowAnalysis()) analysed++ }
        assertEquals(30, analysed)
        now = 61_000
        assertTrue(g.allowAnalysis()) // il minuto dopo si riparte
    }

    @Test fun dedupeIsNotFooledByHashCodeCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val g = NotificationGate("x"); val mon = setOf("com.whatsapp")
        assertNotNull(g.accept(n("Aa"), mon)); assertNotNull(g.accept(n("BB"), mon))
        assertNull(g.accept(n("BB"), mon))
    }

    @Test fun alertIdIsStablePerConversationAndAtMostOneLoudPerTwoMinutes() {
        assertEquals(alertId("com.whatsapp", "Mario"), alertId("com.whatsapp", "Mario"))
        assertNotEquals(alertId("com.whatsapp", "Mario"), alertId("com.whatsapp", "Luigi"))
        assertNotEquals(alertId("com.whatsapp", "Mario"), alertId("org.telegram.messenger", "Mario"))
        var now = 0L
        val g = NotificationGate("x", clock = { now })
        assertTrue(g.allowLoud()); now = 119_000; assertFalse(g.allowLoud()); now = 120_000; assertTrue(g.allowLoud())
    }
}
