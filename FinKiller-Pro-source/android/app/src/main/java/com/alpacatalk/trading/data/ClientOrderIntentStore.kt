package com.alpacatalk.trading.data

import android.content.Context
import java.util.Locale

/** Non-secret intent journal written before broker POST; it survives app/process restarts. */
class ClientOrderIntentStore(context: Context) {
    private val prefs = context.getSharedPreferences("fin_killer_client_order_intents", Context.MODE_PRIVATE)

    fun put(accountMode: String, symbol: String, clientOrderId: String) {
        prefs.edit().putString(key(accountMode, symbol), clientOrderId).commit()
    }

    fun get(accountMode: String, symbol: String): String? = prefs.getString(key(accountMode, symbol), null)

    fun remove(accountMode: String, symbol: String) {
        prefs.edit().remove(key(accountMode, symbol)).commit()
    }

    private fun key(accountMode: String, symbol: String): String =
        "${accountMode.uppercase(Locale.US)}|${symbol.uppercase(Locale.US)}"
}

/** Only an explicitly terminal broker state permits another client order for the same persisted intent. */
object ClientOrderRetryPolicy {
    private val terminal = setOf("filled", "canceled", "cancelled", "expired", "rejected", "done_for_day", "stopped", "suspended", "calculated")

    fun mayRetryAfterLookup(orderFound: Boolean, status: String?): Boolean =
        !orderFound || status?.lowercase(Locale.US) in terminal

    fun blocksUnknownLookup(error: Throwable?): Boolean = error != null
}
