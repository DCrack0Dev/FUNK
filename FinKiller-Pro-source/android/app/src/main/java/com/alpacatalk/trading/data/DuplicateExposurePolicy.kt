package com.alpacatalk.trading.data

import java.util.Locale

/** Fail-closed same-symbol guard; intentionally blocks netting/opposite-side orders too. */
object DuplicateExposurePolicy {
    fun blockReason(symbol: String, openPositionSymbols: Iterable<String>, workingOrderSymbols: Iterable<String>): String? {
        val normalized = symbol.trim().uppercase(Locale.US)
        if (normalized.isEmpty()) return "A normalized symbol is required for duplicate-exposure review."
        if (openPositionSymbols.any { it.trim().uppercase(Locale.US) == normalized }) return "An open position already exists for $normalized."
        if (workingOrderSymbols.any { it.trim().uppercase(Locale.US) == normalized }) return "A working order already exists for $normalized."
        return null
    }
}
