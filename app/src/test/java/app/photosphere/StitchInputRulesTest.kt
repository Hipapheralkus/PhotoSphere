package app.photosphere

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StitchInputRulesTest {
    @Test fun latestAcceptedJournalEntryWinsAndLaterRejectedRetakeDoesNotReplaceIt() {
        // Journal order is reliable even if the device wall clock moved backwards between shots.
        val records = listOf(
            StitchRecordSelection(3, true), StitchRecordSelection(4, true),
            StitchRecordSelection(3, true), StitchRecordSelection(3, false),
        )
        assertEquals(mapOf(3 to 2, 4 to 1), StitchInputRules.latestAcceptedIndices(records, setOf(3, 4)))
    }

    @Test fun rejectedUnknownTargetsCannotBecomeStitchInputs() {
        assertEquals(mapOf(3 to 1), StitchInputRules.latestAcceptedIndices(
            listOf(StitchRecordSelection(-1, false), StitchRecordSelection(3, true)), setOf(3)))
    }

    @Test
    fun acceptedUnknownTargetFailsInsteadOfInventingCoverage() {
        assertThrows(IllegalArgumentException::class.java) {
            StitchInputRules.latestAcceptedIndices(listOf(StitchRecordSelection(8, true)), setOf(3))
        }
    }
}
