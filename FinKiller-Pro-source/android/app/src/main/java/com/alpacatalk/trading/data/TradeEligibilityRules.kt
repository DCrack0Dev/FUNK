package com.alpacatalk.trading.data

/** Shared pre-trade assessment used by scanner alerts, UI selection, and manual order gating. */
data class TradeEligibilityAssessment(
    val memoryDecision: TradeMemoryDecision,
    val adjustedScore: Int,
    val appRating: Int,
    val paperTestEligible: Boolean,
    val eligible: Boolean,
    val blockReasons: List<String> = emptyList(),
)

object TradeEligibilityRules {
    fun evaluate(
        setup: MarketSnapshot,
        environment: DirectEnvironment,
        journal: JournalSnapshot,
        minimumScore: Int,
    ): TradeEligibilityAssessment {
        val memory = TradeJournalEngine.decision(journal, setup.symbol, setup.setup)
        val adjustedScore = (setup.score + memory.scoreAdjustment).coerceIn(0, 100)
        val appRating = TradeJournalEngine.appRatingForTrade(setup.score, memory)
        val dataValid = setup.dataQualityPassed
        val rewardRiskValid = (setup.rewardRisk ?: 0.0) + 1e-9 >= TradeGeometry.MIN_SCAN_REWARD_RISK
        val paperTestEligible = environment == DirectEnvironment.PAPER && dataValid && rewardRiskValid &&
            setup.direction != null &&
            setup.historyBars >= MarketDataQuality.MIN_COMPLETED_BARS_PER_TIMEFRAME &&
            adjustedScore >= minimumScore &&
            appRating >= minimumScore &&
            !setup.setup.startsWith("DATA INCOMPLETE")
        val confirmedSetupEligible = dataValid && rewardRiskValid && setup.direction != null &&
            setup.isExecutableSetup &&
            adjustedScore >= minimumScore &&
            appRating >= minimumScore
        val eligible = environment == DirectEnvironment.PAPER && !setup.symbol.contains('/') && (confirmedSetupEligible || paperTestEligible)
        val reasons = buildList {
            if (environment == DirectEnvironment.LIVE) add("This build is paper-only; live execution is disabled.")
            if (!dataValid) add(setup.dataQualityReason)
            if (!rewardRiskValid) add("Reward/risk ${setup.rewardRisk?.let { "%.2f".format(java.util.Locale.US, it) } ?: "unknown"} is below the required 1:${"%.2f".format(java.util.Locale.US, TradeGeometry.MIN_SCAN_REWARD_RISK)} minimum.")
            if (setup.direction == null) add("Direction is missing or ambiguous.")
            if (adjustedScore < minimumScore) add("Adjusted scanner score $adjustedScore is below $minimumScore.")
            if (appRating < minimumScore) add("Blended app rating $appRating is below $minimumScore.")
            if (setup.symbol.contains('/')) add("This symbol is not supported by the equity order route.")
            if (!setup.isExecutableSetup && !paperTestEligible) add("Strategy checks are not confirmed.")
        }
        return TradeEligibilityAssessment(memory, adjustedScore, appRating, paperTestEligible, eligible, reasons)
    }
}
