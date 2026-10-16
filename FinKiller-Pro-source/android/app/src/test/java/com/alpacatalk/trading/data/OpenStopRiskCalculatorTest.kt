package com.alpacatalk.trading.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenStopRiskCalculatorTest {
    private fun account(equity: Double) = buildJsonObject { put("equity", equity) }

    private fun position(
        symbol: String = "AAPL",
        qty: Double,
        entry: Double,
        side: String = "long",
    ) = buildJsonObject {
        put("symbol", symbol)
        put("asset_class", "us_equity")
        put("qty", qty)
        put("avg_entry_price", entry)
        put("side", side)
    }

    private fun stop(
        symbol: String = "AAPL",
        qty: Double,
        price: Double,
        side: String = "sell",
        status: String = "new",
    ) = buildJsonObject {
        put("symbol", symbol)
        put("qty", qty)
        put("stop_price", price)
        put("side", side)
        put("type", "stop")
        put("status", status)
    }

    @Test
    fun pausesAtExactlyTenPercentOfEquity() {
        val risk = OpenStopRiskCalculator.calculate(
            account(100.0),
            listOf(position(qty = 2.0, entry = 100.0)),
            listOf(stop(qty = 2.0, price = 95.0)),
            nowEpochMs = 10_000L,
        )
        assertTrue(risk.isVerified)
        assertTrue(risk.capReached)
        assertEquals(10.0, risk.downsideRiskDollars!!, 1e-8)
        assertEquals(10.0, risk.riskCapDollars!!, 1e-8)
        assertTrue(risk.isFresh(nowEpochMs = 40_000L))
        assertFalse(risk.isFresh(nowEpochMs = 40_001L))
    }

    @Test
    fun aStopAtOrAboveEntryIsRiskFreeButDoesNotCreateNegativeRiskCredit() {
        val risk = OpenStopRiskCalculator.calculate(
            account(100.0),
            listOf(position(qty = 3.0, entry = 100.0)),
            listOf(stop(qty = 3.0, price = 105.0)),
            nowEpochMs = 1_000L,
        )
        assertTrue(risk.isVerified)
        assertFalse(risk.capReached)
        assertEquals(0.0, risk.downsideRiskDollars!!, 1e-8)
        assertEquals(1, risk.riskFreePositionCount)
    }

    @Test
    fun shortRiskUsesBuyStopAboveEntry() {
        val risk = OpenStopRiskCalculator.calculate(
            account(1_000.0),
            listOf(position(qty = 3.0, entry = 100.0, side = "short")),
            listOf(stop(qty = 3.0, price = 105.0, side = "buy")),
            nowEpochMs = 1_000L,
        )
        assertTrue(risk.isVerified)
        assertEquals(15.0, risk.downsideRiskDollars!!, 1e-8)
    }

    @Test
    fun trailingStopReleasesHeadroomAndMultiplePositionsRecalculateAgainstFreshEquity() {
        val positions = listOf(
            position(symbol = "AAPL", qty = 10.0, entry = 100.0),
            position(symbol = "MSFT", qty = 5.0, entry = 50.0),
        )
        val wideStops = listOf(stop(symbol = "AAPL", qty = 10.0, price = 90.0), stop(symbol = "MSFT", qty = 5.0, price = 45.0))
        val wide = OpenStopRiskCalculator.calculate(account(100_000.0), positions, wideStops, nowEpochMs = 1_000L)
        assertEquals(125.0, wide.downsideRiskDollars!!, 1e-8)
        val trailedStops = listOf(stop(symbol = "AAPL", qty = 10.0, price = 99.0), stop(symbol = "MSFT", qty = 5.0, price = 50.0))
        val trailed = OpenStopRiskCalculator.calculate(account(100_000.0), positions, trailedStops, nowEpochMs = 2_000L)
        assertEquals(10.0, trailed.downsideRiskDollars!!, 1e-8)
        val lowerEquity = OpenStopRiskCalculator.calculate(account(50.0), positions, trailedStops, nowEpochMs = 3_000L)
        assertTrue(lowerEquity.capReached)
        assertEquals(20.0, lowerEquity.riskPercent!!, 1e-8)
    }

    @Test
    fun missingQuantityCoveringStopFailsClosed() {
        val risk = OpenStopRiskCalculator.calculate(
            account(1_000.0),
            listOf(position(qty = 3.0, entry = 100.0)),
            listOf(stop(qty = 2.0, price = 95.0)),
            nowEpochMs = 1_000L,
        )
        assertFalse(risk.isVerified)
        assertNull(risk.downsideRiskDollars)
        assertTrue(risk.issue!!.contains("no single working"))
    }

    @Test
    fun strictlyUnfilledPendingEntryDoesNotCreateCurrentStopRisk() {
        val pendingEntry = buildJsonObject {
            put("symbol", "MSFT")
            put("qty", 1.0)
            put("filled_qty", 0.0)
            put("type", "limit")
            put("side", "buy")
            put("status", "accepted")
        }
        val risk = OpenStopRiskCalculator.calculate(account(1_000.0), emptyList(), listOf(pendingEntry), nowEpochMs = 1_000L)
        assertTrue(risk.isVerified)
        assertEquals(0.0, risk.downsideRiskDollars!!, 1e-8)
    }

    @Test
    fun partiallyFilledPendingEntryWithoutProtectionStillFailsClosed() {
        val partiallyFilledEntry = buildJsonObject {
            put("symbol", "MSFT")
            put("qty", 10.0)
            put("filled_qty", 1.0)
            put("remaining_qty", 9.0)
            put("type", "limit")
            put("side", "buy")
            put("status", "accepted")
        }
        val risk = OpenStopRiskCalculator.calculate(account(1_000.0), emptyList(), listOf(partiallyFilledEntry), nowEpochMs = 1_000L)
        assertFalse(risk.isVerified)
        assertNull(risk.downsideRiskDollars)
        assertTrue(risk.issue!!.contains("Working entry"))
    }

    @Test
    fun workingProtectedBracketParentDoesNotInvalidateUnrelatedRiskSnapshot() {
        val pendingBracket = buildJsonObject {
            put("id", "mdy-entry-1")
            put("symbol", "MDY")
            put("qty", 10.0)
            put("filled_qty", 0.0)
            put("remaining_qty", 10.0)
            put("type", "limit")
            put("side", "buy")
            put("status", "accepted")
            put("order_class", "bracket")
            put("stop_loss", buildJsonObject { put("stop_price", 450.0) })
        }
        val risk = OpenStopRiskCalculator.calculate(account(100_000.0), emptyList(), listOf(pendingBracket), nowEpochMs = 1_000L)
        assertTrue(risk.isVerified)
        assertEquals(0.0, risk.downsideRiskDollars!!, 1e-8)
    }

    @Test
    fun zeroFilledBracketWithoutBrokerStopDoesNotCreateCurrentStopRisk() {
        val unprotectedBracket = buildJsonObject {
            put("id", "mdy-entry-2")
            put("symbol", "MDY")
            put("qty", 10.0)
            put("filled_qty", 0.0)
            put("remaining_qty", 10.0)
            put("type", "limit")
            put("side", "buy")
            put("status", "accepted")
            put("order_class", "bracket")
        }
        val risk = OpenStopRiskCalculator.calculate(account(100_000.0), emptyList(), listOf(unprotectedBracket), nowEpochMs = 1_000L)
        assertTrue(risk.isVerified)
        assertEquals(0.0, risk.downsideRiskDollars!!, 1e-8)
    }

    @Test
    fun findsStopLegNestedUnderFilledBracketParent() {
        val parent = buildJsonObject {
            put("symbol", "AAPL")
            put("type", "market")
            put("side", "buy")
            put("status", "filled")
            put("order_class", "bracket")
            put("legs", buildJsonArray {
                add(buildJsonObject {
                    put("symbol", "AAPL")
                    put("qty", 2.0)
                    put("stop_price", 95.0)
                    put("side", "sell")
                    put("type", "stop")
                    put("status", "new")
                    put("parent_order_id", "entry-id")
                })
            })
        }
        val risk = OpenStopRiskCalculator.calculate(
            account(1_000.0),
            listOf(position(qty = 2.0, entry = 100.0)),
            listOf(parent),
            nowEpochMs = 1_000L,
        )
        assertTrue(risk.isVerified)
        assertEquals(10.0, risk.downsideRiskDollars!!, 1e-8)
    }

    @Test
    fun stopLimitDoesNotCountAsVerifiedDownsideProtection() {
        val stopLimit = buildJsonObject {
            put("symbol", "AAPL")
            put("qty", 2.0)
            put("stop_price", 95.0)
            put("side", "sell")
            put("type", "stop_limit")
            put("status", "new")
        }
        val risk = OpenStopRiskCalculator.calculate(
            account(1_000.0),
            listOf(position(qty = 2.0, entry = 100.0)),
            listOf(stopLimit),
            nowEpochMs = 1_000L,
        )
        assertFalse(risk.isVerified)
    }
}
