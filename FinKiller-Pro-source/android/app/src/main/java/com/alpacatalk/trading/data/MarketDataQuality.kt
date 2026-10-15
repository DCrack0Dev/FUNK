package com.alpacatalk.trading.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Explicit data-quality outcome; absent timestamps/spreads are failures, never implicit passes. */
data class MarketDataQualityResult(
    val passed: Boolean,
    val timeframesCovered: Int,
    val timeframeCount: Int,
    val reasons: List<String>,
    val quoteAgeMs: Long? = null,
    val spreadPct: Double? = null,
)

object MarketDataQuality {
    val REQUIRED_TIMEFRAMES = listOf("1Min", "15Min", "1Hour", "1Day")
    const val MIN_COMPLETED_BARS_PER_TIMEFRAME = 20
    const val MAX_QUOTE_AGE_MS = 60_000L
    const val DEFAULT_MAX_SPREAD_PCT = 0.5

    fun assess(
        barCounts: Map<String, Int>,
        dataErrors: List<String>,
        quoteTimestampEpochMs: Long?,
        bid: Double?,
        ask: Double?,
        nowEpochMs: Long,
        halted: Boolean = false,
        maxSpreadPct: Double = DEFAULT_MAX_SPREAD_PCT,
    ): MarketDataQualityResult {
        val reasons = mutableListOf<String>()
        val covered = REQUIRED_TIMEFRAMES.count { (barCounts[it] ?: 0) >= MIN_COMPLETED_BARS_PER_TIMEFRAME }
        REQUIRED_TIMEFRAMES.forEach { tf ->
            val count = barCounts[tf] ?: 0
            if (count < MIN_COMPLETED_BARS_PER_TIMEFRAME) reasons += "$tf has $count completed bars; requires $MIN_COMPLETED_BARS_PER_TIMEFRAME"
        }
        if (dataErrors.isNotEmpty()) reasons += "Unresolved market-data errors: ${dataErrors.joinToString("; ")}"
        val age = quoteTimestampEpochMs?.let { nowEpochMs - it }
        if (age == null || age < 0L || age > MAX_QUOTE_AGE_MS) reasons += "Quote timestamp is missing, future-dated, or older than ${MAX_QUOTE_AGE_MS / 1000}s"
        val validPrices = bid?.isFinite() == true && ask?.isFinite() == true && bid!! > 0.0 && ask!! >= bid
        val spread = if (validPrices) ((ask!! - bid!!) / ((ask!! + bid!!) / 2.0) * 100.0) else null
        if (spread == null) reasons += "A valid two-sided quote is required"
        else if (!maxSpreadPct.isFinite() || maxSpreadPct <= 0.0 || spread > maxSpreadPct) reasons += "Spread ${"%.3f".format(java.util.Locale.US, spread)}% exceeds ${maxSpreadPct}%"
        if (halted) reasons += "Instrument is halted"
        return MarketDataQualityResult(reasons.isEmpty(), covered, REQUIRED_TIMEFRAMES.size, reasons, age, spread)
    }

    /** Removes a still-forming final candle when its interval has not closed yet. */
    fun dropFormingFinalBar(timestampEpochMs: Long, timeframe: String, nowEpochMs: Long): Boolean {
        if (timestampEpochMs <= 0L) return false
        val duration = when (timeframe) {
            "1Min" -> 60_000L
            "15Min" -> 15 * 60_000L
            "1Hour" -> 60 * 60_000L
            "1Day" -> return isCurrentNewYorkSessionDay(timestampEpochMs, nowEpochMs)
            else -> return false
        }
        val intervalStart = timestampEpochMs / duration * duration
        return intervalStart + duration > nowEpochMs
    }

    fun regularSessionOpen(nowEpochMs: Long): Boolean {
        val ny = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneId.of("America/New_York"))
        val minutes = ny.hour * 60 + ny.minute
        return ny.dayOfWeek.value in 1..5 && minutes in (9 * 60 + 30) until (16 * 60)
    }

    /** Paper-only order window approved for pre-market and after-hours: 04:00–20:00 ET. */
    fun extendedSessionOpen(nowEpochMs: Long): Boolean {
        val ny = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneId.of("America/New_York"))
        val minutes = ny.hour * 60 + ny.minute
        return ny.dayOfWeek.value in 1..5 && minutes in (4 * 60) until (20 * 60)
    }

    private fun isCurrentNewYorkSessionDay(barTimestampMs: Long, nowEpochMs: Long): Boolean {
        val zone = ZoneId.of("America/New_York")
        return Instant.ofEpochMilli(barTimestampMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    }
}
