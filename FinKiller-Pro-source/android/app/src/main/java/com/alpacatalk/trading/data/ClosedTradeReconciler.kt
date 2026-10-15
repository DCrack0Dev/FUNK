package com.alpacatalk.trading.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * One broker order, flattened out of the broker's JSON so the reconciliation rules stay pure and
 * testable. Bracket parents carry their protection legs in [legs].
 */
data class BrokerOrderRecord(
    val orderId: String,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val type: String,
    val orderClass: String,
    val status: String,
    val filledQuantity: Double,
    val filledAveragePrice: Double,
    val submittedAtEpochMs: Long,
    val filledAtEpochMs: Long,
    val legs: List<BrokerOrderRecord> = emptyList(),
) {
    val isFilled: Boolean
        get() = filledQuantity > 0.0 && filledAveragePrice > 0.0 && status.lowercase(Locale.US) in setOf("filled", "partially_filled")

    val isDead: Boolean
        get() = status.lowercase(Locale.US) in setOf("canceled", "cancelled", "expired", "rejected", "replaced", "done_for_day")

    val isStopLeg: Boolean
        get() = type.lowercase(Locale.US).contains("stop")

    val isTargetLeg: Boolean
        get() = type.lowercase(Locale.US).contains("limit")
}

/** Maps Alpaca order JSON into [BrokerOrderRecord]. Kept next to the reconciler for reviewability. */
object BrokerOrderMapper {
    fun from(json: JsonObject): BrokerOrderRecord? {
        val orderId = json.text("id") ?: return null
        val symbol = json.text("symbol") ?: return null
        val legs = (json["legs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::from) } ?: emptyList()
        return BrokerOrderRecord(
            orderId = orderId,
            clientOrderId = json.text("client_order_id"),
            symbol = symbol,
            side = json.text("side") ?: "buy",
            type = json.text("type") ?: "market",
            orderClass = json.text("order_class") ?: "simple",
            status = json.text("status") ?: "unknown",
            filledQuantity = json.number("filled_qty") ?: 0.0,
            filledAveragePrice = json.number("filled_avg_price") ?: 0.0,
            submittedAtEpochMs = epochMillis(json.text("submitted_at")),
            filledAtEpochMs = epochMillis(json.text("filled_at")),
            legs = legs,
        )
    }

    fun fromAll(array: List<JsonObject>): List<BrokerOrderRecord> = array.mapNotNull(::from)

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun epochMillis(timestamp: String?): Long =
        timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

/** Builds journal entries from scanner setups, submitted brackets, and manual records. */
object TradeTicketFactory {
    const val CLIENT_ORDER_ID_PREFIX = "finkiller"

    fun newClientOrderId(): String = "$CLIENT_ORDER_ID_PREFIX-${UUID.randomUUID().toString().take(18)}"

    fun ticket(
        symbol: String,
        side: String,
        accountMode: String,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        quantity: Double,
        setupLabel: String,
        signalScoreAtEntry: Int,
        appRatingAtEntry: Int,
        directiveAtEntry: TradeDirective,
        entryType: String,
        openedAtEpochMs: Long,
        clientOrderId: String,
        brokerOrderId: String? = null,
        notes: String = "",
    ): OpenTradeTicket {
        val normalizedSymbol = TradeMath.normalizeSymbol(symbol)
        val family = TradeJournalEngine.familyForTrade(setupLabel, side)
        return OpenTradeTicket(
            ticketId = "ticket-$clientOrderId",
            clientOrderId = clientOrderId,
            symbol = normalizedSymbol,
            side = side,
            accountMode = accountMode,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            quantity = quantity,
            setupLabel = setupLabel,
            setupFamily = family,
            memoryKey = TradeJournalEngine.keyOf(normalizedSymbol, family),
            signalScoreAtEntry = signalScoreAtEntry,
            appRatingAtEntry = appRatingAtEntry,
            directiveAtEntry = directiveAtEntry,
            stopPips = TradeMath.stopPips(normalizedSymbol, entryPrice, stopPrice),
            targetPips = TradeMath.targetPips(normalizedSymbol, entryPrice, targetPrice),
            riskReward = TradeMath.riskReward(entryPrice, stopPrice, targetPrice),
            entryType = entryType,
            openedAtEpochMs = openedAtEpochMs,
            brokerOrderId = brokerOrderId,
            notes = notes,
        )
    }

    /** Turns an open ticket plus a realised exit price into a stored closed trade. */
    fun close(
        ticket: OpenTradeTicket,
        exitPrice: Double,
        closedAtEpochMs: Long,
        source: TradeJournalSource,
        outcomeOverride: TradeOutcome? = null,
        realizedPnlOverride: Double? = null,
        brokerOrderId: String? = null,
        tradeIdOverride: String? = null,
        notes: String = "",
    ): ClosedTrade {
        val outcome = outcomeOverride ?: TradeMath.resolveOutcome(
            symbol = ticket.symbol,
            side = ticket.side,
            entryPrice = ticket.entryPrice,
            stopPrice = ticket.stopPrice,
            targetPrice = ticket.targetPrice,
            exitPrice = exitPrice,
        )
        val trade = ClosedTrade(
            tradeId = tradeIdOverride ?: "trade-${UUID.randomUUID().toString().take(18)}",
            ticketId = ticket.ticketId,
            brokerOrderId = brokerOrderId ?: ticket.brokerOrderId,
            symbol = ticket.symbol,
            side = ticket.side,
            accountMode = ticket.accountMode,
            setupLabel = ticket.setupLabel,
            setupFamily = ticket.setupFamily,
            memoryKey = ticket.memoryKey,
            entryPrice = ticket.entryPrice,
            stopPrice = ticket.stopPrice,
            targetPrice = ticket.targetPrice,
            exitPrice = exitPrice,
            quantity = ticket.quantity,
            stopPips = ticket.stopPips,
            targetPips = ticket.targetPips,
            resultPips = TradeMath.resultPips(ticket.symbol, ticket.side, ticket.entryPrice, exitPrice),
            realizedPnl = realizedPnlOverride ?: TradeMath.realizedPnl(ticket.side, ticket.entryPrice, exitPrice, ticket.quantity),
            riskReward = ticket.riskReward,
            signalScoreAtEntry = ticket.signalScoreAtEntry,
            appRatingAtEntry = ticket.appRatingAtEntry,
            directiveAtEntry = ticket.directiveAtEntry,
            outcome = outcome,
            openedAtEpochMs = ticket.openedAtEpochMs,
            closedAtEpochMs = closedAtEpochMs,
            entryType = ticket.entryType,
            source = source,
            lesson = "",
            notes = if (notes.isBlank()) ticket.notes else notes,
            manuallyEdited = source == TradeJournalSource.MANUAL,
        )
        return trade.copy(lesson = TradeJournalEngine.lessonFor(trade))
    }

    /**
     * A trade the user typed in. It still carries the rating the app had given the setup, so a
     * historical loss the app rated highly still corrects the memory.
     */
    fun manualTrade(
        symbol: String,
        side: String,
        accountMode: String,
        setupLabel: String,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        exitPrice: Double,
        quantity: Double,
        signalScoreAtEntry: Int,
        appRatingAtEntry: Int,
        directiveAtEntry: TradeDirective,
        openedAtEpochMs: Long,
        closedAtEpochMs: Long,
        outcomeOverride: TradeOutcome? = null,
        notes: String = "",
    ): ClosedTrade {
        val ticket = ticket(
            symbol = symbol,
            side = side,
            accountMode = accountMode,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            quantity = quantity,
            setupLabel = setupLabel,
            signalScoreAtEntry = signalScoreAtEntry,
            appRatingAtEntry = appRatingAtEntry,
            directiveAtEntry = directiveAtEntry,
            entryType = "manual",
            openedAtEpochMs = openedAtEpochMs,
            clientOrderId = "manual-${UUID.randomUUID().toString().take(12)}",
            notes = notes,
        )
        return close(
            ticket = ticket,
            exitPrice = exitPrice,
            closedAtEpochMs = closedAtEpochMs,
            source = TradeJournalSource.MANUAL,
            outcomeOverride = outcomeOverride,
            tradeIdOverride = "manual-${UUID.randomUUID().toString().take(18)}",
            notes = notes,
        )
    }
}

/**
 * Matches the app's open tickets against broker order state.
 *
 * Matching order, strongest first: the client order id the app sent with the bracket, then a bracket
 * parent with protection legs submitted inside the time window, then any same-symbol order inside the
 * window. Whatever cannot be matched is explicitly reported instead of guessed at.
 */
object ClosedTradeReconciler {
    const val MATCH_WINDOW_MS = 6L * 60L * 60L * 1000L

    fun reconcile(tickets: List<OpenTradeTicket>, orders: List<BrokerOrderRecord>): ReconciliationResult {
        val closed = mutableListOf<ClosedTrade>()
        val stillOpen = mutableListOf<OpenTradeTicket>()
        val cancelled = mutableListOf<OpenTradeTicket>()
        val untouched = mutableListOf<OpenTradeTicket>()
        val notes = mutableListOf<String>()

        // Exact matches first, then a guarded time-window fallback. An order already claimed by another
        // ticket, or carrying a client order id that belongs to no known ticket, is never re-used.
        val knownTags = tickets.map { it.clientOrderId }.toSet() + tickets.mapNotNull { it.brokerOrderId }.toSet()
        val claimedOrderIds = mutableSetOf<String>()
        val parentsByTicket = mutableMapOf<String, BrokerOrderRecord>()
        tickets.forEach { ticket ->
            val exact = orders.firstOrNull { order ->
                (order.clientOrderId != null && order.clientOrderId == ticket.clientOrderId) ||
                    (ticket.brokerOrderId != null && order.orderId == ticket.brokerOrderId)
            }
            if (exact != null) {
                parentsByTicket[ticket.ticketId] = exact
                claimedOrderIds += exact.orderId
            }
        }
        tickets.filterNot { parentsByTicket.containsKey(it.ticketId) }.forEach { ticket ->
            val candidates = orders.filter { order ->
                order.orderId !in claimedOrderIds &&
                    TradeMath.normalizeSymbol(order.symbol) == TradeMath.normalizeSymbol(ticket.symbol) &&
                    order.submittedAtEpochMs > 0 &&
                    abs(order.submittedAtEpochMs - ticket.openedAtEpochMs) <= MATCH_WINDOW_MS &&
                    (order.clientOrderId == null || order.clientOrderId !in knownTags)
            }
            val fallback = candidates.filter { it.legs.isNotEmpty() }.minByOrNull { abs(it.submittedAtEpochMs - ticket.openedAtEpochMs) }
                ?: candidates.minByOrNull { abs(it.submittedAtEpochMs - ticket.openedAtEpochMs) }
            if (fallback != null) {
                parentsByTicket[ticket.ticketId] = fallback
                claimedOrderIds += fallback.orderId
            }
        }

        tickets.forEach { ticket ->
            val parent = parentsByTicket[ticket.ticketId]
            if (parent == null) {
                untouched += ticket
                return@forEach
            }
            val filledLeg = parent.legs.firstOrNull { it.isFilled && (it.isStopLeg || it.isTargetLeg) }
                ?: parent.legs.filter { it.isFilled }.maxByOrNull { it.filledQuantity }
            val expectedQuantity = parent.filledQuantity.takeIf { it > 0.0 }?.let { minOf(ticket.quantity, it) } ?: ticket.quantity
            if (filledLeg != null && filledLeg.filledQuantity + 0.0000001 < expectedQuantity) {
                stillOpen += ticket
                notes += "${ticket.symbol}: exit leg ${filledLeg.orderId} is only partially filled (${filledLeg.filledQuantity}/$expectedQuantity); journal waits for verified full closure."
                return@forEach
            }
            if (filledLeg != null) {
                val entryPrice = parent.filledAveragePrice.takeIf { it > 0.0 } ?: ticket.entryPrice
                val exitPrice = filledLeg.filledAveragePrice
                val quantity = filledLeg.filledQuantity.takeIf { it > 0.0 } ?: parent.filledQuantity.takeIf { it > 0.0 } ?: ticket.quantity
                val effectiveTicket = if (entryPrice != ticket.entryPrice) ticket.copy(entryPrice = entryPrice) else ticket
                val exitKind = when {
                    filledLeg.isStopLeg -> "stop-loss leg"
                    filledLeg.isTargetLeg -> "take-profit leg"
                    else -> "protection leg"
                }
                val trade = TradeTicketFactory.close(
                    ticket = effectiveTicket,
                    exitPrice = exitPrice,
                    closedAtEpochMs = filledLeg.filledAtEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    source = TradeJournalSource.APP_BRACKET,
                    brokerOrderId = parent.orderId,
                    tradeIdOverride = "broker-${parent.orderId}",
                    notes = "Reconciled from the $exitKind of bracket ${parent.orderId}.",
                )
                closed += trade
                notes += "${ticket.symbol}: ${trade.outcome.name} via $exitKind; entry ${TradeMath.formatPrice(entryPrice)} → exit ${TradeMath.formatPrice(exitPrice)} (${trade.resultPips} ${TradeMath.pipLabel(ticket.symbol)})."
            } else if (parent.isDead && parent.filledQuantity <= 0.0) {
                cancelled += ticket
                notes += "${ticket.symbol}: entry order ${parent.orderId} ended as ${parent.status} without filling, so no trade was journalled."
            } else {
                stillOpen += ticket
                notes += "${ticket.symbol}: bracket ${parent.orderId} is ${parent.status}; no protection leg has filled yet."
            }
        }

        if (tickets.isEmpty()) notes += "No open tickets to reconcile."
        if (untouched.isNotEmpty()) notes += "${untouched.size} ticket(s) had no matching broker order in the fetched window; they stay open and can be closed manually."
        return ReconciliationResult(closed, stillOpen, cancelled, untouched, notes)
    }
}
