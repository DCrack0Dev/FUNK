package com.alpacatalk.trading

import com.alpacatalk.trading.data.DirectEnvironment
import com.alpacatalk.trading.data.JournalSnapshot
import com.alpacatalk.trading.data.MarketSnapshot
import com.alpacatalk.trading.data.TradeEligibilityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeEligibilityRulesTest {
    @Test
    fun paperTestSetupAt72AppRatingQualifies() {
        val assessment = TradeEligibilityRules.evaluate(candidate(score = 81, executable = false), DirectEnvironment.PAPER, JournalSnapshot.EMPTY, 72)
        assertEquals(72, assessment.appRating)
        assertTrue(assessment.paperTestEligible)
        assertTrue(assessment.eligible)
    }

    @Test
    fun ratingBelow72DoesNotQualifyEvenWhenRawScannerScorePasses() {
        val assessment = TradeEligibilityRules.evaluate(candidate(score = 80, executable = true), DirectEnvironment.LIVE, JournalSnapshot.EMPTY, 72)
        assertEquals(71, assessment.appRating)
        assertFalse(assessment.eligible)
    }

    @Test
    fun liveExecutionIsDisabledEvenForConfirmedHighScoringSetup() {
        val assessment = TradeEligibilityRules.evaluate(candidate(score = 99, executable = true), DirectEnvironment.LIVE, JournalSnapshot.EMPTY, 72)
        assertFalse(assessment.eligible)
        assertTrue(assessment.blockReasons.any { it.contains("paper-only") })
    }

    @Test
    fun ambiguousLongShortDirectionCannotPassPaperOrLiveGates() {
        val ambiguous = candidate(score = 99, executable = true).copy(direction = null)
        assertFalse(TradeEligibilityRules.evaluate(ambiguous, DirectEnvironment.PAPER, JournalSnapshot.EMPTY, 72).eligible)
        assertFalse(TradeEligibilityRules.evaluate(ambiguous, DirectEnvironment.LIVE, JournalSnapshot.EMPTY, 72).eligible)
    }

    @Test
    fun missingDataQualityBlocksEvenHighPaperScore() {
        val assessment = TradeEligibilityRules.evaluate(candidate(score = 99, executable = true).copy(dataQualityPassed = false), DirectEnvironment.PAPER, JournalSnapshot.EMPTY, 72)
        assertFalse(assessment.eligible)
        assertTrue(assessment.blockReasons.any { it.contains("quality") || it.contains("verified") })
    }

    @Test
    fun scannerRewardRiskBoundaryOfOnePointFivePasses() {
        assertTrue(TradeEligibilityRules.evaluate(candidate(81, false).copy(rewardRisk = 1.5), DirectEnvironment.PAPER, JournalSnapshot.EMPTY, 72).eligible)
        val below = TradeEligibilityRules.evaluate(candidate(81, false).copy(rewardRisk = 1.49), DirectEnvironment.PAPER, JournalSnapshot.EMPTY, 72)
        assertFalse(below.eligible)
        assertTrue(below.blockReasons.any { it.contains("1:1.50") })
    }

    private fun candidate(score: Int, executable: Boolean) = MarketSnapshot(
        symbol = "AAPL",
        lastPrice = 100.0,
        dayChangePct = 0.0,
        trendPct = 0.0,
        volatilityPct = 1.0,
        historyBars = 100,
        score = score,
        setup = "SCALP",
        isExecutableSetup = executable,
        rationale = "test candidate",
        scalpScore = score,
        swingScore = score,
        scalpRationale = "scalp",
        swingRationale = "swing",
        executionGate = "test",
        timeframeDiagnostics = emptyList(),
        dataQualityPassed = true,
        dataQualityReason = "fixture data verified",
        rewardRisk = 5.0,
    )
}
