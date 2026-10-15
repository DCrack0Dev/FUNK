package com.alpacatalk.trading.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicateExposurePolicyTest {
    @Test fun matchingPositionOrWorkingOrderBlocksCaseInsensitively() {
        assertNotNull(DuplicateExposurePolicy.blockReason("AAPL", listOf("aapl"), emptyList()))
        assertNotNull(DuplicateExposurePolicy.blockReason("AAPL", emptyList(), listOf("Aapl")))
    }

    @Test fun unrelatedSymbolsDoNotTriggerDuplicateBlock() {
        assertNull(DuplicateExposurePolicy.blockReason("AAPL", listOf("MSFT"), listOf("NVDA")))
    }
}
