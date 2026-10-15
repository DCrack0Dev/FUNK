package com.alpacatalk.trading.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrokerClockPolicyTest {
    @Test fun openClockTimestampCreatedDuringRequestIsFreshAtResponseReceipt() {
        val requestStartedAt = 100_000L
        val brokerTimestamp = 100_010L
        val responseReceivedAt = 100_020L
        assertNull(BrokerClockPolicy.blockReason(true, brokerTimestamp, responseReceivedAt))
        // The old check compared requestStartedAt - brokerTimestamp, always negative here.
        assertEquals(-10L, requestStartedAt - brokerTimestamp)
    }

    @Test fun closedMissingAndStaleClockFailWithSpecificReasons() {
        check(BrokerClockPolicy.blockReason(false, 100_000L, 100_001L), "market is closed")
        check(BrokerClockPolicy.blockReason(null, 100_000L, 100_001L), "omitted is_open")
        check(BrokerClockPolicy.blockReason(true, null, 100_001L), "timestamp is missing")
        check(BrokerClockPolicy.blockReason(true, 10_000L, 100_001L), "stale")
        check(BrokerClockPolicy.blockReason(true, 120_000L, 100_000L), "clock skew")
    }

    @Test fun closedRegularClockIsAllowedOnlyWhenExtendedWindowWasValidated() {
        assertNull(BrokerClockPolicy.blockReason(false, 100_000L, 100_001L, allowClosedForExtendedHours = true))
    }

    private fun check(reason: String?, expected: String) {
        requireNotNull(reason)
        org.junit.Assert.assertTrue("$reason should mention $expected", reason.contains(expected, ignoreCase = true))
    }
}
