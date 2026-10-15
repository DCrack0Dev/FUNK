package com.alpacatalk.trading.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataQualityTest {
    private val now = 1_800_000_000_000L
    private val full = mapOf("1Min" to 30, "15Min" to 30, "1Hour" to 30, "1Day" to 30)

    @Test fun missingTimeframeAndUnresolvedErrorsFailClosed() {
        val missing = MarketDataQuality.assess(full - "1Day", emptyList(), now, 99.9, 100.1, now)
        assertFalse(missing.passed)
        assertTrue(missing.reasons.any { it.contains("1Day") })
        val errored = MarketDataQuality.assess(full, listOf("15Min timeout"), now, 99.9, 100.1, now)
        assertFalse(errored.passed)
    }

    @Test fun quoteMustBeFreshTwoSidedAndNarrow() {
        assertTrue(MarketDataQuality.assess(full, emptyList(), now, 99.9, 100.1, now).passed)
        assertFalse(MarketDataQuality.assess(full, emptyList(), now - 60_001, 99.9, 100.1, now).passed)
        assertFalse(MarketDataQuality.assess(full, emptyList(), null, 99.9, 100.1, now).passed)
        assertFalse(MarketDataQuality.assess(full, emptyList(), now, 99.0, 101.0, now).passed)
        assertFalse(MarketDataQuality.assess(full, emptyList(), now, 100.1, 100.0, now).passed)
    }

    @Test fun formingMinuteBarIsExcludedButClosedBarIsKept() {
        val currentMinuteStart = now / 60_000 * 60_000
        assertTrue(MarketDataQuality.dropFormingFinalBar(currentMinuteStart, "1Min", now))
        assertFalse(MarketDataQuality.dropFormingFinalBar(currentMinuteStart - 60_000, "1Min", now))
    }

    @Test fun regularSessionPolicyRejectsWeekendAndAfterHours() {
        // 2026-10-12 15:00 New York (Monday) is a regular session; the next sample is after close.
        val open = java.time.ZonedDateTime.of(2026, 10, 12, 15, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val after = java.time.ZonedDateTime.of(2026, 10, 12, 16, 1, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val weekend = java.time.ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertTrue(MarketDataQuality.regularSessionOpen(open))
        assertFalse(MarketDataQuality.regularSessionOpen(after))
        assertFalse(MarketDataQuality.regularSessionOpen(weekend))
    }

    @Test fun extendedSessionCoversAllWeekdayHoursFromFourToTwentyEastern() {
        val zone = java.time.ZoneId.of("America/New_York")
        fun at(hour: Int, minute: Int = 0) = java.time.ZonedDateTime.of(2026, 10, 12, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(MarketDataQuality.extendedSessionOpen(at(4)))
        assertTrue(MarketDataQuality.extendedSessionOpen(at(19, 59)))
        assertFalse(MarketDataQuality.extendedSessionOpen(at(3, 59)))
        assertFalse(MarketDataQuality.extendedSessionOpen(at(20)))
        assertFalse(MarketDataQuality.extendedSessionOpen(java.time.ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()))
    }
}
