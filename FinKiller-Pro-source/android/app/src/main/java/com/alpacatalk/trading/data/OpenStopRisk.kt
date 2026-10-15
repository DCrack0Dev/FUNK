package com.alpacatalk.trading.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.abs

/** A broker-read position risk line. It does not place, replace, or cancel an order. */
data class PositionStopRisk(
    val symbol: String,
    val quantity: Double,
    val entryPrice: Double,
    val stopPrice: Double,
    val downsideRiskDollars: Double,
)

/** Null risk/cap means broker stop risk could not be fully verified; monitoring must fail closed. */
data class OpenStopRiskSnapshot(
    val equityDollars: Double?,
    val riskCapDollars: Double?,
    val downsideRiskDollars: Double?,
    val positions: List<PositionStopRisk>,
    val issue: String? = null,
    val verifiedAtEpochMs: Long = 0L,
) {
    val isVerified: Boolean get() = equityDollars != null && riskCapDollars != null && downsideRiskDollars != null && issue == null
    val capReached: Boolean get() = isVerified && downsideRiskDollars!! >= riskCapDollars!!
    val riskFreePositionCount: Int get() = positions.count { it.downsideRiskDollars <= 1e-8 }
    val riskPercent: Double? get() = if (isVerified && equityDollars!! > 0.0) downsideRiskDollars!! / equityDollars * 100.0 else null

    fun isFresh(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        isVerified && verifiedAtEpochMs > 0L && nowEpochMs >= verifiedAtEpochMs && nowEpochMs - verifiedAtEpochMs <= 30_000L

    companion object {
        fun unverified(reason: String, nowEpochMs: Long = System.currentTimeMillis()) =
            OpenStopRiskSnapshot(null, null, null, emptyList(), reason, nowEpochMs)
    }
}

/**
 * Computes downside loss to working stop prices from a read-only account snapshot. Profits above
 * entry are treated as zero downside risk, not as spendable risk credit. Any unprotected or
 * ambiguous exposure returns an unverified result rather than a guessed value.
 */
object OpenStopRiskCalculator {
    private val terminalStatuses = setOf("filled", "canceled", "cancelled", "expired", "rejected", "done_for_day", "stopped", "suspended", "calculated")
    private val stopTypes = setOf("stop", "trailing_stop")
    private val allStopTypes = stopTypes
    private val activeStatuses = setOf("new", "accepted", "pending_new", "accepted_for_bidding", "partially_filled", "pending_cancel", "pending_replace", "held")
    private val stableStopStatuses = setOf("new", "accepted", "accepted_for_bidding", "held")

    fun calculate(
        account: JsonObject,
        positions: List<JsonObject>,
        orders: List<JsonObject>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): OpenStopRiskSnapshot {
        val equity = account.number("equity")?.takeIf { it > 0.0 }
            ?: return OpenStopRiskSnapshot.unverified("Account equity is missing or non-positive.", nowEpochMs)
        val cap = equity * 0.10

        data class Position(val symbol: String, val quantity: Double, val entry: Double, val side: String)
        val parsedPositions = positions.map { raw ->
            val symbol = raw.text("symbol")?.uppercase(Locale.US)
                ?: return OpenStopRiskSnapshot.unverified("An open position is missing its symbol.", nowEpochMs)
            val assetClass = raw.text("asset_class")?.lowercase(Locale.US)
            if (assetClass != "us_equity") {
                return OpenStopRiskSnapshot.unverified("$symbol is not a verified US-equity position; stop risk is unknown.", nowEpochMs)
            }
            val quantity = raw.number("qty")?.let(::abs)?.takeIf { it > 0.0 }
                ?: return OpenStopRiskSnapshot.unverified("$symbol has an invalid position quantity.", nowEpochMs)
            val entry = raw.number("avg_entry_price")?.takeIf { it > 0.0 }
                ?: return OpenStopRiskSnapshot.unverified("$symbol has no valid average entry price.", nowEpochMs)
            val side = raw.text("side")?.lowercase(Locale.US)
                ?: return OpenStopRiskSnapshot.unverified("$symbol has no position side.", nowEpochMs)
            if (side !in setOf("long", "short")) {
                return OpenStopRiskSnapshot.unverified("$symbol has an unsupported position side.", nowEpochMs)
            }
            Position(symbol, quantity, entry, side)
        }

        val flattened = mutableListOf<JsonObject>()
        orders.forEach { flatten(it, flattened) }
        val positionsBySymbol = parsedPositions.associateBy { it.symbol }

        // A working standalone order that can create or add exposure is not ignored. A broker
        // bracket/OTO parent is allowed only when its broker response contains a valid protective
        // stop definition; its entry is still pending and therefore cannot be counted as a filled
        // position risk line yet. An attached OCO group is allowed only because its stop legs are
        // separately checked below.
        orders.filter { it.text("parent_order_id").isNullOrBlank() && isWorking(it.text("status")) }.forEach { order ->
            val orderType = order.text("type")?.lowercase(Locale.US)
            val orderClass = order.text("order_class")?.lowercase(Locale.US)
            val symbol = order.text("symbol")?.uppercase(Locale.US)
            val position = symbol?.let(positionsBySymbol::get)
            val side = order.text("side")?.lowercase(Locale.US)
            val isProtectiveStop = orderType in stopTypes && position != null && side == exitSide(position.side)
            val isOcoWrapper = orderClass == "oco" && order.legs().isNotEmpty() && position != null
            val isProtectedBracketEntry = orderClass in setOf("bracket", "oto") && hasBrokerProtectedStop(order)
            if (!isProtectiveStop && !isOcoWrapper && !isProtectedBracketEntry) {
                return OpenStopRiskSnapshot.unverified(describeUnverifiedOrder(order), nowEpochMs)
            }
        }

        val lines = mutableListOf<PositionStopRisk>()
        for (position in parsedPositions) {
            val candidates = flattened.filter { order ->
                    order.text("symbol")?.equals(position.symbol, ignoreCase = true) == true &&
                    order.text("side")?.equals(exitSide(position.side), ignoreCase = true) == true &&
                    order.text("status")?.lowercase(Locale.US) in stableStopStatuses &&
                    order.text("type")?.lowercase(Locale.US) in allStopTypes
            }
            val covered = candidates.mapNotNull { order ->
                val stop = order.number("stop_price")?.takeIf { it > 0.0 } ?: return@mapNotNull null
                val orderQty = (order.number("remaining_qty") ?: order.number("qty"))?.let(::abs) ?: return@mapNotNull null
                if (orderQty + 1e-8 < position.quantity) return@mapNotNull null
                val riskPerShare = if (position.side == "long") {
                    (position.entry - stop).coerceAtLeast(0.0)
                } else {
                    (stop - position.entry).coerceAtLeast(0.0)
                }
                PositionStopRisk(position.symbol, position.quantity, position.entry, stop, riskPerShare * position.quantity)
            }
            if (covered.isEmpty()) {
                return OpenStopRiskSnapshot.unverified("${position.symbol} has no single working, quantity-covering stop price that can be verified.", nowEpochMs)
            }
            // When multiple stops cover the position, use the most adverse downside risk.
            lines += covered.maxBy { it.downsideRiskDollars }
        }

        val totalRisk = lines.sumOf { it.downsideRiskDollars }
        return OpenStopRiskSnapshot(equity, cap, totalRisk, lines, verifiedAtEpochMs = nowEpochMs)
    }

    private fun flatten(order: JsonObject, output: MutableList<JsonObject>) {
        output += order
        order.legs().forEach { flatten(it, output) }
    }

    private fun JsonObject.legs(): List<JsonObject> =
        (this["legs"] as? JsonArray)?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    private fun isWorking(status: String?): Boolean =
        status?.lowercase(Locale.US)?.let { it in activeStatuses || it !in terminalStatuses } == true

    private fun hasBrokerProtectedStop(order: JsonObject): Boolean {
        val stopObjects = buildList {
            add(order["stop_loss"] as? JsonObject)
            order.legs().filter { it.text("type")?.lowercase(Locale.US) in allStopTypes }.forEach(::add)
        }
        return stopObjects.filterNotNull().any { stop ->
            val stopPrice = stop.number("stop_price")
            val status = stop.text("status")?.lowercase(Locale.US)
            stopPrice != null && stopPrice > 0.0 && (status == null || status in activeStatuses || status in stableStopStatuses)
        }
    }

    private fun describeUnverifiedOrder(order: JsonObject): String {
        val symbol = order.text("symbol")?.uppercase(Locale.US) ?: "unknown symbol"
        val id = order.text("id") ?: order.text("client_order_id") ?: "unknown id"
        val status = order.text("status") ?: "missing status"
        val side = order.text("side") ?: "missing side"
        val type = order.text("type") ?: "missing type"
        val qty = order.number("qty")?.toString() ?: "missing qty"
        val filled = order.number("filled_qty")?.toString() ?: "0"
        val remaining = order.number("remaining_qty")?.toString() ?: "unknown"
        val stop = order.number("stop_price")?.toString()
            ?: (order["stop_loss"] as? JsonObject)?.number("stop_price")?.toString()
            ?: "missing"
        return "Working entry or unclassified order for $symbol may add exposure (id=$id, status=$status, side=$side, type=$type, qty=$qty, filled=$filled, remaining=$remaining, stop=$stop)."
    }

    private fun exitSide(positionSide: String): String = if (positionSide == "long") "sell" else "buy"

    private fun JsonObject.text(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.number(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
