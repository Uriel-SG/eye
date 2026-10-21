package app.eye.android

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import app.eye.android.core.update.UpdateCardState
import app.eye.android.core.update.UpdateClient
import app.eye.android.core.update.UpdateController
import app.eye.android.core.update.UpdatePhase
import app.eye.android.core.update.UpdatePolicy
import app.eye.android.core.update.UpdateSnapshot
import app.eye.android.core.update.UpdateSnooze
import app.eye.android.core.update.UpdateSnoozeStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTest {
    private val day = 24L * 60 * 60 * 1000
    private val avail = UpdateSnapshot(UpdatePhase.AVAILABLE, 10)

    // --- logica pura
    @Test fun noUpdateOrErrorMeansNoCard() {
        assertEquals(UpdateCardState.Hidden, UpdatePolicy.decide(null, UpdateSnooze(), 0))
    }

    @Test fun availableShowsCardUnlessSnoozedForThatVersion() {
        val now = 1_000L
        assertEquals(UpdateCardState.Available(10), UpdatePolicy.decide(avail, UpdateSnooze(), now))
        val s = UpdatePolicy.snoozeFor(10, now)
        assertEquals(now + 3 * day, s.untilMs)
        assertEquals(UpdateCardState.Hidden, UpdatePolicy.decide(avail, s, now + 3 * day - 1))
        assertEquals(UpdateCardState.Available(10), UpdatePolicy.decide(avail, s, now + 3 * day)) // scaduto
        // una versione piu' nuova riporta la scheda anche dentro i 3 giorni
        assertEquals(UpdateCardState.Available(11), UpdatePolicy.decide(UpdateSnapshot(UpdatePhase.AVAILABLE, 11), s, now + day))
    }

    @Test fun downloadingAndReadyIgnoreSnooze() {
        val s = UpdatePolicy.snoozeFor(10, 0)
        assertEquals(UpdateCardState.Downloading, UpdatePolicy.decide(UpdateSnapshot(UpdatePhase.DOWNLOADING, 10), s, 1))
        assertEquals(UpdateCardState.Ready, UpdatePolicy.decide(UpdateSnapshot(UpdatePhase.DOWNLOADED, 10), s, 1))
        assertEquals(UpdateCardState.Ready, UpdatePolicy.decide(UpdateSnapshot(UpdatePhase.DOWNLOADED, 0), UpdateSnooze(), 1))
    }

    // --- controller con client e archivio finti
    private class FakeClient(val flowOf: Flow<UpdateSnapshot?>) : UpdateClient {
        var completed = 0
        var started = 0
        override fun snapshots() = flowOf
        override fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean { started++; return true }
        override fun complete() { completed++ }
    }

    private class FakeStore : UpdateSnoozeStore {
        val s = MutableStateFlow(UpdateSnooze())
        override val snooze: Flow<UpdateSnooze> = s
        override suspend fun set(s: UpdateSnooze) { this.s.value = s }
    }

    @Test fun controllerFollowsFlowAndSnoozes() = runTest(UnconfinedTestDispatcher()) {
        val snaps = MutableStateFlow<UpdateSnapshot?>(avail)
        var now = 5_000L
        val store = FakeStore()
        val c = UpdateController(FakeClient(snaps), store, backgroundScope, { now })
        backgroundScope.launch { c.state.collect { } }
        assertEquals(UpdateCardState.Available(10), c.state.value)
        c.later()
        assertEquals(UpdateCardState.Hidden, c.state.value)
        assertEquals(UpdateSnooze(10, 5_000 + 3 * day), store.s.value)
        snaps.value = UpdateSnapshot(UpdatePhase.DOWNLOADING, 10)
        assertEquals(UpdateCardState.Downloading, c.state.value)
        snaps.value = UpdateSnapshot(UpdatePhase.DOWNLOADED, 10)
        assertEquals(UpdateCardState.Ready, c.state.value)
        c.later() // solo dalla scheda «disponibile»: qui non cambia nulla
        assertEquals(UpdateSnooze(10, 5_000 + 3 * day), store.s.value)
        c.restart()
    }

    @Test fun errorsNeverCrashAndShowNothing() = runTest(UnconfinedTestDispatcher()) {
        // app non installata da Play (ERROR_APP_NOT_OWNED), Play assente, rete: il flusso lancia
        val c = UpdateController(FakeClient(flow { throw IllegalStateException("-10") }), FakeStore(), backgroundScope)
        backgroundScope.launch { c.state.collect { } }
        assertEquals(UpdateCardState.Hidden, c.state.value)
    }

    @Test fun clientFailuresInActionsAreSwallowed() = runTest(UnconfinedTestDispatcher()) {
        val boom = object : UpdateClient {
            override fun snapshots(): Flow<UpdateSnapshot?> = flow { emit(null) }
            override fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean = throw IllegalStateException("x")
            override fun complete() { throw IllegalStateException("y") }
        }
        val c = UpdateController(boom, FakeStore(), backgroundScope)
        val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
            override fun launch(input: IntentSenderRequest, options: androidx.core.app.ActivityOptionsCompat?) {}
            override fun unregister() {}
            override val contract: androidx.activity.result.contract.ActivityResultContract<IntentSenderRequest, *> get() = throw UnsupportedOperationException()
        }
        assertFalse(c.start(launcher))
        c.restart() // non lancia
    }
}
