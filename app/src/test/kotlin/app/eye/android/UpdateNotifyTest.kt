package app.eye.android

import app.eye.android.core.update.UpdateClient
import app.eye.android.core.update.UpdateNotifier
import app.eye.android.core.update.UpdateNotifyPolicy
import app.eye.android.core.update.UpdateNotifyState
import app.eye.android.core.update.UpdateNotifyStore
import app.eye.android.core.update.UpdatePhase
import app.eye.android.core.update.UpdatePolicy
import app.eye.android.core.update.UpdateSnapshot
import app.eye.android.core.update.UpdateSnooze
import app.eye.android.core.update.UpdateSnoozeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotifyTest {
    private val avail = UpdateSnapshot(UpdatePhase.AVAILABLE, 10)
    private val none = UpdateSnooze()
    private fun v(s: UpdateSnapshot? = avail, sn: UpdateSnooze = none, st: UpdateNotifyState = UpdateNotifyState(), can: Boolean = true, now: Long = 1000) =
        UpdateNotifyPolicy.versionToNotify(s, sn, st, can, now)

    @Test fun availableNotifiesOnce() = assertEquals(10, v())
    @Test fun notAvailable() = assertNull(v(s = null))
    @Test fun downloadingOrReadyNoNotification() {
        assertNull(v(s = UpdateSnapshot(UpdatePhase.DOWNLOADING, 10)))
        assertNull(v(s = UpdateSnapshot(UpdatePhase.DOWNLOADED, 10)))
    }
    @Test fun alreadyNotified() = assertNull(v(st = UpdateNotifyState(notifiedCode = 10)))
    @Test fun alreadySeenInCard() = assertNull(v(st = UpdateNotifyState(cardSeenCode = 10)))
    @Test fun newerVersionNotifiesAgain() = assertEquals(10, v(st = UpdateNotifyState(notifiedCode = 9, cardSeenCode = 9)))
    @Test fun permissionDenied() = assertNull(v(can = false))
    @Test fun snoozedVersionSilent() {
        val s = UpdatePolicy.snoozeFor(10, 1000)
        assertNull(v(sn = s, now = 2000))
        assertEquals(10, v(sn = s, now = 1000 + UpdatePolicy.SNOOZE_MS)) // scaduto
        assertEquals(10, v(sn = UpdatePolicy.snoozeFor(9, 1000), now = 2000)) // snooze di un'altra versione
    }

    private class Notify(var st: UpdateNotifyState = UpdateNotifyState()) : UpdateNotifyStore {
        override suspend fun state() = st
        override suspend fun setNotified(code: Int) { st = st.copy(notifiedCode = code) }
        override suspend fun setCardSeen(code: Int) { st = st.copy(cardSeenCode = code) }
    }
    private fun client(f: Flow<UpdateSnapshot?>) = object : UpdateClient {
        override fun snapshots() = f
        override fun start(launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) = false
        override fun complete() {}
    }
    private val snoozeStore = object : UpdateSnoozeStore {
        override val snooze = flowOf(UpdateSnooze())
        override suspend fun set(s: UpdateSnooze) {}
    }

    @Test fun notifierPostsOnceAndRemembers() = runTest {
        val store = Notify(); val posted = mutableListOf<Int>()
        val n = UpdateNotifier(client(flowOf(avail)), snoozeStore, store, { true }, { posted += it })
        assertTrue(n.check()); assertFalse(n.check())
        assertEquals(listOf(10), posted); assertEquals(10, store.st.notifiedCode)
    }

    @Test fun notifierNoPlayOrErrorIsSilent() = runTest {
        val posted = mutableListOf<Int>()
        val n = UpdateNotifier(client(flow { throw IllegalStateException("no Play") }), snoozeStore, Notify(), { true }, { posted += it })
        assertFalse(n.check()); assertTrue(posted.isEmpty())
    }

    @Test fun notifierNoPermissionDoesNotTouchPlay() = runTest {
        val posted = mutableListOf<Int>()
        val n = UpdateNotifier(client(flow { error("non dovrebbe essere chiamato") }), snoozeStore, Notify(), { false }, { posted += it })
        assertFalse(n.check()); assertTrue(posted.isEmpty())
    }
}
