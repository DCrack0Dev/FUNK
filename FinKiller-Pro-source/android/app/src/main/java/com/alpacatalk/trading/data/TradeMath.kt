package com.alpacatalk.trading.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Instrument math for the trade journal. Everything here is a pure function of prices, so the rules
 * that decide "how many pips was the stop" and "was that a win or a loss" can be unit tested without
 * Android, a broker, or a network.
 */
object TradeMath {
    const val STOCK_PIP_SIZE = 0.01
    const val FOREX_PIP_SIZE = 0.0001
    const val JPY_PIP_SIZE = 0.01
    const val GOLD_PIP_SIZE = 0.1
    const val SILVER_PIP_SIZE = 0.01

    fun normalizeSymbol(symbol: String): String = symbol.trim().uppercase(Locale.US).replace("_", "/")

    /**
     * One pip (forex/metals) or one tick (shares/ETFs) in price terms.
     * Stocks quote in cents, so one tick is 0.01.
     */
    fun pipSize(symbol: String): Double {
        val normalized = normalizeSymbol(symbol)
        val base = normalized.substringBefore('/')
        val quote = normalized.substringAfter('/', "")
        return when {
            base == "XAU" -> GOLD_PIP_SIZE
            base == "XAG" -> SILVER_PIP_SIZE
            quote == "JPY" -> JPY_PIP_SIZE
            normalized.contains('/') -> FOREX_PIP_SIZE
            else -> STOCK_PIP_SIZE
        }
    }

    fun pipLabel(symbol: String): String = if (normalizeSymbol(symbol).contains('/')) "pips" else "ticks"

    fun isShort(side: String): Boolean = side.trim().lowercase(Locale.US).let { it == "sell" || it == "short" }

    fun sideLabel(side: String): String = if (isShort(side)) "SHORT" else "LONG"

    /** Price movement expressed in pips, signed by the movement itself. */
    fun pipsBetween(symbol: String, from: Double, to: Double): Double {
        if (!from.isFinite() || !to.isFinite()) return 0.0
        return roundTo((to - from) / pipSize(symbol), 1)
    }

    /** Price movement expressed in pips, signed by trade direction (positive means profit). */
    fun resultPips(symbol: String, side: String, entryPrice: Double, exitPrice: Double): Double =
        roundTo(pipsBetween(symbol, entryPrice, exitPrice) * directionMultiplier(side), 1)

    fun stopPips(symbol: String, entryPrice: Double, stopPrice: Double): Double =
        roundTo(abs(pipsBetween(symbol, entryPrice, stopPrice)), 1)

    fun targetPips(symbol: String, entryPrice: Double, targetPrice: Double): Double =
        roundTo(abs(pipsBetween(symbol, entryPrice, targetPrice)), 1)

    fun directionMultiplier(side: String): Double = if (isShort(side)) -1.0 else 1.0

    fun riskReward(entryPrice: Double, stopPrice: Double, targetPrice: Double): Double {
        if (!entryPrice.isFinite() || !stopPrice.isFinite() || !targetPrice.isFinite()) return 0.0
        val risk = abs(entryPrice - stopPrice)
        if (risk <= 0.0) return 0.0
        return roundTo(abs(targetPrice - entryPrice) / risk, 2)
    }

    fun validationError(symbol: String, side: String, entryPrice: Double, stopPrice: Double, targetPrice: Double): String? {
        if (listOf(entryPrice, stopPrice, targetPrice).any { !it.isFinite() || it <= 0.0 }) return "non-positive or non-finite price"
        val short = isShort(side)
        if (short && !(targetPrice < entryPrice && entryPrice < stopPrice)) return "SHORT requires target < entry < stop"
        if (!short && !(stopPrice < entryPrice && entryPrice < targetPrice)) return "LONG requires stop < entry < target"
        return null
    }

    fun realizedPnl(side: String, entryPrice: Double, exitPrice: Double, quantity: Double): Double {
        if (!entryPrice.isFinite() || !exitPrice.isFinite() || !quantity.isFinite()) return 0.0
        return roundTo((exitPrice - entryPrice) * directionMultiplier(side) * quantity, 2)
    }

    /**
     * Classify the exit. A trade is a win only when the target was reached, a loss only when the stop
     * was reached; anything else, such as a manual close, is graded by the sign of the realised move.
     * The tolerance is a quarter of one pip, so an exact stop or target fill still counts as that event.
     */
    fun resolveOutcome(
        symbol: String,
        side: String,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        exitPrice: Double,
    ): TradeOutcome {
        if (!exitPrice.isFinite() || exitPrice <= 0.0 || !entryPrice.isFinite() || entryPrice <= 0.0) return TradeOutcome.UNKNOWN
        val long = !isShort(side)
        val tolerance = pipSize(symbol) * 0.25
        val targetReached = targetPrice.isFinite() && targetPrice > 0.0 &&
            (if (long) exitPrice >= targetPrice - tolerance else exitPrice <= targetPrice + tolerance)
        val stopReached = stopPrice.isFinite() && stopPrice > 0.0 &&
            (if (long) exitPrice <= stopPrice + tolerance else exitPrice >= stopPrice - tolerance)
        return when {
            targetReached && !stopReached -> TradeOutcome.WIN
            stopReached && !targetReached -> TradeOutcome.LOSS
            else -> when {
                realizedPnl(side, entryPrice, exitPrice, 1.0) > 0.0 -> TradeOutcome.WIN
                realizedPnl(side, entryPrice, exitPrice, 1.0) < 0.0 -> TradeOutcome.LOSS
                else -> TradeOutcome.BREAKEVEN
            }
        }
    }

    fun roundTo(value: Double, decimals: Int): Double {
        if (!value.isFinite()) return 0.0
        var factor = 1.0
        repeat(decimals.coerceIn(0, 8)) { factor *= 10.0 }
        return (value * factor).roundToLong() / factor
    }

    fun formatPips(value: Double): String = String.format(Locale.US, "%+.1f", value)

    fun formatPrice(value: Double): String = String.format(Locale.US, "%.4f", value)

    fun formatPnl(value: Double): String = String.format(Locale.US, "%+.2f", value)
}
