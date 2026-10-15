package com.alpacatalk.trading

import com.alpacatalk.trading.data.PositionCloseAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionCloseAvailabilityTest {
    @Test
    fun fullPositionCloseAllowedWhenAllThreeUnitsAreAvailable() {
        assertTrue(PositionCloseAvailability.canCloseEntirePosition("3", "3"))
    }

    @Test
    fun fullPositionCloseBlockedWhenNoQuantityIsAvailable() {
        assertFalse(PositionCloseAvailability.canCloseEntirePosition("3", "0"))
    }

    @Test
    fun fullPositionCloseBlockedWhenOnlyPartOfThePositionIsAvailable() {
        assertFalse(PositionCloseAvailability.canCloseEntirePosition("3", "1"))
    }

    @Test
    fun negativeShortPositionQuantityUsesAbsoluteSize() {
        assertTrue(PositionCloseAvailability.canCloseEntirePosition("-3", "3"))
    }

    @Test
    fun missingOrMalformedBrokerAvailabilityFailsClosed() {
        assertFalse(PositionCloseAvailability.canCloseEntirePosition("3", null))
        assertFalse(PositionCloseAvailability.canCloseEntirePosition("3", "unknown"))
        assertFalse(PositionCloseAvailability.canCloseEntirePosition(null, "3"))
    }

    @Test
    fun nonFiniteOrZeroPositionDoesNotPassCloseGate() {
        assertFalse(PositionCloseAvailability.canCloseEntirePosition("NaN", "3"))
        assertFalse(PositionCloseAvailability.canCloseEntirePosition("0", "3"))
    }
}
