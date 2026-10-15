package app.eye.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifica opzionale: la cartella dati del sito (EYE_SITE_DATA) e' accettata dall'app con le chiavi di produzione. */
class ProductionFeedCheckTest {
    @Test fun siteDataVerifiesWithProductionKeys() {
        val dir = System.getenv("EYE_SITE_DATA")
        assumeTrue(dir != null && File(dir, "version.json").exists(), "EYE_SITE_DATA non impostato")
        val d = File(dir!!)
        val index = FeedIndex.parse(File(d, "version.json").readBytes()).getOrThrow()
        val rulesBytes = File(d, index.rules.url).readBytes()
        val campBytes = File(d, index.campaigns.url).readBytes()
        assertTrue(index.rules.matches(rulesBytes), "sha256 regole")
        assertTrue(index.campaigns.matches(campBytes), "sha256 campagne")
        val bundled = RuleLoader.parse(File("src/main/resources/rules/rules.json").readText()).version
        val rs = RulePackVerifier().verify(rulesBytes, bundled).getOrThrow()
        assertEquals(index.rules.version, rs.version)
        val feed = CampaignFeedVerifier().verify(campBytes, 0).getOrThrow()
        assertEquals(index.campaigns.version, feed.version)
        assertTrue(feed.campaigns.size in 6..10, "campagne: ${feed.campaigns.size}")
    }
}
