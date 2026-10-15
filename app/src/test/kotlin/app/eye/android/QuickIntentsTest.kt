package app.eye.android

import app.eye.android.core.CheckUseCase
import app.eye.android.core.QuickIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickIntentsTest {
    @Test fun nullAndBlankAreRejected() {
        assertNull(QuickIntents.clean(null))
        assertNull(QuickIntents.clean("  \n "))
    }

    @Test fun textIsTrimmedToLimitPlusOne() {
        val long = "a".repeat(CheckUseCase.MAX_CHARS + 500)
        assertEquals(CheckUseCase.MAX_CHARS + 1, QuickIntents.clean(long)!!.length)
        assertEquals("ciao", QuickIntents.clean(StringBuilder("ciao")))
    }

    @Test fun throwingCharSequenceIsNull() {
        val bad = object : CharSequence {
            override val length get() = 1
            override fun get(index: Int) = 'x'
            override fun subSequence(startIndex: Int, endIndex: Int) = this
            override fun toString(): String = throw IllegalStateException()
        }
        assertNull(QuickIntents.clean(bad))
    }
}
