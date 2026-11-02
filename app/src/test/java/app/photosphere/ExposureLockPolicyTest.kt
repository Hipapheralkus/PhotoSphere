package app.photosphere

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExposureLockPolicyTest {
    @Test fun settlesBothChannelsAndRequiresActualResultConfirmation() {
        val state = ExposureLockPolicy(true, true, true)
        assertFalse(state.prepare(true, false))
        assertFalse(state.confirmed(false, false))
        assertTrue(state.prepare(true, true))
        assertFalse(state.confirmed(true, false))
        assertTrue(state.confirmed(true, true))
        state.resolve(true, true, true)
        assertTrue(state.retained)
        assertFalse(state.resolve(false, true, true))
        assertTrue(state.aeLock && state.awbLock)
    }

    @Test fun rejectionReleasesCandidateAndNextAttemptMustSettleAgain() {
        val state = ExposureLockPolicy(true, true, true)
        state.prepare(true, true)
        assertTrue(state.resolve(false, true, true))
        assertFalse(state.retained || state.aeLock || state.awbLock)
        assertFalse(state.prepare(false, true))
        assertTrue(state.prepare(true, true))
    }

    @Test fun unsupportedAndAutomaticModesNeverSetLocks() {
        val automatic = ExposureLockPolicy(false, true, true)
        val unsupported = ExposureLockPolicy(true, false, false)
        for (state in listOf(automatic, unsupported)) {
            assertFalse(state.prepare(true, true))
            assertTrue(state.confirmed(false, false))
            state.resolve(true, true, true)
            assertFalse(state.retained)
        }
        assertEquals("unsupported", unsupported.fallbackReason)
    }

    @Test fun timeoutKeepsOnlyConfirmedChannelsAndFreshCameraDoesNotReuseOldLock() {
        val state = ExposureLockPolicy(true, true, true)
        state.prepare(true, true)
        assertTrue(state.settleTimedOut(true, false))
        state.resolve(true, true, false)
        assertTrue(state.retained && state.aeLock)
        assertFalse(state.awbLock)
        assertEquals("settle_or_lock_timeout", state.fallbackReason)
        val reopened = ExposureLockPolicy(true, true, true)
        assertFalse(reopened.retained || reopened.aeLock || reopened.awbLock)
        assertFalse(reopened.confirmed(false, false))
    }
}
