package com.alpacatalk.trading.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeRiskPolicyTest {
    @Test fun shortFixtureAt3446AcceptsStopAboveAndTargetBelowEntry() {
        assertEquals(null, TradeGeometry.validationError(false, 34.46, 35.20, 33.00))
        val plan = TradeGeometry.plan(false, 34.46, 35.20, 33.00, 100_000.0)
        assertTrue(plan.targetPrice < plan.entryPrice)
        assertTrue(plan.stopPrice > plan.entryPrice)
    }

    @Test fun shortTakeProfitAboveEntryIsRejectedWithAllPrices() {
        val error = TradeGeometry.validationError(false, 34.46, 35.20, 34.80)
        assertTrue(error?.contains("Short/SELL take-profit must be below entry") == true)
        assertTrue(error?.contains("entry=34.460000") == true)
        assertTrue(error?.contains("target=34.800000") == true)
    }

    @Test fun staleShortBracketRebasesToFreshEntryWithoutChangingDistances() {
        val rebased = TradeGeometry.rebaseBracket(false, 155.51, 157.51, 151.51, 159.61)
        assertTrue(rebased.first > 159.61)
        assertTrue(rebased.second < 159.61)
        assertEquals(2.0, rebased.first - 159.61, 1e-8)
        assertEquals(4.0, 159.61 - rebased.second, 1e-8)
        assertEquals(null, TradeGeometry.validationError(false, 159.61, rebased.first, rebased.second))
    }

    @Test fun invalidPricesAndBothOpeningDirectionsFailClosed() {
        assertTrue(TradeGeometry.validationError(true, 0.0, 99.0, 101.0)!!.contains("Entry price"))
        assertTrue(TradeGeometry.validationError(false, 34.46, -1.0, 33.50)!!.contains("Stop-loss"))
        assertTrue(TradeGeometry.validationError(true, 100.0, 98.0, 99.0)!!.contains("take-profit"))
        assertEquals(null, TradeGeometry.validationError(false, 100.0, 102.0, 99.0))
    }

    @Test fun longAndShortUseDirectionalBracketGeometry() {
        val long = TradeGeometry.plan(true, 100.0, 98.0, 104.0, 100_000.0)
        assertEquals(250, long.quantity)
        assertEquals(500.0, long.estimatedRiskDollars, 1e-8)
        assertEquals(2.0, long.rewardRisk, 1e-8)
        val short = TradeGeometry.plan(false, 100.0, 102.0, 96.0, 100_000.0)
        assertEquals(250, short.quantity)
        assertEquals(2.0, short.rewardRisk, 1e-8)
    }

    @Test fun rewardRiskBoundaryOfOneToOnePasses() {
        assertEquals(1.0, TradeGeometry.plan(true, 100.0, 98.0, 102.0, 100_000.0).rewardRisk, 1e-8)
    }

    @Test fun riskSizingFloorsWholeContractsAndCapsRiskBudget() {
        val plan = TradeGeometry.plan(true, 100.0, 97.9, 104.0, 10_000.0)
        assertEquals(23, plan.quantity)
        assertTrue(plan.estimatedRiskDollars <= plan.riskBudgetDollars)
    }

    @Test fun tightStopsCannotCreateFourTimesEquityNotionalBecauseExposureCapsTheShareCount() {
        val long = TradeGeometry.plan(
            isLong = true, entryPrice = 100.0, stopPrice = 99.9, targetPrice = 100.15,
            equityDollars = 100_000.0, maximumNotionalDollars = 80_000.0,
        )
        assertEquals(5_000, long.riskSizedQuantity)
        assertEquals(800, long.quantity)
        assertTrue(long.limitedByNotionalExposure)
        assertEquals(80_000.0, long.quantity * long.entryPrice, 1e-8)
        assertEquals(80.0, long.estimatedRiskDollars, 1e-8)

        val short = TradeGeometry.plan(
            isLong = false, entryPrice = 100.0, stopPrice = 100.1, targetPrice = 99.85,
            equityDollars = 100_000.0, maximumNotionalDollars = 80_000.0,
        )
        assertEquals(800, short.quantity)
        assertTrue(short.limitedByNotionalExposure)
    }

    @Test fun protectiveStopsAndTargetsAreOnTheCorrectLossAndProfitSidesForBothDirections() {
        assertTrue(TradeGeometry.plan(true, 100.0, 98.0, 104.0, 100_000.0).stopPrice < 100.0)
        assertTrue(TradeGeometry.plan(true, 100.0, 98.0, 104.0, 100_000.0).targetPrice > 100.0)
        assertTrue(TradeGeometry.plan(false, 100.0, 102.0, 96.0, 100_000.0).stopPrice > 100.0)
        assertTrue(TradeGeometry.plan(false, 100.0, 102.0, 96.0, 100_000.0).targetPrice < 100.0)
    }

    @Test fun wrongSideStopsAndTargetsAndLowRewardRiskAreRejected() {
        assertRejected { TradeGeometry.plan(true, 100.0, 101.0, 104.0, 100_000.0) }
        assertRejected { TradeGeometry.plan(false, 100.0, 99.0, 96.0, 100_000.0) }
        assertRejected { TradeGeometry.plan(true, 100.0, 98.0, 101.9, 100_000.0) }
    }

    @Test fun riskBudgetCannotExceedOnePercentAndCannotSizeBelowOne() {
        assertRejected { TradeGeometry.plan(true, 100.0, 98.0, 104.0, 100_000.0, 0.01001) }
        assertRejected { TradeGeometry.plan(true, 100.0, 1.0, 250.0, 100.0, 0.005) }
    }

    @Test fun portfolioCapsHaveDistinctReasonsAndExactBoundaries() {
        val boundary = PortfolioRiskPolicy.assessStopRisk(100_000.0, 1_000.0, 1_000.0, 2_000.0)
        assertTrue(boundary.allowed)
        val singleOver = PortfolioRiskPolicy.assessStopRisk(100_000.0, 0.0, 1_000.01, 1_000.01)
        assertFalse(singleOver.allowed)
        assertTrue(singleOver.reason!!.contains("single order"))
        val batchOver = PortfolioRiskPolicy.assessStopRisk(100_000.0, 0.0, 500.0, 2_000.01)
        assertFalse(batchOver.allowed)
        assertTrue(batchOver.reason!!.contains("batch"))
        val strategyOver = PortfolioRiskPolicy.assessStopRisk(100_000.0, 2_950.0, 100.0, 2_000.0)
        assertFalse(strategyOver.allowed)
        assertTrue(strategyOver.reason!!.contains("strategy allocation"))
        val laterBatchOrder = PortfolioRiskPolicy.assessStopRisk(100_000.0, 1_500.0, 500.0, 2_000.0)
        assertTrue("previous sequential orders are already represented in current open risk", laterBatchOrder.allowed)
        val hardCap = PortfolioRiskPolicy.assessStopRisk(100_000.0, 10_000.0, 0.0, 0.0)
        assertFalse(hardCap.allowed)
        assertTrue(hardCap.reason!!.contains("hard cap"))
    }

    @Test fun staleRiskSnapshotFailsClosed() {
        assertTrue(PortfolioRiskPolicy.assessFreshness(70_000L, 100_000L).allowed)
        assertFalse(PortfolioRiskPolicy.assessFreshness(69_999L, 100_000L).allowed)
        assertFalse(PortfolioRiskPolicy.assessFreshness(100_001L, 100_000L).allowed)
        assertFalse(PortfolioRiskPolicy.assessFreshness(0L, 100_000L).allowed)
    }

    @Test fun twoSmallPositionsOnHundredThousandEquityDoNotTripExposureByCount() {
        val existing = NotionalExposurePolicy.grossNotional(
            listOf(PositionNotional("ES1", 5_000.0), PositionNotional("EURUSD", 5_000.0)),
            emptyList(),
        )
        assertEquals(10_000.0, existing!!, 1e-8)
        assertTrue(NotionalExposurePolicy.assess(100_000.0, existing, 5_000.0).allowed)
    }

    @Test fun closingOrdersDoNotGetAddedAgainToExistingGrossPositionExposure() {
        val longGross = NotionalExposurePolicy.grossNotional(
            listOf(PositionNotional("AAPL", 20_000.0, "long")),
            listOf(OrderNotional("AAPL", 20_000.0, "sell")),
        )
        assertEquals(20_000.0, longGross!!, 1e-8)

        val shortGross = NotionalExposurePolicy.grossNotional(
            listOf(PositionNotional("TSLA", -15_000.0, "short")),
            listOf(OrderNotional("TSLA", 15_000.0, "buy")),
        )
        assertEquals(15_000.0, shortGross!!, 1e-8)

        val entryAdds = NotionalExposurePolicy.grossNotional(
            listOf(PositionNotional("AAPL", 20_000.0, "long")),
            listOf(OrderNotional("AAPL", 5_000.0, "buy")),
        )
        assertEquals(25_000.0, entryAdds!!, 1e-8)

        val oversizedReverse = NotionalExposurePolicy.grossNotional(
            listOf(PositionNotional("AAPL", 20_000.0, "long")),
            listOf(OrderNotional("AAPL", 50_000.0, "sell")),
        )
        assertEquals(30_000.0, oversizedReverse!!, 1e-8)
    }

    @Test fun excessiveNotionalExposureBlocksAndMissingExposureFailsClosed() {
        val blocked = NotionalExposurePolicy.assess(100_000.0, 79_000.0, 2_000.0)
        assertFalse(blocked.allowed)
        assertTrue(blocked.reason!!.contains("current 79000.00 USD"))
        assertTrue(blocked.reason!!.contains("proposed 2000.00 USD"))
        assertTrue(blocked.reason!!.contains("equity"))
        assertFalse(NotionalExposurePolicy.assess(100_000.0, null, 100.0).allowed)
        assertFalse(NotionalExposurePolicy.assess(100_000.0, 1_000.0, Double.NaN).allowed)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid trade plan to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
