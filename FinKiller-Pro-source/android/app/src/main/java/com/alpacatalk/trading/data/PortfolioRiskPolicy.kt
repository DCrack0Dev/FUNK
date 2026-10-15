package com.alpacatalk.trading.data

import kotlin.math.abs

/** Limits are explicit percentages of verified equity, not fixed dollar amounts. */
data class PortfolioRiskLimits(
    val singleOrderPct: Double = 1.0,
    val batchIncrementPct: Double = 2.0,
    val strategyAggregatePct: Double = 3.0,
    val absolutePortfolioPct: Double = 10.0,
    val maxExposurePct: Double = 80.0,
) {
    init {
        require(singleOrderPct > 0 && batchIncrementPct > 0 && strategyAggregatePct > 0 && absolutePortfolioPct > 0 && maxExposurePct > 0)
        require(strategyAggregatePct <= absolutePortfolioPct)
        require(singleOrderPct <= strategyAggregatePct && batchIncrementPct <= strategyAggregatePct)
    }
}

data class RiskGateResult(val allowed: Boolean, val reason: String? = null) {
    companion object {
        fun allow() = RiskGateResult(true)
        fun block(reason: String) = RiskGateResult(false, reason)
    }
}

object PortfolioRiskPolicy {
    /** The 3% scanner allocation is a stricter sub-limit; 10% remains the absolute account-wide ceiling. */
    fun assessStopRisk(
        equityDollars: Double,
        currentOpenRiskDollars: Double,
        proposedOrderRiskDollars: Double,
        proposedBatchRiskDollars: Double = proposedOrderRiskDollars,
        limits: PortfolioRiskLimits = PortfolioRiskLimits(),
    ): RiskGateResult {
        if (!equityDollars.isFinite() || equityDollars <= 0.0) return RiskGateResult.block("Verified positive account equity is required.")
        if (listOf(currentOpenRiskDollars, proposedOrderRiskDollars, proposedBatchRiskDollars).any { !it.isFinite() || it < 0.0 }) {
            return RiskGateResult.block("Risk inputs are missing, negative, or non-finite.")
        }
        val singleCap = equityDollars * limits.singleOrderPct / 100.0
        val batchCap = equityDollars * limits.batchIncrementPct / 100.0
        val strategyCap = equityDollars * limits.strategyAggregatePct / 100.0
        val absoluteCap = equityDollars * limits.absolutePortfolioPct / 100.0
        if (proposedOrderRiskDollars > singleCap + 1e-8) return RiskGateResult.block("Proposed single order risk exceeds ${limits.singleOrderPct}% of equity.")
        if (proposedBatchRiskDollars > batchCap + 1e-8) return RiskGateResult.block("Proposed batch risk exceeds ${limits.batchIncrementPct}% of equity.")
        if (currentOpenRiskDollars >= absoluteCap - 1e-8) return RiskGateResult.block("Current verified portfolio stop risk is at or above the ${limits.absolutePortfolioPct}% hard cap.")
        // Batch increment is checked as a separate planned aggregate. At this call site the
        // caller refreshes currentOpenRisk before each sequential submit, so that snapshot already
        // includes previously accepted members of the same batch; aggregate portfolio totals add
        // only the next order here to avoid counting those earlier orders twice.
        val total = currentOpenRiskDollars + proposedOrderRiskDollars
        if (total > absoluteCap + 1e-8) return RiskGateResult.block("Open plus proposed stop risk would exceed the ${limits.absolutePortfolioPct}% hard cap.")
        if (total > strategyCap + 1e-8) return RiskGateResult.block("Open plus proposed scanner risk exceeds the ${limits.strategyAggregatePct}% strategy allocation.")
        return RiskGateResult.allow()
    }

    fun assessFreshness(verifiedAtEpochMs: Long, nowEpochMs: Long, maxAgeMs: Long = 30_000L): RiskGateResult =
        if (verifiedAtEpochMs > 0L && nowEpochMs >= verifiedAtEpochMs && nowEpochMs - verifiedAtEpochMs <= maxAgeMs) RiskGateResult.allow()
        else RiskGateResult.block("Broker risk snapshot is missing, from the future, or older than ${maxAgeMs / 1000} seconds.")
}

/** Notional exposure is separate from loss-at-stop and uses equity-scaled thresholds. */
object NotionalExposurePolicy {
    fun grossNotional(positions: List<PositionNotional>, workingOrders: List<OrderNotional>): Double? {
        if (positions.any { it.marketValue == null || !it.marketValue.isFinite() }) return null
        val directionBySymbol = positions.associate { position ->
            val normalizedSide = position.side?.trim()?.lowercase()
            val direction = when (normalizedSide) {
                "long", "buy" -> "long"
                "short", "sell" -> "short"
                else -> position.marketValue!!.let { if (it > 0.0) "long" else if (it < 0.0) "short" else "flat" }
            }
            position.symbol.trim().uppercase() to direction
        }
        val positionNotionalBySymbol = positions.groupBy { it.symbol.trim().uppercase() }
            .mapValues { (_, sameSymbolPositions) -> sameSymbolPositions.sumOf { abs(it.marketValue!!) } }
        var grossOpenOrderNotional = 0.0
        for (order in workingOrders) {
            val positionDirection = directionBySymbol[order.symbol.trim().uppercase()]
            val orderSide = order.side?.trim()?.lowercase()
            val closesExistingPosition = (positionDirection == "long" && orderSide == "sell") ||
                (positionDirection == "short" && orderSide == "buy")
            val notional = order.notional ?: return null
            if (!notional.isFinite() || notional < 0.0) return null
            // Exit orders against a position are not new gross exposure. Linked bracket legs
            // should also be filtered by the caller. If an opposite-side order is large enough
            // to reverse and exceed the existing position, count only that excess increase.
            if (closesExistingPosition) {
                val currentPositionNotional = positionNotionalBySymbol[order.symbol.trim().uppercase()] ?: 0.0
                grossOpenOrderNotional += (abs(notional) - 2.0 * currentPositionNotional).coerceAtLeast(0.0)
            } else {
                grossOpenOrderNotional += abs(notional)
            }
        }
        return positions.sumOf { abs(it.marketValue!!) } + grossOpenOrderNotional
    }

    fun remainingCapacityDollars(equityDollars: Double, grossExposureDollars: Double?, maximumPct: Double = 80.0): Double? {
        if (!equityDollars.isFinite() || equityDollars <= 0.0 || grossExposureDollars == null ||
            !grossExposureDollars.isFinite() || grossExposureDollars < 0.0 || !maximumPct.isFinite() || maximumPct <= 0.0
        ) return null
        return (equityDollars * maximumPct / 100.0 - grossExposureDollars).coerceAtLeast(0.0)
    }

    fun assess(equityDollars: Double, grossExposureDollars: Double?, proposedNotionalDollars: Double, maximumPct: Double = 80.0): RiskGateResult {
        if (!equityDollars.isFinite() || equityDollars <= 0.0 || grossExposureDollars == null || !grossExposureDollars.isFinite() || grossExposureDollars < 0.0) {
            return RiskGateResult.block("Exposure cannot be verified from the current account snapshot.")
        }
        if (!proposedNotionalDollars.isFinite() || proposedNotionalDollars < 0.0 || !maximumPct.isFinite() || maximumPct <= 0.0) {
            return RiskGateResult.block("Proposed notional or exposure limit is invalid.")
        }
        val currentPct = grossExposureDollars / equityDollars * 100.0
        val afterDollars = grossExposureDollars + proposedNotionalDollars
        val afterPct = afterDollars / equityDollars * 100.0
        return if (afterPct > maximumPct + 1e-8) {
            RiskGateResult.block(
                "Gross notional exposure would be ${"%.2f".format(java.util.Locale.US, afterPct)}% of equity " +
                    "(current ${"%.2f".format(java.util.Locale.US, grossExposureDollars)} USD / ${"%.2f".format(java.util.Locale.US, equityDollars)} USD equity = ${"%.2f".format(java.util.Locale.US, currentPct)}%; " +
                    "proposed ${"%.2f".format(java.util.Locale.US, proposedNotionalDollars)} USD; " +
                    "after ${"%.2f".format(java.util.Locale.US, afterDollars)} USD; limit ${maximumPct}%)."
            )
        } else RiskGateResult.allow()
    }
}

data class PositionNotional(val symbol: String, val marketValue: Double?, val side: String? = null)
data class OrderNotional(val symbol: String, val notional: Double?, val side: String? = null)

data class ExposureReview(val currentPct: Double, val afterProposedPct: Double)
