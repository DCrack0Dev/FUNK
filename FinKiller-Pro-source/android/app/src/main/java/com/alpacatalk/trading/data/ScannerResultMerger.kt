package com.alpacatalk.trading.data

import java.util.Locale

/** Replaces refreshed candidates in place while keeping results for unscanned symbols visible. */
object ScannerResultMerger {
    fun merge(existing: List<MarketSnapshot>, refreshed: List<MarketSnapshot>): List<MarketSnapshot> {
        if (refreshed.isEmpty()) return existing.toList()
        val refreshedSymbols = refreshed.map { it.symbol.uppercase(Locale.US) }.toSet()
        return (existing.filterNot { it.symbol.uppercase(Locale.US) in refreshedSymbols } + refreshed)
            .sortedByDescending { it.score }
    }
}
