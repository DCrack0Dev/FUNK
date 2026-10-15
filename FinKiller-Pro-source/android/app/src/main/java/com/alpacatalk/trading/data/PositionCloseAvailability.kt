package com.alpacatalk.trading.data

import kotlin.math.abs

/**
 * Alpaca can report an open position whose shares/contracts are reserved by working orders.
 * DELETE /v2/positions closes the full position, so it must not be sent unless the full current
 * quantity is reported available after protective exits have been cancelled and settled.
 */
internal object PositionCloseAvailability {
    private const val EPSILON = 1e-8

    fun canCloseEntirePosition(positionQty: String?, availableQty: String?): Boolean {
        val position = positionQty?.toDoubleOrNull()?.let(::abs) ?: return false
        val available = availableQty?.toDoubleOrNull()?.let(::abs) ?: return false
        if (!position.isFinite() || !available.isFinite() || position <= EPSILON) return false
        return available + EPSILON >= position
    }

    fun describe(positionQty: String?, availableQty: String?): String =
        "position qty=${positionQty ?: "unknown"}, qty_available=${availableQty ?: "unknown"}"
}
