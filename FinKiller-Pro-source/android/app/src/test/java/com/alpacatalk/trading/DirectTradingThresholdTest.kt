package com.alpacatalk.trading

import org.junit.Assert.assertEquals
import org.junit.Test
import com.alpacatalk.trading.data.TradeJournalEngine
import com.alpacatalk.trading.data.TradeMemoryDecision

class DirectTradingThresholdTest {
    @Test
    fun executionMinimumIs72ForPaperAndLiveDefaults() {
        assertEquals(72, DirectTradingState().minimumSignalScore)
    }

    @Test
    fun scannerScore85WithNeutralMemoryStillRoundsTo75() {
        val neutral = TradeMemoryDecision.neutral("AAPL|SCALP")
        assertEquals(75, TradeJournalEngine.appRatingForTrade(85, neutral))
    }

    @Test
    fun scannerScore81WithNeutralMemoryRoundsToNew72PointMinimum() {
        val neutral = TradeMemoryDecision.neutral("AAPL|SCALP")
        assertEquals(72, TradeJournalEngine.appRatingForTrade(81, neutral))
    }

    @Test
    fun scannerScore80WithNeutralMemoryRemainsBelowNewExecutionMinimum() {
        val neutral = TradeMemoryDecision.neutral("AAPL|SCALP")
        assertEquals(71, TradeJournalEngine.appRatingForTrade(80, neutral))
    }
}
