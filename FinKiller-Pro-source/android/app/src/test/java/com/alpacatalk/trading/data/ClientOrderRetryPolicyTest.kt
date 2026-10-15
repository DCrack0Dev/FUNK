package com.alpacatalk.trading.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientOrderRetryPolicyTest {
    @Test fun activeOrUnclearBrokerOrderBlocksRetryButTerminalOrderCanBeReplaced() {
        assertFalse(ClientOrderRetryPolicy.mayRetryAfterLookup(orderFound = true, status = "new"))
        assertFalse(ClientOrderRetryPolicy.mayRetryAfterLookup(orderFound = true, status = null))
        assertTrue(ClientOrderRetryPolicy.mayRetryAfterLookup(orderFound = true, status = "rejected"))
        assertTrue(ClientOrderRetryPolicy.mayRetryAfterLookup(orderFound = false, status = null))
    }

    @Test fun failedBrokerLookupIsNotTreatedAsMissingOrder() {
        assertTrue(ClientOrderRetryPolicy.blocksUnknownLookup(IllegalStateException("network")))
        assertFalse(ClientOrderRetryPolicy.blocksUnknownLookup(null))
    }
}
