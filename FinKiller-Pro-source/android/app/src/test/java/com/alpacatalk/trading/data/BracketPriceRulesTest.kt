package com.alpacatalk.trading.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BracketPriceRulesTest {
    @Test
    fun standardTwoPercentStopKeepsFourPercentTargetForLongAndShort() {
        assertEquals(98.0 to 104.0, BracketPriceRules.levels(100.0, "buy", 0.02))
        assertEquals(102.0 to 96.0, BracketPriceRules.levels(100.0, "sell", 0.02))
    }

    @Test
    fun widenedEightPercentStopRetryAlsoKeepsTwoToOneRewardRisk() {
        assertEquals(92.0 to 116.0, BracketPriceRules.levels(100.0, "buy", 0.08))
        assertEquals(108.0 to 84.0, BracketPriceRules.levels(100.0, "sell", 0.08))
    }
}
