package com.alpacatalk.trading

import com.alpacatalk.trading.data.MarketSnapshot
import com.alpacatalk.trading.data.ScannerResultMerger
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerResultMergerTest {
    @Test
    fun refreshReplacesScannedSymbolsButKeepsOtherCandidatesVisible() {
        val existing = listOf(candidate("AAPL", 86), candidate("MSFT", 83), candidate("NVDA", 80))
        val refreshed = listOf(candidate("aapl", 91))

        val merged = ScannerResultMerger.merge(existing, refreshed)

        assertEquals(setOf("AAPL", "MSFT", "NVDA"), merged.map { it.symbol.uppercase() }.toSet())
        assertEquals(91, merged.single { it.symbol.equals("AAPL", ignoreCase = true) }.score)
        assertEquals(91, merged.first().score)
    }

    @Test
    fun emptyRefreshDoesNotEraseLastVisibleCandidates() {
        val existing = listOf(candidate("AAPL", 86), candidate("MSFT", 83))
        assertEquals(existing, ScannerResultMerger.merge(existing, emptyList()))
    }

    private fun candidate(symbol: String, score: Int) = MarketSnapshot(
        symbol = symbol,
        lastPrice = 100.0,
        dayChangePct = 0.0,
        trendPct = 0.0,
        volatilityPct = 1.0,
        historyBars = 100,
        score = score,
        setup = "SCALP",
        isExecutableSetup = true,
        rationale = "test candidate",
        scalpScore = score,
        swingScore = score,
        scalpRationale = "scalp",
        swingRationale = "swing",
        executionGate = "eligible",
        timeframeDiagnostics = emptyList(),
    )
}
