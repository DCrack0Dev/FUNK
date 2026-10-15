package com.alpacatalk.trading.data

/** Validates the server clock using response-receipt time, not the pre-request client timestamp. */
object BrokerClockPolicy {
    const val MAX_CLOCK_AGE_MS = 60_000L
    const val MAX_FUTURE_SKEW_MS = 15_000L

    fun blockReason(
        isOpen: Boolean?,
        timestampEpochMs: Long?,
        receivedAtEpochMs: Long,
        allowClosedForExtendedHours: Boolean = false,
    ): String? {
        if (isOpen == null) return "Execution blocked: Alpaca clock response omitted is_open; no order was sent."
        if (!isOpen && !allowClosedForExtendedHours) return "Execution blocked: Alpaca reports the regular market is closed; no order was sent."
        if (timestampEpochMs == null || timestampEpochMs <= 0L) {
            return "Execution blocked: Alpaca clock timestamp is missing or invalid; no order was sent."
        }
        val ageMs = receivedAtEpochMs - timestampEpochMs
        if (ageMs > MAX_CLOCK_AGE_MS || ageMs < -MAX_FUTURE_SKEW_MS) {
            return "Execution blocked: Alpaca clock timestamp is stale or outside the allowed device-clock skew (age ${ageMs}ms); refresh account time and retry."
        }
        return null
    }
}
