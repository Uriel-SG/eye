package app.eye.android

import app.eye.android.core.feed.FeedFailure
import app.eye.android.core.feed.FeedHttp
import app.eye.android.core.feed.FeedResponse
import app.eye.android.core.feed.FeedResult
import app.eye.android.core.feed.FeedState
import app.eye.android.core.feed.InstalledModel
import app.eye.android.core.feed.ModelChecker
import app.eye.android.core.feed.ModelUpdater
import app.eye.engine.FeedIndex
import app.eye.engine.ModelAutotest
import app.eye.engine.ModelFeedVerifier
import app.eye.engine.modelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** Aggiornamento del modello AI: manifest/sha256/anti-rollback/autotest fallito = resta il modello precedente. */
class ModelUpdaterTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pubB64 = Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())
    private val verifier = ModelFeedVerifier(mapOf("k" to pubB64))
    private val tokSha = "7".repeat(64)

    private fun sha(b: ByteArray) = FeedIndex.sha256Hex(b)

    private fun envelope(version: Long, model: ByteArray, tokenizerSha: String = tokSha, declaredSha: String = sha(model)): ByteArray {
        val plain = ("""{"version":$version,"keyId":"k","issuedAt":"2026-10-24T00:00:00Z","modelSha256":"$declaredSha","modelSize":${model.size},""" +
            """"modelUrl":"model-$version.onnx","tokenizerSha256":"$tokenizerSha","tokenizerSize":10,"raiseThreshold":0.9,""" +
            """"benign":[{"text":"a","pMax":0.2},{"text":"b","pMax":0.2},{"text":"c","pMax":0.2}],"scam":[{"text":"x","pMin":0.8},{"text":"y","pMin":0.8}]}""").toByteArray()
        val payload = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
        val msg = "${ModelFeedVerifier.DOMAIN}k\n$version\n".toByteArray() + payload
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(msg); sign() }
        val b = Base64.getEncoder()
        return """{"format":5,"alg":"Ed25519","keyId":"k","version":$version,"createdAt":"2026-10-24","payload":"${b.encodeToString(payload)}","sig":"${b.encodeToString(sig)}"}""".toByteArray()
    }

    private class Server : FeedHttp {
        val files = HashMap<String, ByteArray>()
        var downloads = 0
        override fun get(url: String, etag: String?, maxBytes: Int): FeedResponse =
            FeedResponse.Ok(files[url.substringAfterLast('/')] ?: throw IOException("404"), null)
        override fun download(url: String, dest: File, maxBytes: Long) {
            downloads++
            dest.writeBytes(files[url.substringAfterLast('/')] ?: throw IOException("404"))
        }
    }

    private class State : FeedState {
        var rejected = 0L
        override fun etag(consumer: String): String? = null
        override fun setEtag(consumer: String, etag: String?) {}
        override fun lastCampaignCheck() = 0L
        override fun setLastCampaignCheck(t: Long) {}
        override fun seenCampaignIds(): Set<String>? = null
        override fun setSeenCampaignIds(ids: Set<String>) {}
        override fun notifiedCampaignIds(): Set<String> = emptySet()
        override fun setNotifiedCampaignIds(ids: Set<String>) {}
        override fun rejectedModel() = rejected
        override fun setRejectedModel(version: Long) { rejected = version }
        override fun clear() {}
    }

    private val dir = Files.createTempDirectory("mu").toFile()
    private val feeds = File(dir, "feeds").apply { mkdirs() }
    private val models = File(dir, "models")
    private val store = modelStore(feeds)
    private val server = Server()
    private val state = State()
    private var checkResult = true
    private var checks = 0
    private var activated = 0
    private fun updater() = ModelUpdater(
        server, "https://x/", store, models, state, ModelChecker { _: File, _: ModelAutotest -> checks++; checkResult },
        { tokSha }, verifier, { activated++ },
    )

    private fun publish(version: Long, model: ByteArray, tokenizerSha: String = tokSha, declaredSha: String = sha(model)) {
        val env = envelope(version, model, tokenizerSha, declaredSha)
        server.files["model.eye.json"] = env
        server.files["model-$version.onnx"] = model
        server.files["version.json"] = """{"rules":{"version":1,"sha256":"${"0".repeat(64)}","url":"rules.eye.json"},"campaigns":{"version":1,"sha256":"${"0".repeat(64)}","url":"campaigns.eye.json"},"model":{"version":$version,"sha256":"${sha(env)}","url":"model.eye.json"}}""".toByteArray()
    }

    private val modelV4 = ByteArray(5000) { (it % 251).toByte() }
    private val modelV5 = ByteArray(5000) { (it % 241).toByte() } // stessa dimensione di v4

    @Test fun installsOnlyAfterShaAndAutotest() {
        publish(4, modelV4)
        assertEquals(FeedResult.Updated(4), updater().update())
        assertEquals(1, checks); assertEquals(1, activated)
        assertEquals(4, store.installedVersion())
        val m = InstalledModel.resolve(store, models, tokSha, verifier)!!
        assertEquals(4, m.manifest.version)
        assertTrue(m.file.name.contains(sha(modelV4).take(16)))
        assertEquals(listOf(m.file.name), models.list()!!.toList()) // nessun .part residuo
    }

    @Test fun failedAutotestKeepsPreviousModelAndIsNotRetried() {
        publish(4, modelV4)
        updater().update()
        val prev = InstalledModel.resolve(store, models, tokSha, verifier)!!
        publish(5, modelV5)
        checkResult = false
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), updater().update())
        assertEquals(0, activated - 1)
        assertEquals(4, store.installedVersion())
        assertEquals(prev.file.name, InstalledModel.resolve(store, models, tokSha, verifier)!!.file.name) // rollback: resta v4
        assertEquals(listOf(prev.file.name), models.list()!!.toList())
        assertEquals(5, state.rejected)
        val dl = server.downloads
        assertEquals(FeedResult.UpToDate, updater().update()) // la versione scartata non viene riscaricata
        assertEquals(dl, server.downloads)
        publish(6, modelV5)
        checkResult = true
        assertEquals(FeedResult.Updated(6), updater().update()) // una versione piu' nuova si
    }

    @Test fun shaMismatchIsRejected() {
        publish(4, modelV4, declaredSha = sha(modelV5))
        assertEquals(FeedResult.Failed(FeedFailure.BAD_DOWNLOAD), updater().update())
        assertEquals(0, checks) // il file corrotto non arriva nemmeno alla prova
        assertEquals(0, store.installedVersion())
        assertNull(InstalledModel.resolve(store, models, tokSha, verifier))
    }

    @Test fun antiRollbackAndBundledFloor() {
        publish(4, modelV4)
        updater().update()
        val dl = server.downloads
        assertEquals(FeedResult.UpToDate, updater().update()) // stessa versione
        publish(3, modelV5) // v3 = modello dell'APK: mai un modello "vecchio" dal feed
        assertEquals(FeedResult.UpToDate, updater().update())
        assertEquals(dl, server.downloads)
        assertEquals(4, store.installedVersion())
    }

    @Test fun differentTokenizerIsRefused() {
        publish(4, modelV4, tokenizerSha = "8".repeat(64))
        assertEquals(FeedResult.Failed(FeedFailure.REJECTED), updater().update())
        assertEquals(0, server.downloads)
        assertEquals(4, state.rejected)
    }

    @Test fun tamperedManifestOrNetworkLeavesStateUnchanged() {
        publish(4, modelV4)
        val env = server.files["model.eye.json"]!!
        server.files["model.eye.json"] = env.copyOf().also { it[it.size / 2] = 'Z'.code.toByte() }
        assertTrue(updater().update() is FeedResult.Failed) // sha dell'indice non torna
        assertEquals(0, server.downloads)
        server.files.remove("version.json")
        assertEquals(FeedResult.Failed(FeedFailure.NETWORK), updater().update())
        assertEquals(0, store.installedVersion())
    }

    @Test fun resolveRejectsTamperedFileOrWrongTokenizer() {
        publish(4, modelV4)
        updater().update()
        assertNotNull(InstalledModel.resolve(store, models, tokSha, verifier))
        assertNull(InstalledModel.resolve(store, models, "9".repeat(64), verifier)) // tokenizer dell'app cambiato
        val f = models.listFiles()!!.single()
        f.writeBytes(modelV5) // stessa dimensione, contenuto diverso: la chiave e' lo sha256, non la dimensione
        assertNull(InstalledModel.resolve(store, models, tokSha, verifier))
        f.delete()
        assertFalse(InstalledModel.resolve(store, models, tokSha, verifier) != null)
    }
}
