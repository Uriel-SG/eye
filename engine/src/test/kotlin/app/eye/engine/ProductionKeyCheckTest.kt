package app.eye.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Verifica opzionale: un pacchetto firmato con la chiave di produzione (path in EYE_SIGNED_PACK) è accettato. */
class ProductionKeyCheckTest {
    @Test fun signedPackVerifiesWithProductionKeys() {
        val path = System.getenv("EYE_SIGNED_PACK")
        assumeTrue(path != null && File(path).exists(), "EYE_SIGNED_PACK non impostato")
        val r = RulePackVerifier().verify(File(path!!).readBytes(), installedVersion = 1)
        assertTrue(r.isSuccess, "verifica fallita: ${r.exceptionOrNull()}")
    }
}
