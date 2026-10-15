package com.alpacatalk.trading.data

import kotlinx.serialization.Serializable

/** How a closed trade ended. */
@Serializable
enum class TradeOutcome {
    WIN,
    LOSS,
    BREAKEVEN,
    UNKNOWN,
}

/** How the app should treat a setup on the next attempt, based on its remembered record. */
@Serializable
enum class TradeDirective(val label: String) {
    PREFER("PREFER — remembered winner"),
    ALLOW("ALLOW — no negative memory"),
    CAUTION("CAUTION — reduced conviction"),
    PROBATION("PROBATION — auto trading paused for this setup"),
    BLOCK("BLOCK — remembered losing setup"),
    ;

    /** Higher means stricter; used to merge symbol-specific and cross-market memory. */
    val strictness: Int
        get() = when (this) {
            PREFER -> 0
            ALLOW -> 1
            CAUTION -> 2
            PROBATION -> 3
            BLOCK -> 4
        }

    val allowsAutomaticTrading: Boolean
        get() = this == PREFER || this == ALLOW
}

/** Which way the journal entry was captured. */
@Serializable
enum class TradeJournalSource {
    /** A bracket the app submitted and later reconciled against broker order state. */
    APP_BRACKET,

    /** Reconciled from the broker's own closed order history. */
    BROKER_SYNC,

    /** Typed in by the user. */
    MANUAL,
}

/**
 * A ticket for a trade the app has submitted but which has not been resolved yet.
 * It carries every value needed to journal the trade once the broker reports the exit, including the
 * rating the app gave the setup at entry. This is what makes the later lesson possible.
 */
@Serializable
data class OpenTradeTicket(
    val ticketId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val accountMode: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val quantity: Double,
    val setupLabel: String,
    val setupFamily: String,
    val memoryKey: String,
    val signalScoreAtEntry: Int,
    val appRatingAtEntry: Int,
    val directiveAtEntry: TradeDirective,
    val stopPips: Double,
    val targetPips: Double,
    val riskReward: Double,
    val entryType: String,
    val openedAtEpochMs: Long,
    val brokerOrderId: String? = null,
    val notes: String = "",
)

/**
 * One stored closed trade. Every field the user asked to keep is present: the setup the app rated,
 * the rating itself, the stop-loss distance in pips, the target in pips, and the realised pip result.
 */
@Serializable
data class ClosedTrade(
    val tradeId: String,
    val ticketId: String? = null,
    val brokerOrderId: String? = null,
    val symbol: String,
    val side: String,
    val accountMode: String,
    val setupLabel: String,
    val setupFamily: String,
    val memoryKey: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val stopPips: Double,
    val targetPips: Double,
    val resultPips: Double,
    val realizedPnl: Double,
    val riskReward: Double,
    val signalScoreAtEntry: Int,
    /** The rating the app itself gave this setup when it opened the trade. */
    val appRatingAtEntry: Int,
    val directiveAtEntry: TradeDirective,
    val outcome: TradeOutcome,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long,
    val entryType: String,
    val source: TradeJournalSource,
    /** Plain-language lesson derived from this result, e.g. a high-rated failure. */
    val lesson: String,
    val notes: String = "",
    /** True for typed/edited records; excluded from live-learning memory by policy. */
    val manuallyEdited: Boolean = false,
)

/**
 * The remembered record of one setup key. Ratings are always replayed from the stored trade list, so
 * this file never drifts: it is a pure function of the journal.
 */
@Serializable
data class SetupMemoryRecord(
    val key: String,
    val symbol: String,
    val setupFamily: String,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val breakevens: Int,
    /** Trades whose result could not be graded; stored but never counted toward the rating. */
    val ungraded: Int,
    val highRatedWins: Int,
    val highRatedLosses: Int,
    val weightedWins: Double,
    val weightedLosses: Double,
    val rating: Int,
    val previousRating: Int,
    val winRatePct: Int,
    val averageWinPips: Double,
    val averageLossPips: Double,
    val netPips: Double,
    val expectancyPips: Double,
    val consecutiveWins: Int,
    val consecutiveLosses: Int,
    val lastOutcome: TradeOutcome?,
    val lastClosedAtEpochMs: Long,
    val directive: TradeDirective,
    val reason: String,
    val scoreAdjustment: Int,
)

/** Persisted journal: every closed trade plus the still-open tickets waiting for a result. */
@Serializable
data class JournalState(
    val version: Int = 1,
    val trades: List<ClosedTrade> = emptyList(),
    val openTickets: List<OpenTradeTicket> = emptyList(),
)

/** Rating before and after one trade was applied to its setup memory. */
@Serializable
data class TradeRatingPoint(
    val tradeId: String,
    val memoryKey: String,
    val ratingBefore: Int,
    val ratingAfter: Int,
    val directiveAfter: TradeDirective,
    val delta: Int,
)

/** Aggregate numbers shown at the top of the journal screen. */
@Serializable
data class JournalStats(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val breakevens: Int,
    val winRatePct: Int,
    val grossWinPips: Double,
    val grossLossPips: Double,
    val netPips: Double,
    val averageWinPips: Double,
    val averageLossPips: Double,
    val expectancyPips: Double,
    val profitFactor: Double,
    val highRatedLosses: Int,
    val blockedSetups: Int,
    val cautionedSetups: Int,
    val preferredSetups: Int,
    val bestSetupKey: String?,
    val worstSetupKey: String?,
    val bestSymbol: String?,
    val worstSymbol: String?,
)

/** Everything the UI needs: trades, memories, per-trade rating movement, and totals. */
data class JournalSnapshot(
    val trades: List<ClosedTrade>,
    val openTickets: List<OpenTradeTicket>,
    val memories: List<SetupMemoryRecord>,
    val stats: JournalStats,
    val ratingPoints: List<TradeRatingPoint>,
    val generatedAtEpochMs: Long,
) {
    companion object {
        val EMPTY = JournalSnapshot(
            trades = emptyList(),
            openTickets = emptyList(),
            memories = emptyList(),
            ratingPoints = emptyList(),
            stats = JournalStats(
                totalTrades = 0,
                wins = 0,
                losses = 0,
                breakevens = 0,
                winRatePct = 0,
                grossWinPips = 0.0,
                grossLossPips = 0.0,
                netPips = 0.0,
                averageWinPips = 0.0,
                averageLossPips = 0.0,
                expectancyPips = 0.0,
                profitFactor = 0.0,
                highRatedLosses = 0,
                blockedSetups = 0,
                cautionedSetups = 0,
                preferredSetups = 0,
                bestSetupKey = null,
                worstSetupKey = null,
                bestSymbol = null,
                worstSymbol = null,
            ),
            generatedAtEpochMs = 0L,
        )
    }
}

/** The merged memory decision for one candidate symbol plus setup family. */
data class TradeMemoryDecision(
    val memoryKey: String,
    val symbolRecord: SetupMemoryRecord?,
    val familyRecord: SetupMemoryRecord?,
    val rating: Int,
    val eligibilityRating: Int = rating,
    val directive: TradeDirective,
    val scoreAdjustment: Int,
    val reason: String,
) {
    val allowsAutomaticTrading: Boolean get() = directive.allowsAutomaticTrading
    val isRemembered: Boolean get() = symbolRecord != null || familyRecord != null

    companion object {
        fun neutral(memoryKey: String): TradeMemoryDecision = TradeMemoryDecision(
            memoryKey = memoryKey,
            symbolRecord = null,
            familyRecord = null,
            rating = 50,
            directive = TradeDirective.ALLOW,
            scoreAdjustment = 0,
            reason = "No closed trades recorded yet for this setup.",
        )
    }
}

/** Result of reconciling broker order history against the app's open tickets. */
data class ReconciliationResult(
    val closed: List<ClosedTrade>,
    val stillOpen: List<OpenTradeTicket>,
    /** Tickets whose entry order was cancelled or expired without ever filling. */
    val cancelled: List<OpenTradeTicket>,
    val untouched: List<OpenTradeTicket>,
    val notes: List<String>,
)
/**
 * Input for a trade the user types into the journal by hand. Everything the memory needs is present,
 * including the rating the app had given the setup, so a historical high-rated loss still teaches it.
 */
data class ManualJournalEntry(
    val symbol: String,
    val side: String,
    val accountMode: String,
    val setupLabel: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val signalScore: Int,
    val appRating: Int? = null,
    val directiveAtEntry: TradeDirective? = null,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long,
    val outcomeOverride: TradeOutcome? = null,
    val notes: String = "",
)
