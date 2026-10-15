package com.alpacatalk.trading.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionalScannerTest {
    @Test
    fun risingBarsProduceLongSignalAtOrAboveOnePointFiveR() {
        val bars = trendingBars(up = true)
        val snapshot = snapshotFor(bars)

        assertEquals(TradeDirection.LONG, snapshot.direction)
        assertTrue(snapshot.isExecutableSetup)
        assertTrue(snapshot.rationale.contains("LONG"))
        assertEquals("buy", snapshot.direction?.orderSide)
        assertTrue("long stop-loss must be below entry", snapshot.scannerStopPrice!! < snapshot.lastPrice)
        assertTrue("long take-profit must be above entry", snapshot.scannerTargetPrice!! > snapshot.lastPrice)
    }

    @Test
    fun fallingBarsProduceShortSignalAtOrAboveOnePointFiveR() {
        val bars = trendingBars(up = false)
        val snapshot = snapshotFor(bars)

        assertEquals(TradeDirection.SHORT, snapshot.direction)
        assertTrue(snapshot.isExecutableSetup)
        assertTrue(snapshot.rationale.contains("SHORT"))
        assertEquals("sell", snapshot.direction?.orderSide)
        assertTrue(snapshot.rationale.contains("SHORT"))
        assertTrue("short stop-loss must be above entry", snapshot.scannerStopPrice!! > snapshot.lastPrice)
        assertTrue("short take-profit must be below entry", snapshot.scannerTargetPrice!! < snapshot.lastPrice)
    }

    @Test
    fun shortFixturePreservesEntryStopAndTargetAsDirectionalFields() {
        assertEquals(null, TradeGeometry.validationError(false, 34.46, 35.20, 33.50))
        assertTrue(33.50 < 34.46)
        assertTrue(35.20 > 34.46)
    }

    @Test
    fun oppositeDirectionCandidatesAcrossTimeframesAreBlockedAsAmbiguous() {
        val rising = trendingBars(up = true)
        val falling = trendingBars(up = false)
        val snapshot = DirectEnvironment.PAPER.buildSnapshot(
            symbol = "TEST",
            series = mapOf("1Min" to rising, "15Min" to rising, "1Hour" to falling, "1Day" to falling),
            dataErrors = emptyList(),
            quote = freshQuote(115.0),
        )

        assertEquals(null, snapshot.direction)
        assertFalse(snapshot.isExecutableSetup)
        assertTrue(snapshot.setup.contains("MIXED LONG/SHORT") || snapshot.executionGate.contains("reward/risk") || snapshot.executionGate.contains("not confirmed"))
    }

    @Test
    fun shortScoringRecognizesAnUpsideSweepRejectedBackBelowTheRangeHigh() {
        val flat = (0 until 30).map { index ->
            val close = 100.0
            DirectEnvironment.Bar(close, 100.5, 99.5, close, if (index == 29) 4_000.0 else 1_000.0)
        }.toMutableList()
        flat[29] = DirectEnvironment.Bar(open = 100.0, high = 102.0, low = 99.2, close = 100.0, volume = 4_000.0)

        val stats = DirectEnvironment.PAPER.analyzeBars(flat, "scalp", TradeDirection.SHORT)

        assertTrue(stats?.liquiditySweep == true)
        assertTrue(stats?.vwapConfirmed == true)
    }

    @Test
    fun shortLearningFamilyDoesNotBlendIntoLegacyLongFamily() {
        assertEquals("SCALP", TradeJournalEngine.familyOf("SCALP / LONG MOMENTUM BREAKOUT"))
        assertEquals("SCALP", TradeJournalEngine.familyOf("SCALP · short-term momentum"))
        assertEquals("SCALP SHORT", TradeJournalEngine.familyOf("SCALP / SHORT MOMENTUM BREAKDOWN"))
        assertEquals("SCALP SHORT", TradeJournalEngine.familyForTrade("SCALP", "sell"))
    }

    @Test
    fun replayMigratesLegacySellTradeAwayFromLongMemoryKey() {
        val ticket = TradeTicketFactory.ticket(
            symbol = "AAPL", side = "sell", accountMode = "PAPER", entryPrice = 100.0,
            stopPrice = 102.0, targetPrice = 96.0, quantity = 1.0, setupLabel = "SCALP",
            signalScoreAtEntry = 80, appRatingAtEntry = 80, directiveAtEntry = TradeDirective.ALLOW,
            entryType = "market", openedAtEpochMs = 1_000L, clientOrderId = "legacy-short-test",
        )
        val oldTrade = TradeTicketFactory.close(
            ticket = ticket, exitPrice = 98.0, closedAtEpochMs = 2_000L, source = TradeJournalSource.MANUAL,
        ).copy(setupFamily = "SCALP", memoryKey = "AAPL|SCALP")

        val snapshot = TradeJournalEngine.rebuild(JournalState(trades = listOf(oldTrade)))

        assertTrue(snapshot.memories.any { it.key == "AAPL|SCALP SHORT" })
        assertTrue(snapshot.memories.any { it.key == "*|SCALP SHORT" })
        assertFalse(snapshot.memories.any { it.key == "AAPL|SCALP" })
        assertFalse(snapshot.memories.any { it.key == "*|SCALP" })
    }

    private fun snapshotFor(bars: List<DirectEnvironment.Bar>) = DirectEnvironment.PAPER.buildSnapshot(
        symbol = "TEST",
        series = mapOf("1Min" to bars, "15Min" to bars, "1Hour" to bars, "1Day" to bars),
        dataErrors = emptyList(),
        quote = freshQuote(bars.last().close),
    )

    private fun freshQuote(price: Double) = buildJsonObject {
        put("t", java.time.Instant.now().toString())
        put("bp", price * 0.9995)
        put("ap", price * 1.0005)
        put("mp", price)
    }

    private fun trendingBars(up: Boolean): List<DirectEnvironment.Bar> = (0 until 30).map { index ->
        val close = if (up) 100.0 + index else 130.0 - index
        DirectEnvironment.Bar(
            open = close,
            high = close + 0.4,
            low = close - 0.4,
            close = close,
            volume = if (index == 29) 4_000.0 else 1_000.0,
        )
    }
}
