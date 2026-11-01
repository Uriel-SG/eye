package app.eye.android

import app.eye.android.core.feed.FallbackFeedHttp
import app.eye.android.core.feed.FeedHttp
import app.eye.android.core.feed.FeedResponse
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FallbackFeedHttpTest {
    private val p = "https://primary.test/data/"
    private val f = "https://fallback.test/data/"

    private class Fake(val down: Set<String>) : FeedHttp {
        val seen = ArrayList<String>()
        override fun get(url: String, etag: String?, maxBytes: Int): FeedResponse {
            seen += url
            if (down.any { url.startsWith(it) }) throw IOException("HTTP 503")
            return FeedResponse.Ok(url.toByteArray(), null)
        }
    }

    @Test fun primaryOk() {
        val h = Fake(emptySet())
        val r = FallbackFeedHttp(h, p, f).get(p + "version.json", null, 100) as FeedResponse.Ok
        assertEquals(p + "version.json", String(r.bytes)); assertEquals(1, h.seen.size)
    }

    @Test fun fallsBackPerRequest() {
        val h = Fake(setOf(p))
        val x = FallbackFeedHttp(h, p, f)
        val r = x.get(p + "rules.eye.json", null, 100) as FeedResponse.Ok
        assertEquals(f + "rules.eye.json", String(r.bytes))
        assertEquals(listOf(p + "rules.eye.json", f + "rules.eye.json"), h.seen)
    }

    @Test fun bothDownThrows() {
        try { FallbackFeedHttp(Fake(setOf(p, f)), p, f).get(p + "a", null, 100); fail() } catch (_: IOException) {}
    }

    @Test fun foreignUrlNotRewritten() {
        val h = Fake(setOf("https://other.test/"))
        try { FallbackFeedHttp(h, p, f).get("https://other.test/x", null, 100); fail() } catch (_: IOException) {}
        assertEquals(1, h.seen.size)
    }
}
