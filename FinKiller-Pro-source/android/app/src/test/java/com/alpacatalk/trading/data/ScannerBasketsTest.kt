package com.alpacatalk.trading.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerBasketsTest {
    @Test
    fun allOriginalBasketsAndDowNdaqSymbolsArePreserved() {
        assertEquals(16, ScannerBaskets.baskets.size)
        assertTrue(ScannerBaskets.allSymbols.contains("DOW"))
        assertTrue(ScannerBaskets.allSymbols.contains("NDAQ"))
        assertEquals(ScannerBaskets.allSymbols.size, ScannerBaskets.allSymbols.distinct().size)
        assertEquals(ScannerBaskets.baskets.sumOf { (_, symbols) -> symbols.split(',').size }, 160)
        assertEquals(156, ScannerBaskets.allSymbols.size)
    }

    @Test
    fun batchesCoverEveryUniqueMarketExactlyOnceAndRespectAlpacaLimit() {
        val batches = ScannerBaskets.scanBatches()
        assertEquals(16, batches.size)
        assertEquals(4, ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES)
        assertTrue(batches.all { it.size in 1..10 })
        assertEquals(ScannerBaskets.allSymbols, batches.flatten())
        assertFalse(batches.flatten().groupingBy { it }.eachCount().values.any { it != 1 })
        assertEquals(6, batches.last().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchSizeMustBePositive() {
        ScannerBaskets.scanBatches(0)
    }
}
