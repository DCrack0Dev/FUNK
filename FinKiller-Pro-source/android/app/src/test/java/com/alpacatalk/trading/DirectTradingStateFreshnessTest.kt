package com.alpacatalk.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTradingStateFreshnessTest {
    @Test
    fun completedScanResultIsFreshForThreeMinutes() {
        val state = DirectTradingState(latestScanSymbols = setOf("AAPL"), latestScanAtEpochMs = 10_000L)
        assertTrue(state.hasFreshScanResult("aapl", nowEpochMs = 190_000L))
        assertFalse(state.hasFreshScanResult("AAPL", nowEpochMs = 190_001L))
    }

    @Test
    fun inProgressScanCannotBeUsedAsFreshResult() {
        val state = DirectTradingState(
            isScanning = true,
            latestScanSymbols = setOf("AAPL"),
            latestScanAtEpochMs = 10_000L,
        )
        assertFalse(state.hasFreshScanResult("AAPL", nowEpochMs = 20_000L))
    }
}
