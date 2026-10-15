package com.alpacatalk.trading

import com.alpacatalk.trading.data.BrokerOrderRecord
import com.alpacatalk.trading.data.ClosedTrade
import com.alpacatalk.trading.data.ClosedTradeReconciler
import com.alpacatalk.trading.data.JournalState
import com.alpacatalk.trading.data.TradeDirective
import com.alpacatalk.trading.data.TradeJournalEngine
import com.alpacatalk.trading.data.TradeJournalMigration
import com.alpacatalk.trading.data.TradeJournalSource
import com.alpacatalk.trading.data.TradeMath
import com.alpacatalk.trading.data.TradeOutcome
import com.alpacatalk.trading.data.TradeTicketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the trade journal, the pip math, and the setup memory.
 * They pin the behaviour the user asked for: every closed trade is stored with its stop and result in
 * pips, and a setup the app rated highly that still lost is punished hardest so it is not repeated.
 */
class TradeJournalEngineTest {

    private val scalpLabel = "SCALP / MOMENTUM BREAKOUT OR LIQUIDITY SWEEP"
    private val swingLabel = "SWING / LIQUIDITY-TO-LIQUIDITY SETUP"

    private fun ticket(
        symbol: String = "AAPL",
        side: String = "buy",
        entryPrice: Double = 100.0,
        stopPrice: Double = 98.0,
        targetPrice: Double = 104.0,
        quantity: Double = 1.0,
        appRating: Int = 50,
        label: String = scalpLabel,
        signalScore: Int = 70,
        openedAtEpochMs: Long = 1_000L,
        clientOrderId: String = "fk-test",
    ) = TradeTicketFactory.ticket(
        symbol = symbol,
        side = side,
        accountMode = "PAPER",
        entryPrice = entryPrice,
        stopPrice = stopPrice,
        targetPrice = targetPrice,
        quantity = quantity,
        setupLabel = label,
        signalScoreAtEntry = signalScore,
        appRatingAtEntry = appRating,
        directiveAtEntry = TradeDirective.ALLOW,
        entryType = "market",
        openedAtEpochMs = openedAtEpochMs,
        clientOrderId = clientOrderId,
    )

    private fun trade(
        tradeId: String,
        outcome: TradeOutcome,
        appRating: Int,
        symbol: String = "AAPL",
        label: String = scalpLabel,
        closedAtEpochMs: Long = 2_000L,
    ): ClosedTrade {
        val entry = 100.0
        val stop = 98.0
        val target = 104.0
        val exit = when (outcome) {
            TradeOutcome.WIN -> target
            TradeOutcome.LOSS -> stop
            TradeOutcome.BREAKEVEN -> entry
            TradeOutcome.UNKNOWN -> 0.0
        }
        return TradeTicketFactory.close(
            ticket = ticket(
                symbol = symbol,
                entryPrice = entry,
                stopPrice = stop,
                targetPrice = target,
                appRating = appRating,
                label = label,
                openedAtEpochMs = closedAtEpochMs - 1_000L,
                clientOrderId = tradeId,
            ),
            exitPrice = exit,
            closedAtEpochMs = closedAtEpochMs,
            source = TradeJournalSource.MANUAL,
            tradeIdOverride = tradeId,
        )
    }

    private fun snapshotOf(vararg trades: ClosedTrade) =
        TradeJournalEngine.rebuild(JournalState(trades = trades.toList()))

    // ---------------------------------------------------------------- pip and instrument math

    @Test
    fun pipSizeFollowsTheInstrument() {
        assertEquals(0.01, TradeMath.pipSize("AAPL"), 1e-9)
        assertEquals(0.01, TradeMath.pipSize("SPY"), 1e-9)
        assertEquals(0.0001, TradeMath.pipSize("EUR/USD"), 1e-9)
        assertEquals(0.01, TradeMath.pipSize("USD/JPY"), 1e-9)
        assertEquals(0.1, TradeMath.pipSize("XAU/USD"), 1e-9)
    }

    @Test
    fun stopAndTargetAreStoredInPips() {
        val ticket = ticket(entryPrice = 100.0, stopPrice = 98.0, targetPrice = 104.0)
        assertEquals(200.0, ticket.stopPips, 1e-9)
        assertEquals(400.0, ticket.targetPips, 1e-9)
        assertEquals(2.0, ticket.riskReward, 1e-9)
        assertEquals("ticks", TradeMath.pipLabel(ticket.symbol))
    }

    @Test
    fun forexPipsUseForexPipSize() {
        val ticket = ticket(symbol = "EUR/USD", entryPrice = 1.1000, stopPrice = 1.0980, targetPrice = 1.1050)
        assertEquals(20.0, ticket.stopPips, 1e-9)
        assertEquals(50.0, ticket.targetPips, 1e-9)
        assertEquals("pips", TradeMath.pipLabel(ticket.symbol))
    }

    @Test
    fun outcomeIsDecidedByStopAndTarget() {
        assertEquals(TradeOutcome.WIN, TradeMath.resolveOutcome("AAPL", "buy", 100.0, 98.0, 104.0, 104.0))
        assertEquals(TradeOutcome.LOSS, TradeMath.resolveOutcome("AAPL", "buy", 100.0, 98.0, 104.0, 98.0))
        assertEquals(TradeOutcome.WIN, TradeMath.resolveOutcome("EUR/USD", "sell", 1.1000, 1.1020, 1.0950, 1.0950))
        assertEquals(TradeOutcome.LOSS, TradeMath.resolveOutcome("EUR/USD", "sell", 1.1000, 1.1020, 1.0950, 1.1020))
        // A manual close between the two levels is graded by the sign of the realised move.
        assertEquals(TradeOutcome.WIN, TradeMath.resolveOutcome("AAPL", "buy", 100.0, 98.0, 104.0, 101.0))
        assertEquals(TradeOutcome.LOSS, TradeMath.resolveOutcome("AAPL", "buy", 100.0, 98.0, 104.0, 99.0))
        assertEquals(TradeOutcome.BREAKEVEN, TradeMath.resolveOutcome("AAPL", "buy", 100.0, 98.0, 104.0, 100.0))
        assertEquals(TradeOutcome.UNKNOWN, TradeMath.resolveOutcome("AAPL", "buy", 100.0, 98.0, 104.0, 0.0))
    }

    @Test
    fun resultPipsAreSignedByDirection() {
        assertEquals(400.0, TradeMath.resultPips("AAPL", "buy", 100.0, 104.0), 1e-9)
        assertEquals(-200.0, TradeMath.resultPips("AAPL", "buy", 100.0, 98.0), 1e-9)
        assertEquals(200.0, TradeMath.resultPips("AAPL", "sell", 100.0, 98.0), 1e-9)
        assertEquals(50.0, TradeMath.resultPips("EUR/USD", "buy", 1.1000, 1.1050), 1e-9)
    }

    @Test
    fun csvExportContainsTradeAndMemoryFieldsAndQuotesNotes() {
        val stored = trade("csv-trade", TradeOutcome.LOSS, appRating = 90).copy(notes = "stop hit, reviewed\nsecond line")
        val csv = TradeJournalEngine.csv(listOf(stored))
        assertTrue(csv.startsWith("trade_id,ticket_id,closed_at_utc"))
        assertTrue(csv.contains("memory_rating_before,memory_rating_after,directive_after"))
        assertTrue(csv.contains("stop_pips,target_pips,risk_reward,result_pips"))
        assertTrue(csv.contains("\"stop hit, reviewed\nsecond line\""))
    }

    // ---------------------------------------------------------------- rating and memory

    @Test
    fun coldSetupStartsNeutralAndOneLossForcesCaution() {
        val empty = TradeJournalEngine.rebuild(JournalState())
        assertTrue(empty.memories.isEmpty())
        val decision = TradeJournalEngine.decision(empty, "AAPL", scalpLabel)
        assertEquals(TradeJournalEngine.NEUTRAL_RATING, decision.rating)
        assertEquals(TradeDirective.ALLOW, decision.directive)
        assertFalse(decision.isRemembered)

        val snapshot = snapshotOf(trade("t1", TradeOutcome.LOSS, appRating = 50))
        val memory = snapshot.memories.first { it.key == "AAPL|SCALP" }
        assertEquals(1, memory.losses)
        assertEquals(33, memory.rating)
        assertEquals(TradeDirective.CAUTION, memory.directive)
        assertEquals(TradeJournalEngine.SCORE_ADJUSTMENT_CAUTION, memory.scoreAdjustment)
    }

    @Test
    fun aHighRatedLossIsPunishedHarderThanALowRatedOne() {
        val confident = snapshotOf(trade("t1", TradeOutcome.LOSS, appRating = 90)).memories.first { it.key == "AAPL|SCALP" }
        val doubtful = snapshotOf(trade("t1", TradeOutcome.LOSS, appRating = 20)).memories.first { it.key == "AAPL|SCALP" }
        assertTrue("a loss on a highly rated setup must cost more rating", confident.rating < doubtful.rating)
        assertEquals(29, confident.rating)
        assertEquals(37, doubtful.rating)
        assertEquals(1, confident.highRatedLosses)
        assertEquals(0, doubtful.highRatedLosses)
    }

    @Test
    fun repeatedHighRatedLossesEscalateToProbationAndThenBlock() {
        val first = snapshotOf(trade("t1", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 1_000L))
        assertEquals(TradeDirective.CAUTION, first.memories.first { it.key == "AAPL|SCALP" }.directive)

        val second = snapshotOf(
            trade("t1", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 2_000L),
        )
        val secondMemory = second.memories.first { it.key == "AAPL|SCALP" }
        assertEquals(TradeDirective.PROBATION, secondMemory.directive)
        assertEquals(2, secondMemory.highRatedLosses)

        val third = snapshotOf(
            trade("t1", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 2_000L),
            trade("t3", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 3_000L),
        )
        val thirdMemory = third.memories.first { it.key == "AAPL|SCALP" }
        assertEquals(TradeDirective.BLOCK, thirdMemory.directive)
        assertEquals(TradeJournalEngine.SCORE_ADJUSTMENT_BLOCK, thirdMemory.scoreAdjustment)

        val decision = TradeJournalEngine.decision(third, "AAPL", scalpLabel)
        assertEquals(TradeDirective.BLOCK, decision.directive)
        assertFalse("a blocked setup must not be traded automatically", decision.allowsAutomaticTrading)
    }

    @Test
    fun smallWinningSamplesDoNotEarnPreferenceOrRaiseEligibility() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 2_000L),
            trade("t3", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 3_000L),
            trade("t4", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 4_000L),
        )
        val memory = snapshot.memories.first { it.key == "AAPL|SCALP" }
        assertEquals(4, memory.wins)
        // Four wins the app expected at 90/100 conviction: 4 x (0.5 + 0.5 * 0.9) of weight.
        assertEquals(82, memory.rating)
        assertEquals(TradeDirective.ALLOW, memory.directive)
        assertEquals(0, memory.scoreAdjustment)
        val decision = TradeJournalEngine.decision(snapshot, "AAPL", scalpLabel)
        assertEquals(TradeDirective.ALLOW, decision.directive)
        assertEquals(50, decision.eligibilityRating)
        assertEquals(71, TradeJournalEngine.appRatingForTrade(80, decision))
        assertTrue(decision.allowsAutomaticTrading)
    }

    @Test
    fun twentyPositiveSamplesCanEarnPreference() {
        val snapshot = snapshotOf(*List(20) { index -> trade("w$index", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 1_000L + index) }.toTypedArray())
        val decision = TradeJournalEngine.decision(snapshot, "AAPL", scalpLabel)
        assertEquals(20, decision.symbolRecord?.trades)
        assertEquals(TradeDirective.PREFER, decision.directive)
        assertEquals(decision.rating, decision.eligibilityRating)
        assertEquals(TradeJournalEngine.SCORE_ADJUSTMENT_PREFER, decision.scoreAdjustment)
    }

    @Test
    fun aLuckyLowRatedWinCountsLessThanAnExpectedWin() {
        val expected = snapshotOf(trade("t1", TradeOutcome.WIN, appRating = 90)).memories.first { it.key == "AAPL|SCALP" }
        val lucky = snapshotOf(trade("t1", TradeOutcome.WIN, appRating = 20)).memories.first { it.key == "AAPL|SCALP" }
        assertTrue(expected.rating > lucky.rating)
    }

    @Test
    fun aSetupThatKeepsWinningEarnsItsWayBackOutOfProbation() {
        val blocked = mutableListOf(
            trade("t1", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 2_000L),
            trade("t3", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 3_000L),
        )
        val blockedSnapshot = snapshotOf(*blocked.toTypedArray())
        assertEquals(TradeDirective.BLOCK, blockedSnapshot.memories.first { it.key == "AAPL|SCALP" }.directive)
        assertFalse(TradeJournalEngine.decision(blockedSnapshot, "AAPL", scalpLabel).allowsAutomaticTrading)

        repeat(6) { index ->
            blocked += trade("w$index", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 10_000L + index)
        }
        val recovered = snapshotOf(*blocked.toTypedArray())
        val memory = recovered.memories.first { it.key == "AAPL|SCALP" }
        assertTrue("rating must recover as the setup keeps winning", memory.rating > 50)
        assertEquals(TradeDirective.ALLOW, memory.directive)
        assertTrue(TradeJournalEngine.decision(recovered, "AAPL", scalpLabel).allowsAutomaticTrading)
    }

    @Test
    fun breakevenAndUngradedResultsDoNotMoveTheRating() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.BREAKEVEN, appRating = 80),
            trade("t2", TradeOutcome.UNKNOWN, appRating = 80),
        )
        val memory = snapshot.memories.first { it.key == "AAPL|SCALP" }
        assertEquals(TradeJournalEngine.NEUTRAL_RATING, memory.rating)
        assertEquals(1, memory.breakevens)
        assertEquals(1, memory.ungraded)
        assertEquals(2, memory.trades)
    }

    @Test
    fun memoryIsReplayedFromTheJournalAndRewindsWhenATradeIsDeleted() {
        val state = JournalState(
            trades = listOf(
                trade("t1", TradeOutcome.WIN, appRating = 90, closedAtEpochMs = 1_000L),
                trade("t2", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 2_000L),
                trade("t3", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 3_000L),
            ),
        )
        val first = TradeJournalEngine.rebuild(state)
        val second = TradeJournalEngine.rebuild(state)
        assertEquals(first.memories.first { it.key == "AAPL|SCALP" }.rating, second.memories.first { it.key == "AAPL|SCALP" }.rating)
        assertEquals(33, first.memories.first { it.key == "AAPL|SCALP" }.rating)

        val rewound = TradeJournalEngine.rebuild(TradeJournalEngine.removeTrade(state, "t3"))
        val rewoundMemory = rewound.memories.first { it.key == "AAPL|SCALP" }
        assertTrue(rewoundMemory.rating > first.memories.first { it.key == "AAPL|SCALP" }.rating)
        assertEquals(1, rewoundMemory.losses)
    }

    @Test
    fun perTradeRatingMovementIsRecorded() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.LOSS, appRating = 90, closedAtEpochMs = 2_000L),
        )
        val points = snapshot.ratingPoints.filter { it.memoryKey == "AAPL|SCALP" }.sortedBy { it.tradeId }
        assertEquals(2, points.size)
        assertEquals(TradeJournalEngine.NEUTRAL_RATING, points[0].ratingBefore)
        assertEquals(29, points[0].ratingAfter)
        assertEquals(29, points[1].ratingBefore)
        assertEquals(20, points[1].ratingAfter)
        assertEquals(-9, points[1].delta)
        assertEquals(TradeDirective.PROBATION, points[1].directiveAfter)
    }

    @Test
    fun familyMemoryCarriesAcrossMarkets() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.LOSS, appRating = 90, symbol = "AAPL", closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.LOSS, appRating = 90, symbol = "AAPL", closedAtEpochMs = 2_000L),
            trade("t3", TradeOutcome.LOSS, appRating = 90, symbol = "AAPL", closedAtEpochMs = 3_000L),
        )
        val family = snapshot.memories.first { it.key == "*|SCALP" }
        assertEquals(3, family.losses)
        assertEquals(3, family.highRatedLosses)
        val otherSymbol = TradeJournalEngine.decision(snapshot, "MSFT", scalpLabel)
        assertNotNull(otherSymbol.familyRecord)
        assertTrue(otherSymbol.directive.strictness >= TradeDirective.CAUTION.strictness)
        assertFalse(otherSymbol.allowsAutomaticTrading)
    }

    @Test
    fun decisionMergesSymbolAndFamilyMemoryAtTheStricterDirective() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.WIN, appRating = 90, symbol = "AAPL", closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.WIN, appRating = 90, symbol = "AAPL", closedAtEpochMs = 2_000L),
            trade("t3", TradeOutcome.WIN, appRating = 90, symbol = "AAPL", closedAtEpochMs = 3_000L),
            trade("t4", TradeOutcome.WIN, appRating = 90, symbol = "AAPL", closedAtEpochMs = 4_000L),
            trade("t5", TradeOutcome.LOSS, appRating = 60, symbol = "MSFT", closedAtEpochMs = 5_000L),
            trade("t6", TradeOutcome.LOSS, appRating = 60, symbol = "MSFT", closedAtEpochMs = 6_000L),
        )
        val aapl = TradeJournalEngine.decision(snapshot, "AAPL", scalpLabel)
        assertEquals(TradeDirective.ALLOW, aapl.symbolRecord?.directive)
        assertEquals(TradeDirective.CAUTION, aapl.familyRecord?.directive)
        assertEquals(60, aapl.familyRecord?.rating)
        assertEquals("the stricter of the two memories wins", TradeDirective.CAUTION, aapl.directive)
        assertEquals(TradeJournalEngine.SCORE_ADJUSTMENT_CAUTION, aapl.scoreAdjustment)
    }

    @Test
    fun appRatingBlendsSignalConvictionWithRememberedMemory() {
        val empty = TradeJournalEngine.rebuild(JournalState())
        val neutral = TradeJournalEngine.decision(empty, "AAPL", scalpLabel)
        assertEquals(71, TradeJournalEngine.appRatingForTrade(80, neutral))
        val punished = TradeJournalEngine.decision(snapshotOf(trade("t1", TradeOutcome.LOSS, appRating = 90)), "AAPL", scalpLabel)
        assertTrue(TradeJournalEngine.appRatingForTrade(80, punished) < 71)
    }

    @Test
    fun swingAndScalpSetupsAreLearnedSeparately() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.LOSS, appRating = 90, label = swingLabel, closedAtEpochMs = 1_000L),
        )
        assertEquals(null, snapshot.memories.firstOrNull { it.key == "AAPL|SCALP" })
        assertNotNull(snapshot.memories.firstOrNull { it.key == "AAPL|SWING" })
    }

    @Test
    fun setupFamiliesAreDerivedFromTheScannerLabels() {
        assertEquals("SCALP", TradeJournalEngine.familyOf(scalpLabel))
        assertEquals("SWING", TradeJournalEngine.familyOf(swingLabel))
        assertEquals("SCALP+SWING", TradeJournalEngine.familyOf("SCALP + SWING / ALIGNED LONG SETUP"))
        assertEquals("DEVELOPING", TradeJournalEngine.familyOf("DEVELOPING SETUP"))
        assertEquals("OTHER", TradeJournalEngine.familyOf("OANDA GOLD MARKET"))
    }

    // ---------------------------------------------------------------- journalling and reconciliation

    @Test
    fun recordingATradeStoresEveryFieldAndClearsItsTicket() {
        val open = ticket(appRating = 85, signalScore = 78)
        var state = TradeJournalEngine.recordTickets(JournalState(), listOf(open))
        assertEquals(1, state.openTickets.size)

        val closed = TradeTicketFactory.close(open, exitPrice = 98.0, closedAtEpochMs = 5_000L, source = TradeJournalSource.APP_BRACKET, tradeIdOverride = "broker-1")
        state = TradeJournalEngine.recordTrade(state, closed)
        assertEquals(1, state.trades.size)
        assertTrue(state.openTickets.isEmpty())

        val stored = state.trades.first()
        assertEquals("AAPL", stored.symbol)
        assertEquals(200.0, stored.stopPips, 1e-9)
        assertEquals(400.0, stored.targetPips, 1e-9)
        assertEquals(-200.0, stored.resultPips, 1e-9)
        assertEquals(TradeOutcome.LOSS, stored.outcome)
        assertEquals(85, stored.appRatingAtEntry)
        assertEquals(78, stored.signalScoreAtEntry)
        assertTrue(stored.lesson.contains("HIGH-RATED LOSS"))

        val again = TradeJournalEngine.recordTrade(state, closed)
        assertEquals(1, again.trades.size)
    }

    @Test
    fun reconcilerGradesAFilledStopLegAsALoss() {
        val open = ticket(appRating = 85, clientOrderId = "fk-abc", openedAtEpochMs = 1_000_000L)
        val parent = BrokerOrderRecord(
            orderId = "o1",
            clientOrderId = "fk-abc",
            symbol = "AAPL",
            side = "buy",
            type = "market",
            orderClass = "bracket",
            status = "filled",
            filledQuantity = 1.0,
            filledAveragePrice = 100.0,
            submittedAtEpochMs = 1_000_000L,
            filledAtEpochMs = 1_000_500L,
            legs = listOf(
                BrokerOrderRecord("l1", null, "AAPL", "sell", "stop", "simple", "filled", 1.0, 98.0, 1_000_000L, 2_000_000L),
                BrokerOrderRecord("l2", null, "AAPL", "sell", "limit", "simple", "canceled", 0.0, 0.0, 1_000_000L, 0L),
            ),
        )
        val result = ClosedTradeReconciler.reconcile(listOf(open), listOf(parent))
        assertEquals(1, result.closed.size)
        assertTrue(result.stillOpen.isEmpty())
        val trade = result.closed.first()
        assertEquals(TradeOutcome.LOSS, trade.outcome)
        assertEquals(-200.0, trade.resultPips, 1e-9)
        assertEquals(2_000_000L, trade.closedAtEpochMs)
        assertEquals("broker-o1", trade.tradeId)
        assertEquals(85, trade.appRatingAtEntry)
    }

    @Test
    fun reconcilerGradesAFilledTargetLegAsAWin() {
        val open = ticket(appRating = 70, clientOrderId = "fk-win", openedAtEpochMs = 1_000_000L)
        val parent = BrokerOrderRecord(
            orderId = "o2",
            clientOrderId = "fk-win",
            symbol = "AAPL",
            side = "buy",
            type = "market",
            orderClass = "bracket",
            status = "filled",
            filledQuantity = 1.0,
            filledAveragePrice = 100.0,
            submittedAtEpochMs = 1_000_000L,
            filledAtEpochMs = 1_000_500L,
            legs = listOf(
                BrokerOrderRecord("l1", null, "AAPL", "sell", "stop", "simple", "canceled", 0.0, 0.0, 1_000_000L, 0L),
                BrokerOrderRecord("l2", null, "AAPL", "sell", "limit", "simple", "filled", 1.0, 104.0, 1_000_000L, 3_000_000L),
            ),
        )
        val trade = ClosedTradeReconciler.reconcile(listOf(open), listOf(parent)).closed.single()
        assertEquals(TradeOutcome.WIN, trade.outcome)
        assertEquals(400.0, trade.resultPips, 1e-9)
        assertTrue(trade.lesson.contains("Target reached"))
    }

    @Test
    fun reconcilerKeepsTicketOpenWhenExitLegOnlyPartiallyFilled() {
        val open = ticket(clientOrderId = "fk-partial", openedAtEpochMs = 1_000_000L)
        val parent = BrokerOrderRecord(
            orderId = "o-partial",
            clientOrderId = "fk-partial",
            symbol = "AAPL",
            side = "buy",
            type = "market",
            orderClass = "bracket",
            status = "filled",
            filledQuantity = 1.0,
            filledAveragePrice = 100.0,
            submittedAtEpochMs = 1_000_000L,
            filledAtEpochMs = 1_000_500L,
            legs = listOf(
                BrokerOrderRecord("l-partial", null, "AAPL", "sell", "stop", "simple", "partially_filled", 0.5, 98.0, 1_000_000L, 2_000_000L),
            ),
        )
        val result = ClosedTradeReconciler.reconcile(listOf(open), listOf(parent))
        assertTrue(result.closed.isEmpty())
        assertEquals(1, result.stillOpen.size)
        assertTrue(result.notes.single().contains("partially filled"))
    }

    @Test
    fun brokerFillJournalReducerStoresOnlyVerifiedFullCloseAtWeightedActualExitPrice() {
        val open = ticket(clientOrderId = "fk-verified").copy(brokerOrderId = "entry-order")
        val state = JournalState(openTickets = listOf(open))
        val entry = BrokerOrderRecord("entry-order", "fk-verified", "AAPL", "buy", "market", "bracket", "filled", 1.0, 100.0, 1_000L, 2_000L)
        val exit = BrokerOrderRecord("exit-order", null, "AAPL", "sell", "market", "simple", "filled", 1.0, 103.2, 3_000L, 4_000L)
        val closeFills = listOf(
            com.alpacatalk.trading.ClosedTrade("AAPL", "LONG", 0.4, 100.0, 102.0, 0.8, "2026-10-12T10:00:00Z", "exit-order", "entry-order"),
            com.alpacatalk.trading.ClosedTrade("AAPL", "LONG", 0.6, 100.0, 104.0, 2.4, "2026-10-12T10:00:01Z", "exit-order", "entry-order"),
        )

        val (updated, closed) = BrokerFillJournalReconciler.storeVerifiedTicketClosures(
            state, closeFills, listOf(entry, exit), setOf("entry-order", "exit-order"),
        )

        assertEquals(1, closed.size)
        assertEquals(0, updated.openTickets.size)
        assertEquals(1, updated.trades.size)
        assertEquals(103.2, updated.trades.single().exitPrice, 1e-9)
        assertEquals(3.2, updated.trades.single().realizedPnl, 1e-9)
        assertEquals(TradeJournalSource.BROKER_SYNC, updated.trades.single().source)
    }

    @Test
    fun brokerFillDerivationMatchesRecentExitToVerifiedAppEntryOrder() {
        val open = ticket(clientOrderId = "fk-recent-exit").copy(brokerOrderId = "entry-old")
        val entry = BrokerOrderRecord("entry-old", "fk-recent-exit", "AAPL", "buy", "market", "bracket", "filled", 1.0, 100.25, 1_000L, 2_000L)
        val exit = BrokerOrderRecord("exit-new", null, "AAPL", "sell", "market", "simple", "filled", 1.0, 103.25, 3_000L, 4_000L)
        val exitActivity = Json.parseToJsonElement(
            """{"symbol":"AAPL","side":"sell","qty":"1","price":"103.25","order_id":"exit-new","transaction_time":"2026-10-12T10:00:00Z"}""",
        ).jsonObject

        val derived = BrokerFillJournalReconciler.deriveClosedTrades(
            activities = listOf(exitActivity),
            filledOrderIds = setOf("entry-old", "exit-new"),
            journalTickets = listOf(open),
            brokerOrders = listOf(entry, exit),
        )

        assertEquals(1, derived.size)
        assertEquals("entry-old", derived.single().entryOrderId)
        assertEquals("exit-new", derived.single().exitOrderId)
        assertEquals(100.25, derived.single().entryPrice, 1e-9)
        assertEquals(103.25, derived.single().exitPrice, 1e-9)
        assertEquals(3.0, derived.single().pnl, 1e-9)
    }

    @Test
    fun brokerFillJournalReducerDoesNotCloseOnUnverifiedExitOrPartialQuantity() {
        val open = ticket(clientOrderId = "fk-wait").copy(brokerOrderId = "entry-order")
        val state = JournalState(openTickets = listOf(open))
        val entry = BrokerOrderRecord("entry-order", "fk-wait", "AAPL", "buy", "market", "bracket", "filled", 1.0, 100.0, 1_000L, 2_000L)
        val partialFill = com.alpacatalk.trading.ClosedTrade(
            "AAPL", "LONG", 0.5, 100.0, 104.0, 2.0, "2026-10-12T10:00:00Z", "exit-order", "entry-order",
        )

        val unverified = BrokerFillJournalReconciler.storeVerifiedTicketClosures(
            state, listOf(partialFill.copy(quantity = 1.0)), listOf(entry), setOf("entry-order"),
        )
        val partial = BrokerFillJournalReconciler.storeVerifiedTicketClosures(
            state, listOf(partialFill), listOf(entry), setOf("entry-order", "exit-order"),
        )

        assertTrue(unverified.second.isEmpty())
        assertEquals(1, unverified.first.openTickets.size)
        assertTrue(partial.second.isEmpty())
        assertEquals(1, partial.first.openTickets.size)
        assertTrue(partial.first.trades.isEmpty())
    }

    @Test
    fun reconcilerReportsOpenCancelledAndUnmatchedTicketsInsteadOfGuessing() {
        val open = ticket(clientOrderId = "fk-open", openedAtEpochMs = 1_000_000L)
        val cancelledTicket = ticket(clientOrderId = "fk-cancel", openedAtEpochMs = 1_000_000L)
        val unmatched = ticket(clientOrderId = "fk-missing", openedAtEpochMs = 1_000_000L)

        val openParent = BrokerOrderRecord("o3", "fk-open", "AAPL", "buy", "market", "bracket", "new", 0.0, 0.0, 1_000_000L, 0L,
            legs = listOf(BrokerOrderRecord("l1", null, "AAPL", "sell", "stop", "simple", "new", 0.0, 0.0, 1_000_000L, 0L)))
        val cancelledParent = BrokerOrderRecord("o4", "fk-cancel", "AAPL", "buy", "limit", "bracket", "canceled", 0.0, 0.0, 1_000_000L, 0L)

        val result = ClosedTradeReconciler.reconcile(listOf(open, cancelledTicket, unmatched), listOf(openParent, cancelledParent))
        assertTrue(result.closed.isEmpty())
        assertEquals(1, result.stillOpen.size)
        assertEquals(1, result.cancelled.size)
        assertEquals(1, result.untouched.size)
        assertEquals("fk-open", result.stillOpen.single().clientOrderId)
        assertEquals("fk-cancel", result.cancelled.single().clientOrderId)
        assertEquals("fk-missing", result.untouched.single().clientOrderId)
        assertTrue(result.notes.isNotEmpty())
    }

    @Test
    fun journalStatisticsSummariseTheRecord() {
        val snapshot = snapshotOf(
            trade("t1", TradeOutcome.WIN, appRating = 80, closedAtEpochMs = 1_000L),
            trade("t2", TradeOutcome.LOSS, appRating = 80, closedAtEpochMs = 2_000L),
            trade("t3", TradeOutcome.LOSS, appRating = 30, symbol = "MSFT", closedAtEpochMs = 3_000L),
        )
        val stats = snapshot.stats
        assertEquals(3, stats.totalTrades)
        assertEquals(1, stats.wins)
        assertEquals(2, stats.losses)
        assertEquals(33, stats.winRatePct)
        assertEquals(0.0, stats.netPips, 1e-9)
        assertEquals(1, stats.highRatedLosses)
        assertEquals("AAPL", stats.bestSymbol)
        assertEquals("MSFT", stats.worstSymbol)
    }

    @Test
    fun csvExportCarriesTheStopAndResultInPips() {
        val csv = TradeJournalEngine.csv(listOf(trade("t1", TradeOutcome.LOSS, appRating = 85, closedAtEpochMs = 1_000L)))
        val lines = csv.lines()
        assertTrue(lines.first().startsWith("trade_id,ticket_id,closed_at_utc,symbol,side,account,setup_family"))
        assertTrue(lines.first().contains("stop_pips"))
        assertTrue(lines.first().contains("result_pips"))
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("AAPL"))
        assertTrue(lines[1].contains("LOSS"))
        assertTrue(lines[1].contains("-200.0"))
        assertTrue(lines[1].contains("85"))
    }

    @Test
    fun forgettingASetupRemovesOnlyItsTrades() {
        val state = JournalState(
            trades = listOf(
                trade("t1", TradeOutcome.LOSS, appRating = 90, symbol = "AAPL", closedAtEpochMs = 1_000L),
                trade("t2", TradeOutcome.LOSS, appRating = 90, symbol = "MSFT", closedAtEpochMs = 2_000L),
            ),
        )
        val forgotten = TradeJournalEngine.forget(state, "AAPL|SCALP")
        assertEquals(1, forgotten.trades.size)
        assertEquals("MSFT", forgotten.trades.single().symbol)
        val allMarkets = TradeJournalEngine.forget(state, "*|SCALP")
        assertTrue(allMarkets.trades.isEmpty())
    }

    @Test
    fun liveMemoryCanExcludeManuallyEditedRecordsWithoutDeletingJournalHistory() {
        val edited = trade("manual", TradeOutcome.WIN, appRating = 90).copy(manuallyEdited = true)
        val state = JournalState(trades = listOf(edited))
        assertEquals(1, TradeJournalEngine.rebuild(state).memories.first { it.key == "AAPL|SCALP" }.wins)
        assertTrue(TradeJournalEngine.rebuild(state, excludeManuallyEdited = true).memories.isEmpty())
        assertTrue(state.trades.single().manuallyEdited)
    }

    @Test
    fun legacyJournalMigrationKeepsOnlyPaperTradesAndTickets() {
        val paper = trade("paper", TradeOutcome.WIN, appRating = 70).copy(accountMode = "PAPER")
        val live = trade("live", TradeOutcome.LOSS, appRating = 70).copy(accountMode = "LIVE")
        val paperTicket = ticket(clientOrderId = "paper-ticket").copy(accountMode = "PAPER")
        val liveTicket = ticket(clientOrderId = "live-ticket").copy(accountMode = "LIVE")
        val migrated = TradeJournalMigration.legacyToPaper(JournalState(trades = listOf(paper, live), openTickets = listOf(paperTicket, liveTicket)))
        assertEquals(2, migrated.version)
        assertEquals(listOf("paper"), migrated.trades.map { it.tradeId })
        assertEquals(listOf("paper-ticket"), migrated.openTickets.map { it.clientOrderId })
    }
}
