package com.alpacatalk.trading.data

import kotlin.math.floor

/** Pure, broker-independent bracket validation and risk-budget sizing. */
data class TradeOrderPlan(
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val quantity: Int,
    val riskBudgetDollars: Double,
    val estimatedRiskDollars: Double,
    val rewardRisk: Double,
    val riskSizedQuantity: Int,
    val limitedByNotionalExposure: Boolean,
)

object TradeGeometry {
    const val DEFAULT_RISK_BUDGET_PCT = 0.005
    const val HARD_MAX_RISK_BUDGET_PCT = 0.01
    /** A 1:1 bracket is acceptable when all other entry and risk gates pass. */
    const val MIN_REWARD_RISK = 1.0
    const val MIN_SCAN_REWARD_RISK = MIN_REWARD_RISK
    const val MAX_SCAN_REWARD_RISK = 10.0

    /** Returns null only when the opening bracket has valid directional price geometry. */
    fun validationError(isLong: Boolean, entryPrice: Double, stopPrice: Double, targetPrice: Double): String? {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return "Entry price must be positive and finite (entry=${format(entryPrice)})."
        if (!stopPrice.isFinite() || stopPrice <= 0.0) return "Stop-loss must be positive and finite (stop=${format(stopPrice)})."
        if (!targetPrice.isFinite() || targetPrice <= 0.0) return "Take-profit must be positive and finite (target=${format(targetPrice)})."
        return if (isLong) {
            when {
                stopPrice >= entryPrice -> "Long/BUY stop-loss must be below entry (stop=${format(stopPrice)}, entry=${format(entryPrice)}, target=${format(targetPrice)})."
                targetPrice <= entryPrice -> "Long/BUY take-profit must be above entry (stop=${format(stopPrice)}, entry=${format(entryPrice)}, target=${format(targetPrice)})."
                else -> null
            }
        } else {
            when {
                stopPrice <= entryPrice -> "Short/SELL stop-loss must be above entry (stop=${format(stopPrice)}, entry=${format(entryPrice)}, target=${format(targetPrice)})."
                targetPrice >= entryPrice -> "Short/SELL take-profit must be below entry (stop=${format(stopPrice)}, entry=${format(entryPrice)}, target=${format(targetPrice)})."
                else -> null
            }
        }
    }

    /** Re-anchor a still-valid scanner bracket to a newer entry quote without changing its R:R. */
    fun rebaseBracket(
        isLong: Boolean,
        referenceEntryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        freshEntryPrice: Double,
    ): Pair<Double, Double> {
        require(validationError(isLong, referenceEntryPrice, stopPrice, targetPrice) == null) {
            "Scanner bracket is not directionally valid at its reference entry."
        }
        require(freshEntryPrice.isFinite() && freshEntryPrice > 0.0) { "Fresh entry price must be positive and finite." }
        val stopDistance = if (isLong) referenceEntryPrice - stopPrice else stopPrice - referenceEntryPrice
        val targetDistance = if (isLong) targetPrice - referenceEntryPrice else referenceEntryPrice - targetPrice
        return if (isLong) {
            freshEntryPrice - stopDistance to (freshEntryPrice + targetDistance)
        } else {
            (freshEntryPrice + stopDistance) to (freshEntryPrice - targetDistance)
        }
    }

    fun plan(
        isLong: Boolean,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        equityDollars: Double,
        requestedRiskBudgetPct: Double = DEFAULT_RISK_BUDGET_PCT,
        maximumNotionalDollars: Double? = null,
    ): TradeOrderPlan {
        val geometryError = validationError(isLong, entryPrice, stopPrice, targetPrice)
        require(geometryError == null) { geometryError!! }
        require(equityDollars.isFinite() && equityDollars > 0.0) { "Verified account equity is required." }
        require(requestedRiskBudgetPct.isFinite() && requestedRiskBudgetPct > 0.0 && requestedRiskBudgetPct <= HARD_MAX_RISK_BUDGET_PCT) {
            "Per-trade risk budget must be greater than zero and no more than 1% of equity."
        }
        require(maximumNotionalDollars == null || maximumNotionalDollars.isFinite() && maximumNotionalDollars >= 0.0) {
            "Remaining gross-notional capacity must be finite and non-negative."
        }
        val riskPerShare = if (isLong) entryPrice - stopPrice else stopPrice - entryPrice
        val rewardPerShare = if (isLong) targetPrice - entryPrice else entryPrice - targetPrice
        val rewardRisk = rewardPerShare / riskPerShare
        require(rewardRisk + 1e-9 >= MIN_REWARD_RISK) {
            "Bracket reward/risk ${"%.3f".format(java.util.Locale.US, rewardRisk)} is below 1:1."
        }
        val budget = equityDollars * requestedRiskBudgetPct
        val riskSizedQuantity = floor(budget / riskPerShare).toInt()
        val exposureSizedQuantity = maximumNotionalDollars?.let { floor(it / entryPrice).toInt() }
        val quantity = minOf(riskSizedQuantity, exposureSizedQuantity ?: riskSizedQuantity)
        require(quantity >= 1) {
            if (maximumNotionalDollars != null && exposureSizedQuantity == 0) {
                "Remaining notional headroom ${"%.2f".format(java.util.Locale.US, maximumNotionalDollars)} USD cannot support one share/contract at this entry price."
            } else "Risk budget supports less than one whole share/contract at this stop distance."
        }
        return TradeOrderPlan(
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            quantity = quantity,
            riskBudgetDollars = budget,
            estimatedRiskDollars = quantity * riskPerShare,
            rewardRisk = rewardRisk,
            riskSizedQuantity = riskSizedQuantity,
            limitedByNotionalExposure = quantity < riskSizedQuantity,
        )
    }

    private fun format(value: Double): String = if (value.isFinite()) "%.6f".format(java.util.Locale.US, value) else value.toString()
}
