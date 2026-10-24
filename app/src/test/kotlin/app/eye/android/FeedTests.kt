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
import app.eye.engine.BlocklistVerifier
import app.eye.engine.HashBlocklist
import app.eye.engine.Campaign
import app.eye.engine.CampaignFeedVerifier
import app.eye.engine.CampaignSource
import app.eye.engine.FeedIndex
import app.eye.engine.RulePackStore
import app.eye.engine.RulePackVerifier
import app.eye.engine.loadBlocklist
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

    fun envelope(format: Int, domain: String, version: Long, plain: String): ByteArray = envelopeBytes(format, domain, version, plain.toByteArray())

    fun envelopeBytes(format: Int, domain: String, version: Long, plain: ByteArray): ByteArray {
        val payload = gzip(plain)
        val msg = "$domain$id\n$version\n".toByteArray() + payload
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(msg); sign() }
        val b = Base64.getEncoder()
        return """{"format":$format,"alg":"Ed25519","keyId":"$id","version":$version,"createdAt":"2026-10-15","payload":"${b.encodeToString(payload)}","sig":"${b.encodeToString(sig)}"}""".toByteArray()
    }

    /** Feed siti malevoli (format 3): stesso contenitore EBL1 del tool Python (differenze u32, escape se grandi). */
    fun blocklist(version: Long, vararg hosts: String): ByteArray {
        val hs = hosts.map { HashBlocklist.hashOf(it) }.distinct().sorted()
        val header = """{"version":$version,"keyId":"$id","issuedAt":"2026-10-15T00:00:00Z","expiresAt":"2026-11-29T00:00:00Z","count":${hs.size},"hashBytes":6}""".toByteArray()
        val o = ByteArrayOutputStream()
        o.write("EBL1".toByteArray()); o.write(java.nio.ByteBuffer.allocate(4).putInt(header.size).array()); o.write(header)
        fun u48(v: Long) = repeat(6) { o.write(((v shr (8 * (5 - it))) and 0xFF).toInt()) }
        hs.forEachIndexed { i, v ->
            if (i == 0) u48(v) else {
                val gap = v - hs[i - 1]
                if (gap < 0xFFFFFFFFL) o.write(java.nio.ByteBuffer.allocate(4).putInt(gap.toInt()).array()) else { repeat(4) { o.write(0xFF) }; u48(v) }
            }
        }
        return envelopeBytes(3, "EYE-BLOCKLIST-v1\n", version, o.toByteArray())
    }

    /** Feed numeri (format 4): contenitore ENL1, hash di `tel:` + E.164. [asSites] = elenco siti (format 3) con gli stessi byte, per i test di confusione tra feed. */
    fun numbers(version: Long, vararg e164: String, asSites: Boolean = false): ByteArray {
        val hs = e164.map { HashBlocklist.hashOf("tel:$it") }.distinct().sorted()
        val header = """{"version":$version,"keyId":"$id","issuedAt":"2026-10-15T00:00:00Z","expiresAt":"2026-11-29T00:00:00Z","count":${hs.size},"hashBytes":6}""".toByteArray()
        val o = ByteArrayOutputStream()
        o.write((if (asSites) "EBL1" else "ENL1").toByteArray()); o.write(java.nio.ByteBuffer.allocate(4).putInt(header.size).array()); o.write(header)
        fun u48(v: Long) = repeat(6) { o.write(((v shr (8 * (5 - it))) and 0xFF).toInt()) }
        hs.forEachIndexed { i, v -> if (i == 0) u48(v) else o.write(java.nio.ByteBuffer.allocate(4).putInt((v - hs[i - 1]).toInt()).array()) }
        return if (asSites) envelopeBytes(3, "EYE-BLOCKLIST-v1\n", version, o.toByteArray()) else envelopeBytes(4, "EYE-NUMBERS-v1\n", version, o.toByteArray())
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
    var lastAll = 0L
    override fun lastFeedUpdate() = lastAll
    override fun setLastFeedUpdate(t: Long) { lastAll = t }
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

    private class Env(val http: FakeHttp, val rules: MemStore, val camp: MemStore, val state: MemState, val updater: FeedUpdater, val bl: MemStore)

    private fun env(bundled: Long = 1, clock: () -> Long = { 1_000_000 }): Env {
        val http = FakeHttp(); val r = MemStore(); val c = MemStore(); val s = MemState(); val b = MemStore()
        return Env(http, r, c, s, FeedUpdater(http, base, r, c, s, bundled, RulePackVerifier(key.pinned), CampaignFeedVerifier(key.pinned), clock,
            blocklistStore = b, blocklistVerifier = BlocklistVerifier(key.pinned)), b)
    }

    private fun sha(b: ByteArray) = FeedIndex.sha256Hex(b)
    private fun index(rules: ByteArray?, rv: Long, camp: ByteArray?, cv: Long) = """{"rules":{"version":$rv,"sha256":"${sha(rules ?: ByteArray(1))}","url":"rules.eye.json"},"campaigns":{"version":$cv,"sha256":"${sha(camp ?: ByteArray(1))}","url":"campaigns.eye.json"}}""".toByteArray()

    private fun Env.publish(rules: ByteArray? = null, rv: Long = 1, camp: ByteArray? = null, cv: Long = 1) {
        http.files["version.json"] = index(rules, rv, camp, cv)
        rules?.let { http.files["rules.eye.json"] = it }
        camp?.let { http.files["campaigns.eye.json"] = it }
    }

    private fun Env.publishBlocklist(bl: ByteArray, v: Long) {
        http.files["version.json"] = ("""{"rules":{"version":1,"sha256":"${sha(ByteArray(1))}","url":"rules.eye.json"},"campaigns":{"version":1,"sha256":"${sha(ByteArray(1))}","url":"campaigns.eye.json"},""" +
            """"blocklist":{"version":$v,"sha256":"${sha(bl)}","url":"blocklist.eye.json"}}""").toByteArray()
        http.files["blocklist.eye.json"] = bl
    }

    private fun Env.publishNumbers(pack: ByteArray, v: Long) {
        http.files["version.json"] = ("""{"rules":{"version":1,"sha256":"${sha(ByteArray(1))}","url":"rules.eye.json"},"campaigns":{"version":1,"sha256":"${sha(ByteArray(1))}","url":"campaigns.eye.json"},""" +
            """"numbers":{"version":$v,"sha256":"${sha(pack)}","url":"numbers.eye.json"}}""").toByteArray()
        http.files["numbers.eye.json"] = pack
    }

    private fun numbersEnv(): Pair<Env, MemStore> {
        val http = FakeHttp(); val r = MemStore(); val c = MemStore(); val s = MemState(); val n = MemStore()
        return Env(http, r, c, s, FeedUpdater(http, base, r, c, s, 1, RulePackVerifier(key.pinned), CampaignFeedVerifier(key.pinned), { 1_000_000 },
            numbersStore = n, numbersVerifier = app.eye.engine.numberlistVerifier(key.pinned)), MemStore()) to n
    }

    @Test fun numbersInstallVerifiedAndRefuseForgedRolledBackOrConfusedFeeds() {
        val (e, store) = numbersEnv()
        e.publishNumbers(key.numbers(4, "+393331234567", "+390212345678"), 4)
        assertEquals(FeedResult.Updated(4), e.updater.updateNumbers())
        assertEquals(4L, store.version)
        val list = store.loadBlocklist(app.eye.engine.numberlistVerifier(key.pinned))!!
        assertTrue(list.contains("tel:+393331234567")); assertFalse(list.contains("tel:+393331234568"))
        // chiave non pinnata
        e.http.etag = "v2"; e.publishNumbers(TestKey("test-a").numbers(5, "+393331234567"), 5)
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateNumbers())
        // rollback
        e.http.etag = "v3"; e.publishNumbers(key.numbers(3, "+393331234567"), 6)
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateNumbers())
        // un elenco siti (format 3) servito come numeri
        e.http.etag = "v4"; e.publishNumbers(key.numbers(7, "+393331234567", asSites = true), 7)
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateNumbers())
        assertEquals(4L, store.version)
        // indice senza voce numbers (sito vecchio) o senza store: nessun errore
        e.http.etag = "v5"; e.http.files["version.json"] = index(null, 1, null, 1)
        assertEquals(FeedResult.UpToDate, e.updater.updateNumbers())
        assertNull(FeedIndex.parse(index(null, 1, null, 1)).getOrThrow().numbers)
    }

    @Test fun blocklistInstallsVerifiedAndUsable() {
        val e = env(); val pack = key.blocklist(3, "evil.com", "x.github.io")
        e.publishBlocklist(pack, 3)
        assertEquals(FeedResult.Updated(3), e.updater.updateBlocklist())
        assertEquals(3L, e.bl.version)
        val list = e.bl.loadBlocklist(BlocklistVerifier(key.pinned))!!
        assertTrue(list.isKnownMalicious("a.evil.com", "evil.com")); assertFalse(list.isKnownMalicious("other.com", "other.com"))
        e.http.requests.clear()
        assertEquals(FeedResult.UpToDate, e.updater.updateBlocklist()) // 304
        assertEquals(listOf("version.json"), e.http.requests)
    }

    @Test fun blocklistForgedTamperedOrRolledBackIsRefused() {
        val e = env()
        e.publishBlocklist(TestKey("test-a").blocklist(1, "evil.com"), 1) // stessa id, chiave diversa
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateBlocklist())
        assertEquals(0L, e.bl.version); assertNull(e.bl.pack)
        val ok = key.blocklist(5, "evil.com")
        e.publishBlocklist(ok, 5); assertEquals(FeedResult.Updated(5), e.updater.updateBlocklist())
        // file piu' vecchio (valido) dichiarato come v6: rollback rifiutato
        e.http.etag = "v2"; e.publishBlocklist(key.blocklist(3, "evil.com"), 6)
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), e.updater.updateBlocklist())
        assertEquals(5L, e.bl.version)
        // sha256 dell'indice diverso dal file scaricato: scartato prima della firma
        e.http.etag = "v3"; e.publishBlocklist(ok, 7); e.http.files["blocklist.eye.json"] = ok.copyOf(ok.size - 3)
        assertEquals(FeedResult.Failed(FeedFailure.BAD_DOWNLOAD), e.updater.updateBlocklist())
    }

    @Test fun blocklistHasItsOwnSizeLimitAndIndexStaysCompatible() {
        val e = env()
        e.publishBlocklist(ByteArray(BlocklistVerifier.MAX_ENVELOPE + 1), 2)
        assertEquals(FeedResult.Failed(FeedFailure.BAD_DOWNLOAD), e.updater.updateBlocklist())
        // indice senza voce blocklist (sito vecchio): nessun errore
        e.http.etag = "v2"; e.publish(camp = key.campaigns(1, "a"))
        assertEquals(FeedResult.UpToDate, e.updater.updateBlocklist())
        assertNull(FeedIndex.parse(index(null, 1, null, 1)).getOrThrow().blocklist)
        // voce blocklist malformata: l'indice resta valido per regole e campagne
        val bad = """{"rules":{"version":1,"sha256":"${sha(ByteArray(1))}","url":"rules.eye.json"},"campaigns":{"version":1,"sha256":"${sha(ByteArray(1))}","url":"campaigns.eye.json"},"blocklist":{"version":"x","sha256":"zz","url":"../x"}}"""
        assertNull(FeedIndex.parse(bad.toByteArray()).getOrThrow().blocklist)
    }

    @Test fun updateAllRefreshesEverythingAndStampsTime() {
        val e = env(bundled = 1, clock = { 5_000_000 })
        val rules = key.rules(2); val camp = key.campaigns(1, "uno"); val bl = key.blocklist(1, "evil.com")
        e.http.files["version.json"] = ("""{"rules":{"version":2,"sha256":"${sha(rules)}","url":"rules.eye.json"},"campaigns":{"version":1,"sha256":"${sha(camp)}","url":"campaigns.eye.json"},""" +
            """"blocklist":{"version":1,"sha256":"${sha(bl)}","url":"blocklist.eye.json"}}""").toByteArray()
        e.http.files["rules.eye.json"] = rules; e.http.files["campaigns.eye.json"] = camp; e.http.files["blocklist.eye.json"] = bl
        val all = e.updater.updateAll()
        assertEquals(FeedResult.Updated(2), all.rules); assertEquals(FeedResult.Updated(1), all.campaigns); assertEquals(FeedResult.Updated(1), all.blocklist)
        assertEquals(FeedResult.Updated(2), all.summary)
        assertEquals(5_000_000L, e.state.lastAll)
        e.state.lastAll = 0; e.http.offline = true
        assertEquals(FeedResult.Failed(FeedFailure.NETWORK), e.updater.updateAll().summary)
        assertEquals(0L, e.state.lastAll) // un tentativo fallito non aggiorna la data
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
