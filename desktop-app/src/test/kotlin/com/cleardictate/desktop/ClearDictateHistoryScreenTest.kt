package com.cleardictate.desktop

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves that the History date filter uses the PC's local calendar day rather than the stored UTC day.
 */
class ClearDictateHistoryScreenTest
{
    @Test
    fun `date filter uses local capture date`()
    {
        val london = ZoneId.of("Europe/London")
        val lateUtcEntry = entry(1, "2026-08-10T23:30:00Z")
        val daytimeEntry = entry(2, "2026-08-10T12:00:00Z")

        assertEquals(listOf(lateUtcEntry), filterHistoryEntries(listOf(lateUtcEntry, daytimeEntry), LocalDate.parse("2026-08-11"), london))
        assertEquals(listOf(lateUtcEntry, daytimeEntry), filterHistoryEntries(listOf(lateUtcEntry, daytimeEntry), null, london))
    }

    private fun entry(identifier: Long, recordedAt: String): StoredDictationSummary
    {
        return StoredDictationSummary(
            identifier = identifier,
            recordedAt = Instant.parse(recordedAt),
            rawTranscript = "raw $identifier",
            polishedTranscript = "polished $identifier",
            timing = DesktopDictationTiming(0, 1, 2, 3)
        )
    }
}
