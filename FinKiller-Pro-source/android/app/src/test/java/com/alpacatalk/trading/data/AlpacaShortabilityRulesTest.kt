package com.alpacatalk.trading.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpacaShortabilityRulesTest {
    @Test
    fun allowsOnlyVerifiedActiveTradableShortableEasyToBorrowAssets() {
        assertTrue(AlpacaShortabilityRules.canOpenShort("active", true, true, true))
        assertTrue(AlpacaShortabilityRules.canOpenShort("ACTIVE", true, true, true))
    }

    @Test
    fun blocksUnavailableOrUnverifiedAssets() {
        assertFalse(AlpacaShortabilityRules.canOpenShort("inactive", true, true, true))
        assertFalse(AlpacaShortabilityRules.canOpenShort("active", false, true, true))
        assertFalse(AlpacaShortabilityRules.canOpenShort("active", true, false, true))
        assertFalse(AlpacaShortabilityRules.canOpenShort("active", true, true, false))
        assertFalse(AlpacaShortabilityRules.canOpenShort(null, true, true, true))
        assertFalse(AlpacaShortabilityRules.canOpenShort("active", null, true, true))
        assertFalse(AlpacaShortabilityRules.canOpenShort("active", true, null, true))
        assertFalse(AlpacaShortabilityRules.canOpenShort("active", true, true, null))
    }
}
