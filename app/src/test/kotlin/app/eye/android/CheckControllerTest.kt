package app.eye.android

import app.eye.android.core.CheckUseCase
import app.eye.android.data.HistoryRepository
import app.eye.android.ui.CheckController
import app.eye.android.ui.CheckState
import app.eye.engine.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckControllerTest {
    private fun controller(scope: TestScope, analyzed: MutableList<Int>): CheckController {
        val analyzer = object : app.eye.engine.Analyzer {
            override fun analyze(input: app.eye.engine.Input): app.eye.engine.Verdict {
                analyzed += input.text.length
                return FakeAnalyzer().analyze(input)
            }
        }
        val uc = CheckUseCase(analyzer, HistoryRepository(FakeDao()), { false })
        return CheckController(scope, uc, StandardTestDispatcher(scope.testScheduler))
    }

    @Test fun sharedTextIsCheckedAutomatically() = runTest {
        val lens = mutableListOf<Int>()
        val c = controller(this, lens)
        c.onShared("Urgente: codice PIN subito https://x.top")
        advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is CheckState.Done)
        assertFalse((s as CheckState.Done).result.truncated)
    }

    @Test fun longSharedTextIsTruncatedWithWarningFlag() = runTest {
        val lens = mutableListOf<Int>()
        val c = controller(this, lens)
        c.onShared("a".repeat(CheckUseCase.MAX_CHARS + 500))
        advanceUntilIdle()
        val s = c.state.value as CheckState.Done
        assertTrue(s.result.truncated)
        assertEquals(listOf(CheckUseCase.MAX_CHARS), lens)
    }

    @Test fun pastedTextStaysIdleUntilCheck() = runTest {
        val c = controller(this, mutableListOf())
        advanceUntilIdle()
        assertEquals(CheckState.Idle, c.state.value)
        c.check("ciao", Source.PASTE)
        advanceUntilIdle()
        assertTrue(c.state.value is CheckState.Done)
    }

    @Test fun blankSharedTextIsIgnored() = runTest {
        val c = controller(this, mutableListOf())
        c.onShared("   ")
        advanceUntilIdle()
        assertEquals(CheckState.Idle, c.state.value)
    }
}
