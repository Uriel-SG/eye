package app.eye.android

import app.eye.android.core.ai.OnnxTextClassifier
import org.junit.Test
import org.junit.Assert.assertNotEquals

class ModelCacheKeyTest {
    /** Modello v2 e v3 hanno la stessa dimensione: dopo un aggiornamento la chiave deve cambiare. */
    @Test fun sameSizeAfterAppUpdateGivesDifferentKey() {
        assertNotEquals(
            OnnxTextClassifier.modelCacheName(35_010_057L, 1_000L),
            OnnxTextClassifier.modelCacheName(35_010_057L, 2_000L),
        )
    }

    @Test fun differentSizeGivesDifferentKey() {
        assertNotEquals(OnnxTextClassifier.modelCacheName(1L, 5L), OnnxTextClassifier.modelCacheName(2L, 5L))
    }
}
