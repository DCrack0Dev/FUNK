package com.alpacatalk.trading

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alpacatalk.trading.data.DirectEnvironment
import com.alpacatalk.trading.data.BatchScanResult
import com.alpacatalk.trading.data.OandaEnvironment
import com.alpacatalk.trading.data.DirectCtraderClient
import com.alpacatalk.trading.data.MarketSnapshot
import com.alpacatalk.trading.data.OpenStopRiskCalculator
import com.alpacatalk.trading.data.OpenStopRiskSnapshot
import com.alpacatalk.trading.data.PositionCloseAvailability
import com.alpacatalk.trading.data.ScannerResultMerger
import com.alpacatalk.trading.data.ScannerBaskets
import com.alpacatalk.trading.data.BracketSubmission
import com.alpacatalk.trading.data.BrokerOrderMapper
import com.alpacatalk.trading.data.ClosedTradeReconciler
import com.alpacatalk.trading.data.JournalSnapshot
import com.alpacatalk.trading.data.JournalState
import com.alpacatalk.trading.data.ManualJournalEntry
import com.alpacatalk.trading.data.TradeDirective
import com.alpacatalk.trading.data.TradeEligibilityRules
import com.alpacatalk.trading.data.TradeJournalEngine
import com.alpacatalk.trading.data.TradeJournalSource
import com.alpacatalk.trading.data.TradeJournalStore
import com.alpacatalk.trading.data.TradeMath
import com.alpacatalk.trading.data.TradeMemoryDecision
import com.alpacatalk.trading.data.TradeOutcome
import com.alpacatalk.trading.data.TradeTicketFactory
import com.alpacatalk.trading.data.TradeGeometry
import com.alpacatalk.trading.data.PortfolioRiskPolicy
import com.alpacatalk.trading.data.NotionalExposurePolicy
import com.alpacatalk.trading.data.PositionNotional
import com.alpacatalk.trading.data.OrderNotional
import com.alpacatalk.trading.data.MarketDataQuality
import com.alpacatalk.trading.data.ClientOrderIntentStore
import com.alpacatalk.trading.data.ClientOrderRetryPolicy
import com.alpacatalk.trading.data.CredentialVault
import com.alpacatalk.trading.data.DuplicateExposurePolicy
import com.alpacatalk.trading.data.BrokerClockPolicy
import com.alpacatalk.trading.ui.ScanAlertNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

data class JournalEntry(
    val id: Long,
    val createdAt: String,
    val symbol: String,
    val setup: String,
    val notes: String,
)

data class ClosedTrade(
    val symbol: String,
    val side: String,
    val quantity: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnl: Double,
    val closedAt: String,
    val exitOrderId: String,
    val entryOrderId: String = "",
)

data class AutoScanOrderCandidate(
    val setup: MarketSnapshot,
    val capturedAtEpochMs: Long,
)

data class DirectTradingState(
    val perTradeRiskBudgetPct: Double = TradeGeometry.DEFAULT_RISK_BUDGET_PCT,
    val environment: DirectEnvironment? = null,
    val account: JsonObject? = null,
    val positions: List<JsonObject> = emptyList(),
    val pendingOrders: List<JsonObject> = emptyList(),
    val closedTrades: List<ClosedTrade> = emptyList(),
    val snapshots: List<MarketSnapshot> = emptyList(),
    val autoScanResults: List<MarketSnapshot> = emptyList(),
    val autoScanConfirmQueue: List<AutoScanOrderCandidate> = emptyList(),
    val autoScanConfirmSelectedSymbols: Set<String> = emptySet(),
    val autoScanProgress: String? = null,
    val autoScanLastCompletedAtEpochMs: Long = 0L,
    val autoScanScannedCount: Int = 0,
    val autoScanUniverseSize: Int = 0,
    val autoScanNotificationStatus: String? = null,
    val latestScanSymbols: Set<String> = emptySet(),
    val latestScanAtEpochMs: Long = 0L,
    val terminal: List<String> = emptyList(),
    val debuggerTerminal: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val autoTrading: Boolean = false,
    val scanMonitorPaused: Boolean = false,
    val scanMonitorStatus: String? = null,
    val openStopRisk: OpenStopRiskSnapshot = OpenStopRiskSnapshot.unverified("Open stop risk has not been refreshed."),
    val error: String? = null,
    val info: String? = null,
    val orderStatus: String? = null,
    val permissionWarning: String? = null,
    val selectedTradeSymbol: String? = null,
    val selectedTradeSymbols: Set<String> = emptySet(),
    val oandaEnvironment: OandaEnvironment? = null,
    val oandaAccounts: List<JsonObject> = emptyList(),
    val oandaSummary: JsonObject? = null,
    val oandaQuote: JsonObject? = null,
    val oandaEligibility: String? = null,
    val ctraderAccounts: List<JsonObject> = emptyList(),
    val ctraderConnected: Boolean = false,
    val journalEntries: List<JournalEntry> = emptyList(),
    val journal: JournalSnapshot = JournalSnapshot.EMPTY,
    val journalNotice: String? = null,
    val journalBusy: Boolean = false,
    val journalSyncedAtEpochMs: Long = 0L,
) {
    val connected: Boolean get() = environment != null || oandaEnvironment != null
    val minimumSignalScore: Int get() = 72
    fun hasFreshScanResult(symbol: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        !isScanning && latestScanAtEpochMs > 0L && nowEpochMs >= latestScanAtEpochMs &&
            nowEpochMs - latestScanAtEpochMs <= 180_000L && symbol.uppercase(Locale.US) in latestScanSymbols
}

private data class PositionCloseResult(
    val submitted: List<String> = emptyList(),
    val alreadyFlat: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)
private data class FullPositionAvailability(
    val position: JsonObject? = null,
    val error: String? = null,
    val alreadyFlat: Boolean = false,
)

/** Pure reducer: a journal close requires a filled Alpaca entry order and matching filled exit activity. */
internal object BrokerFillJournalReconciler {
    fun deriveClosedTrades(
        activities: List<JsonObject>,
        filledOrderIds: Set<String>,
        journalTickets: List<com.alpacatalk.trading.data.OpenTradeTicket> = emptyList(),
        brokerOrders: List<com.alpacatalk.trading.data.BrokerOrderRecord> = emptyList(),
    ): List<com.alpacatalk.trading.ClosedTrade> {
        data class Lot(val side: String, var quantity: Double, val price: Double, val entryOrderId: String)
        data class FillEvent(val timeMs: Long, val activity: JsonObject? = null, val seed: Lot? = null, val symbol: String = "")
        val openLots = mutableMapOf<String, MutableList<Lot>>()
        val completed = mutableListOf<com.alpacatalk.trading.ClosedTrade>()
        val ordersById = brokerOrders.associateBy { it.orderId }
        val seededTickets = journalTickets.mapNotNull { ticket ->
            val orderId = ticket.brokerOrderId ?: return@mapNotNull null
            val order = ordersById[orderId]?.takeIf { it.isFilled && it.orderId in filledOrderIds } ?: return@mapNotNull null
            val entryQuantity = minOf(ticket.quantity, order.filledQuantity)
            if (entryQuantity <= 0.0 || order.filledAveragePrice <= 0.0) return@mapNotNull null
            FillEvent(
                timeMs = order.filledAtEpochMs.takeIf { it > 0L } ?: ticket.openedAtEpochMs,
                seed = Lot(if (TradeMath.isShort(ticket.side)) "short" else "long", entryQuantity, order.filledAveragePrice, orderId),
                symbol = TradeMath.normalizeSymbol(ticket.symbol),
            )
        }
        val seededEntryOrderIds = seededTickets.mapNotNull { it.seed?.entryOrderId }.toSet()
        val fillEvents = activities.mapNotNull { activity ->
            val timeMs = activity["transaction_time"]?.jsonPrimitive?.content
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null
            FillEvent(timeMs = timeMs, activity = activity)
        }
        (fillEvents + seededTickets).sortedBy { it.timeMs }.forEach { event ->
            val seededLot = event.seed
            if (seededLot != null) {
                openLots.getOrPut(event.symbol) { mutableListOf() }.add(seededLot)
                return@forEach
            }
            val activity = event.activity ?: return@forEach
            val symbol = activity["symbol"]?.jsonPrimitive?.content?.uppercase() ?: return@forEach
            val side = activity["side"]?.jsonPrimitive?.content?.lowercase() ?: return@forEach
            val quantity = activity["qty"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
            val price = activity["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
            val activityOrderId = activity["order_id"]?.jsonPrimitive?.content ?: return@forEach
            if (activityOrderId !in filledOrderIds || quantity <= 0.0 || price <= 0.0 || (side != "buy" && side != "sell")) return@forEach
            if (activityOrderId in seededEntryOrderIds) return@forEach
            val lots = openLots.getOrPut(symbol) { mutableListOf() }
            var remaining = quantity
            val opposite = if (side == "buy") "short" else "long"
            while (remaining > 0.0000001 && lots.firstOrNull()?.side == opposite) {
                val lot = lots.first()
                val matched = minOf(remaining, lot.quantity)
                val pnl = if (lot.side == "long") (price - lot.price) * matched else (lot.price - price) * matched
                completed += com.alpacatalk.trading.ClosedTrade(
                    symbol = symbol,
                    side = lot.side.uppercase(),
                    quantity = matched,
                    entryPrice = lot.price,
                    exitPrice = price,
                    pnl = pnl,
                    closedAt = activity["transaction_time"]?.jsonPrimitive?.content ?: "Unknown time",
                    exitOrderId = activityOrderId,
                    entryOrderId = lot.entryOrderId,
                )
                remaining -= matched
                lot.quantity -= matched
                if (lot.quantity <= 0.0000001) lots.removeAt(0)
            }
            if (remaining > 0.0000001) lots.add(Lot(if (side == "buy") "long" else "short", remaining, price, activityOrderId))
        }
        return completed.sortedByDescending { it.closedAt }
    }

    fun storeVerifiedTicketClosures(
        initial: JournalState,
        brokerHistory: List<com.alpacatalk.trading.ClosedTrade>,
        brokerOrders: List<com.alpacatalk.trading.data.BrokerOrderRecord>,
        filledOrderIds: Set<String>,
    ): Pair<JournalState, List<com.alpacatalk.trading.data.ClosedTrade>> {
        var next = initial
        val closed = mutableListOf<com.alpacatalk.trading.data.ClosedTrade>()
        val ordersById = brokerOrders.associateBy { it.orderId }
        initial.openTickets.forEach { ticket ->
            val entryOrderId = ticket.brokerOrderId ?: return@forEach
            val entryOrder = ordersById[entryOrderId]
                ?.takeIf { it.isFilled && it.orderId in filledOrderIds }
                ?: return@forEach
            val expectedQuantity = minOf(ticket.quantity, entryOrder.filledQuantity)
            if (expectedQuantity <= 0.0) return@forEach
            val matchingFills = brokerHistory
                .filter { it.entryOrderId == entryOrderId && it.exitOrderId in filledOrderIds }
                .sortedBy { it.closedAt }
            var remaining = expectedQuantity
            var exitValue = 0.0
            var realizedPnl = 0.0
            var closedAt = 0L
            val exitIds = linkedSetOf<String>()
            matchingFills.forEach { fill ->
                if (remaining <= 0.0000001 || fill.quantity <= 0.0) return@forEach
                val usedQuantity = minOf(remaining, fill.quantity)
                exitValue += fill.exitPrice * usedQuantity
                realizedPnl += fill.pnl * (usedQuantity / fill.quantity)
                val fillTime = runCatching { Instant.parse(fill.closedAt).toEpochMilli() }.getOrDefault(0L)
                closedAt = maxOf(closedAt, fillTime)
                exitIds += fill.exitOrderId
                remaining -= usedQuantity
            }
            // Partial exits stay pending; no estimated or current quote is ever written as a close.
            if (remaining > 0.0000001) return@forEach
            if (exitIds.isEmpty() || exitIds.any { it !in filledOrderIds }) return@forEach

            val actualEntry = entryOrder.filledAveragePrice.takeIf { it > 0.0 } ?: ticket.entryPrice
            val averageExit = exitValue / expectedQuantity
            val realizedOutcome = when {
                realizedPnl > 0.005 -> TradeOutcome.WIN
                realizedPnl < -0.005 -> TradeOutcome.LOSS
                else -> TradeOutcome.BREAKEVEN
            }
            val actualTicket = ticket.copy(
                entryPrice = actualEntry,
                quantity = expectedQuantity,
                stopPips = TradeMath.stopPips(ticket.symbol, actualEntry, ticket.stopPrice),
                targetPips = TradeMath.targetPips(ticket.symbol, actualEntry, ticket.targetPrice),
                riskReward = TradeMath.riskReward(actualEntry, ticket.stopPrice, ticket.targetPrice),
            )
            val trade = TradeTicketFactory.close(
                ticket = actualTicket,
                exitPrice = averageExit,
                closedAtEpochMs = closedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                source = TradeJournalSource.BROKER_SYNC,
                outcomeOverride = realizedOutcome,
                realizedPnlOverride = realizedPnl,
                brokerOrderId = entryOrderId,
                tradeIdOverride = "broker-fill-${ticket.ticketId}",
                notes = "Verified against Alpaca filled entry order $entryOrderId and filled exit order(s) ${exitIds.joinToString()}; the stored exit is the quantity-weighted actual fill price.",
            )
            next = TradeJournalEngine.removeTicket(TradeJournalEngine.recordTrade(next, trade), ticket.ticketId)
            closed += trade
        }
        return next to closed
    }
}

class DirectTradingViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val riskPreferences = appContext.getSharedPreferences("fin_killer_risk_preferences", android.content.Context.MODE_PRIVATE)
    private var perTradeRiskBudgetPct = riskPreferences.getFloat("per_trade_risk_pct", TradeGeometry.DEFAULT_RISK_BUDGET_PCT.toFloat()).toDouble()
        .coerceIn(0.001, TradeGeometry.HARD_MAX_RISK_BUDGET_PCT)
    private val _state = MutableStateFlow(DirectTradingState(perTradeRiskBudgetPct = perTradeRiskBudgetPct))
    val state: StateFlow<DirectTradingState> = _state.asStateFlow()
    private var journalStore = TradeJournalStore(appContext, "paper")
    private var journalState: JournalState = JournalState()
    private val orderIntentStore = ClientOrderIntentStore(appContext)

    private var apiKey: String? = null
    private var apiSecret: String? = null
    private var oandaToken: String? = null
    private var ctraderClientId: String? = null
    private var ctraderClientSecret: String? = null
    private var ctraderToken: String? = null
    private var ctraderLive: Boolean = false
    private var autoJob: Job? = null
    private var accountRefreshJob: Job? = null
    private var lastOrderSymbol: String? = null
    private var lastOrderAtMs: Long = 0L

    private fun switchJournalScope(mode: String, accountKey: String) {
        journalStore = TradeJournalStore(appContext, mode, accountKey)
        journalState = journalStore.load()
        _state.update { it.copy(journal = journalStore.snapshot(journalState), journalNotice = journalStore.lastError) }
    }

    init {
        journalState = journalStore.load()
        _state.update { it.copy(journal = journalStore.snapshot(journalState), journalNotice = journalStore.lastError) }
    }

    fun setPerTradeRiskBudgetPct(value: Double) {
        if (!value.isFinite()) return
        perTradeRiskBudgetPct = value.coerceIn(0.001, TradeGeometry.HARD_MAX_RISK_BUDGET_PCT)
        riskPreferences.edit().putFloat("per_trade_risk_pct", perTradeRiskBudgetPct.toFloat()).apply()
        _state.update { it.copy(perTradeRiskBudgetPct = perTradeRiskBudgetPct) }
    }

    fun connect(apiKeyInput: CharArray, apiSecretInput: CharArray) {
        val key = apiKeyInput.concatToString()
        val secret = apiSecretInput.concatToString()
        apiKeyInput.fill('\u0000')
        apiSecretInput.fill('\u0000')

        viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    info = null,
                    terminal = listOf(activity("AUTH", "Probing official Paper and Live account endpoints.")),
                    debuggerTerminal = listOf(debug("AUTH", "Environment probe started; credentials are intentionally never logged.")),
                )
            }
            try {
                val environment = DirectEnvironment.detect(key, secret)
                apiKey = key
                apiSecret = secret
                val account = environment.account(key, secret)
                val accountId = account["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Alpaca did not return an account ID; journal isolation cannot be established.")
                switchJournalScope(if (environment == DirectEnvironment.PAPER) "paper" else "live", "alpaca:${environment.name}:$accountId")
                val positions = environment.positions(key, secret)
                val pendingOrders = environment.openOrders(key, secret)
                val closedTrades = runCatching {
                    val filledOrderIds = environment.closedOrders(key, secret)
                        .filter { it["status"]?.jsonPrimitive?.content?.equals("filled", ignoreCase = true) == true }
                        .mapNotNull { it["id"]?.jsonPrimitive?.content }
                        .toSet()
                    deriveClosedTrades(environment.fillActivities(key, secret), filledOrderIds)
                }.getOrDefault(emptyList())
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                _state.value = DirectTradingState(
                    environment = environment,
                    account = account,
                    positions = positions,
                    pendingOrders = pendingOrders,
                    closedTrades = closedTrades,
                    terminal = listOf(
                        activity("AUTH", "Authenticated to ${environment.label} in ${elapsedMs}ms."),
                        activity("READY", "Direct Alpaca mode online; account snapshot, ${positions.size} position(s), and ${pendingOrders.size} pending order(s) loaded."),
                    ),
                    debuggerTerminal = listOf(
                        debug("AUTH", "Environment detection resolved to ${environment.label}; elapsed=${elapsedMs}ms."),
                        debug("ACCOUNT", "Read account snapshot, ${positions.size} open position record(s), and ${pendingOrders.size} pending order record(s); credentials were not logged."),
                    ),
                    info = "Connected directly to ${environment.label}.",
                    journalEntries = listOf(journalEntry("ACCOUNT", "Connection", "Connected to ${environment.label}; loaded ${positions.size} position(s) and ${pendingOrders.size} pending order(s).")),
                    journal = journalStore.snapshot(journalState),
                    journalNotice = journalStore.lastError,
                )
                startAccountRefreshLoop()
            } catch (error: Exception) {
                apiKey = null
                apiSecret = null
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                val message = error.message ?: "Alpaca connection failed."
                _state.value = DirectTradingState(
                    error = message,
                    terminal = listOf(activity("AUTH ERROR", "Connection failed after ${elapsedMs}ms: $message")),
                    debuggerTerminal = listOf(debug("AUTH ERROR", "Endpoint detection failed after ${elapsedMs}ms: $message; credentials were not logged.")),
                    journalEntries = listOf(journalEntry("ACCOUNT", "Connection error", message)),
                    journal = journalStore.snapshot(journalState),
                    journalNotice = journalStore.lastError,
                )
            }
        }
    }

    fun probeOanda(tokenInput: CharArray, environment: OandaEnvironment) {
        val token = tokenInput.concatToString()
        tokenInput.fill('\u0000')
        viewModelScope.launch {
            try {
                val accounts = environment.accounts(token)
                oandaToken = token
                _state.update { it.copy(oandaAccounts = accounts, oandaEnvironment = null, error = null, info = "OANDA session valid. Select an account.", terminal = appendActivity(it, listOf("OANDA AUTH" to "${environment.label} token accepted; accounts=${accounts.size}."))) }
            } catch (error: Exception) {
                oandaToken = null
                _state.update { it.copy(error = error.message ?: "OANDA connection failed.", terminal = appendActivity(it, listOf("OANDA AUTH ERROR" to (error.message ?: "connection failed")))) }
            }
        }
    }

    fun selectOandaAccount(accountId: String, environment: OandaEnvironment) {
        val token = oandaToken ?: return
        viewModelScope.launch {
            try {
                val summary = environment.summary(token, accountId)
                val eligibility = environment.xauUsdEligibility(token, accountId)
                val quote = environment.pricing(token, accountId)
                switchJournalScope(if (environment.name.contains("LIVE", ignoreCase = true)) "live" else "paper", "oanda:${environment.name}:$accountId")
                _state.update { it.copy(oandaEnvironment = environment, oandaSummary = summary, oandaQuote = quote, oandaEligibility = eligibility, oandaAccounts = listOf(buildJsonObject { put("id", accountId) }), info = "Connected directly to ${environment.label}; XAU_USD checked.", terminal = appendActivity(it, listOf("OANDA READY" to "account=$accountId; $eligibility"))) }
            } catch (error: Exception) { _state.update { it.copy(error = error.message ?: "OANDA account selection failed.") } }
        }
    }

    fun tradeOandaXau(units: String, stop: String, target: String) {
        val token = oandaToken ?: return
        val environment = _state.value.oandaEnvironment ?: return
        if (environment != OandaEnvironment.PRACTICE) {
            _state.update { it.copy(orderStatus = "Execution blocked: this build is paper-only; no live OANDA order was sent.") }
            return
        }
        val accountId = _state.value.oandaSummary?.get("id")?.jsonPrimitive?.content ?: return
        viewModelScope.launch {
            try {
                val result = environment.marketOrder(token, accountId, units, stop.toDoubleOrNull(), target.toDoubleOrNull())
                val journalLine = journalOandaTicket(environment, units, stop, target)
                _state.update {
                    it.copy(
                        orderStatus = "OANDA order submitted; broker response received.",
                        journal = journalStore.snapshot(journalState),
                        terminal = appendActivity(it, listOf("OANDA ORDER" to "XAU_USD units=$units; responseKeys=${result.keys.joinToString()}") + listOfNotNull(journalLine)),
                    )
                }
            } catch (error: Exception) { _state.update { it.copy(orderStatus = "OANDA order rejected: ${error.message}") } }
        }
    }

    fun beginCtraderAuthorization(clientIdInput: CharArray, clientSecretInput: CharArray, live: Boolean, openAuthorization: (String) -> Unit) {
        val clientId = clientIdInput.concatToString()
        val clientSecret = clientSecretInput.concatToString()
        clientIdInput.fill('\u0000'); clientSecretInput.fill('\u0000')
        ctraderClientId = clientId; ctraderClientSecret = clientSecret; ctraderLive = live
        _state.update { it.copy(info = "Opening cTrader account authorization…", error = null) }
        openAuthorization(DirectCtraderClient.authorizationUrl(clientId, java.util.UUID.randomUUID().toString()))
    }

    fun completeCtraderAuthorization(code: String) {
        val clientId = ctraderClientId ?: return
        val clientSecret = ctraderClientSecret ?: return
        viewModelScope.launch {
            try {
                val token = DirectCtraderClient.exchangeCode(clientId, clientSecret, code)
                ctraderToken = token.accessToken
                val accounts = DirectCtraderClient.accounts(clientId, clientSecret, token.accessToken, ctraderLive)
                _state.update { it.copy(ctraderAccounts = accounts, ctraderConnected = false, error = null, info = "cTrader login complete. Select one authorized trading account.", terminal = appendActivity(it, listOf("CTRADER AUTH" to "OAuth login complete; authorized accounts=${accounts.size}."))) }
            } catch (error: Exception) { _state.update { it.copy(error = error.message ?: "cTrader authorization failed.") } }
        }
    }

    fun selectCtraderAccount(account: JsonObject) {
        val id = account["ctidTraderAccountId"]?.jsonPrimitive?.content ?: account["accountId"]?.jsonPrimitive?.content ?: return
        val clientId = ctraderClientId ?: return
        val clientSecret = ctraderClientSecret ?: return
        val token = ctraderToken ?: return
        viewModelScope.launch {
            try {
                val authorized = DirectCtraderClient.authorizeAccount(clientId, clientSecret, token, id, ctraderLive)
                if (!authorized) throw IllegalStateException("cTrader rejected account authorization.")
                switchJournalScope(if (ctraderLive) "live" else "paper", "ctrader:${if (ctraderLive) "live" else "demo"}:$id")
                _state.update { it.copy(ctraderConnected = true, info = "cTrader account $id authorized.", terminal = appendActivity(it, listOf("CTRADER READY" to "Authorized cTrader trading account $id."))) }
            } catch (error: Exception) { _state.update { it.copy(error = error.message ?: "cTrader account authorization failed.") } }
        }
    }

    fun scanMarkets(symbolsInput: String) {
        val symbols = parseSymbols(symbolsInput)
        if (symbols.isEmpty()) {
            _state.update { it.copy(error = "Enter at least one market symbol.") }
            return
        }
        viewModelScope.launch { scanMarketsNow(symbols) }
    }

    fun selectTradeSymbol(symbol: String) {
        val current = _state.value
        val snapshot = current.snapshots.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } ?: return
        if (!current.hasFreshScanResult(snapshot.symbol)) {
            _state.update { it.copy(orderStatus = "Selection blocked: rescan ${snapshot.symbol} before selecting it; scan results older than three minutes are not executable.") }
            return
        }
        val environment = current.environment ?: return
        val assessment = TradeEligibilityRules.evaluate(snapshot, environment, current.journal, current.minimumSignalScore)
        if (!assessment.eligible) {
            _state.update { it.copy(orderStatus = "Selection blocked: ${snapshot.symbol} does not currently pass the app rating, memory-adjusted score, and setup eligibility gates.") }
            return
        }
        val selected = current.selectedTradeSymbols.toMutableSet()
        if (!selected.add(symbol)) selected.remove(symbol)
        _state.update {
            it.copy(
                selectedTradeSymbols = selected,
                selectedTradeSymbol = selected.firstOrNull(),
                info = if (symbol in selected) "Selected ${selected.size} symbol(s) for trading." else "Removed $symbol from the trading selection.",
                terminal = appendActivity(it, listOf("SELECT" to "${if (symbol in selected) "Added" else "Removed"} $symbol; selected=${selected.joinToString(",")}.")),
                debuggerTerminal = appendDebug(it, listOf("SELECT" to "tradeSymbols=${selected.joinToString(",")}; threshold=${it.minimumSignalScore}.")),
            )
        }
    }

    fun toggleAutoTrading() {
        if (_state.value.autoTrading) {
            stopAutoTrading("Scan monitor stopped by user. No orders were submitted by the monitor.")
            return
        }
        val symbols = ScannerBaskets.allSymbols
        val environment = _state.value.environment
        if (symbols.isEmpty()) {
            _state.update { it.copy(error = "Enter at least one market to start the scan monitor.") }
            return
        }
        if (environment == null) {
            _state.update { it.copy(error = "Connect to an account before starting the scan monitor.") }
            return
        }
        val key = apiKey
        val secret = apiSecret
        if (key.isNullOrBlank() || secret.isNullOrBlank()) {
            _state.update { it.copy(error = "Reconnect to the Paper account before starting the scan monitor.") }
            return
        }
        if (!CredentialVault(appContext).has(BackgroundScanService.ALPACA_KEY)) {
            _state.update { it.copy(error = "Save Alpaca credentials with Remember credentials enabled before starting a background monitor.") }
            return
        }
        appContext.getSharedPreferences(BackgroundScanService.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(BackgroundScanService.KEY_ENABLED, true)
            .putString(BackgroundScanService.KEY_ENVIRONMENT, environment.name)
            .apply()
        _state.update {
            it.copy(
                autoTrading = true,
                scanMonitorPaused = true,
                scanMonitorStatus = "Waiting for a fresh, broker-verified stop-risk snapshot.",
                autoScanResults = emptyList(),
                autoScanProgress = "Waiting for a fresh risk check before scanning all ${symbols.size} unique symbols.",
                autoScanLastCompletedAtEpochMs = 0L,
                autoScanScannedCount = 0,
                autoScanUniverseSize = symbols.size,
                autoScanNotificationStatus = "Waiting for the first complete all-basket sweep; one combined notification will be sent for all qualifying findings.",
                error = null,
                info = "Scan monitor started; verifying open-position stop risk before scanning. It will not submit or modify orders.",
                terminal = appendActivity(it, listOf("SCAN MONITOR" to "Started read-only full-universe scan/alert monitoring; interval=60s after each sweep; uniqueSymbols=${symbols.size}; one combined notification per sweep.")),
                debuggerTerminal = appendDebug(it, listOf("SCAN MONITOR" to "Started; ${symbols.size} unique symbols across all baskets; environment=${environment.label}; requestChunkSize=10; concurrentChunks=${ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES}; automaticOrderSubmission=false.")),
            )
        }
        autoJob?.cancel()
        autoJob = null
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, BackgroundScanService::class.java).apply { action = BackgroundScanService.ACTION_START })
        }.onFailure { error ->
            appContext.getSharedPreferences(BackgroundScanService.PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(BackgroundScanService.KEY_ENABLED, false).apply()
            _state.update { it.copy(autoTrading = false, error = "Could not start background monitor: ${error.message ?: "foreground service unavailable"}") }
        }
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            while (isActive && _state.value.autoTrading) {
                val risk = _state.value.openStopRisk
                val pauseReason = when {
                    !risk.isFresh() -> risk.issue ?: "The broker stop-risk snapshot is stale; waiting for refresh."
                    risk.capReached -> "Open downside risk ${format(risk.downsideRiskDollars ?: 0.0)} USD has reached the 10% equity cap (${format(risk.riskCapDollars ?: 0.0)} USD). Trail a stop manually and wait for Alpaca verification."
                    else -> null
                }
                if (pauseReason != null) {
                    _state.update {
                        it.copy(
                            scanMonitorPaused = true,
                            scanMonitorStatus = pauseReason,
                            autoScanProgress = "Paused before scanning; waiting for verified risk to fall below the cap.",
                            info = "Scan alerts paused: $pauseReason",
                        )
                    }
                    delay(10_000)
                } else {
                    _state.update {
                        it.copy(
                            scanMonitorPaused = false,
                            scanMonitorStatus = "Verified open downside risk ${format(risk.downsideRiskDollars ?: 0.0)} USD / ${format(risk.riskCapDollars ?: 0.0)} USD cap; all-basket sweep starting.",
                        )
                    }
                    val sweepStartedAtMs = System.currentTimeMillis()
                    val batches = ScannerBaskets.scanBatches()
                    val resultsByBatch = arrayOfNulls<BatchScanResult>(batches.size)
                    val completedBatchCount = AtomicInteger(0)
                    val completedSymbolCount = AtomicInteger(0)
                    _state.update {
                        it.copy(
                            scanMonitorPaused = false,
                            autoScanScannedCount = 0,
                            autoScanProgress = "Scanning all ${symbols.size} unique markets in one sweep · up to ${ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES} requests in parallel",
                            scanMonitorStatus = "One complete all-basket sweep is running; results and alerts will be combined when it finishes.",
                        )
                    }
                    val semaphore = Semaphore(ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES)
                    coroutineScope {
                        batches.mapIndexed { index, batch ->
                            async(Dispatchers.Default) {
                                semaphore.withPermit {
                                    if (!isActive || !_state.value.autoTrading) return@withPermit
                                    val batchStartedAtMs = System.currentTimeMillis()
                                    val result = try {
                                        environment.scanMarkets(key, secret, batch, includeControlledDiagnostics = false)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        BatchScanResult(
                                            snapshots = emptyList(),
                                            debugLogs = listOf("[SWEEP BATCH FAILED] ${error.message ?: error.javaClass.simpleName}"),
                                            requestedSymbols = batch,
                                            elapsedMs = System.currentTimeMillis() - batchStartedAtMs,
                                        )
                                    }
                                    resultsByBatch[index] = result
                                    val batchNumber = completedBatchCount.incrementAndGet()
                                    val doneSymbols = completedSymbolCount.addAndGet(batch.size)
                                    _state.update {
                                        it.copy(
                                            autoScanScannedCount = doneSymbols.coerceAtMost(symbols.size),
                                            autoScanProgress = "All-basket sweep in progress · ${doneSymbols.coerceAtMost(symbols.size)}/${symbols.size} symbols · $batchNumber/${batches.size} request groups complete",
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    if (!_state.value.autoTrading || !isActive) break

                    val completedResults = resultsByBatch.mapIndexedNotNull { index, result ->
                        result?.takeUnless { batchResult -> batchResult.debugLogs.any { it.startsWith("[SWEEP BATCH FAILED]") } }
                    }
                    val failedBatchCount = batches.size - completedResults.size
                    val scannedCount = completedResults.sumOf { it.requestedSymbols.size }
                    val allBatchResults = resultsByBatch.filterNotNull()
                    val completeResults = ScannerResultMerger.merge(emptyList(), completedResults.flatMap { it.snapshots }).sortedByDescending { it.score }
                    val sweepElapsedMs = System.currentTimeMillis() - sweepStartedAtMs
                    val finalState = _state.value
                    val capturedAt = System.currentTimeMillis()
                    val qualifying = completeResults.filter { setup ->
                        TradeEligibilityRules.evaluate(setup, environment, finalState.journal, finalState.minimumSignalScore).eligible
                    }
                    val newCandidates = qualifying.map { AutoScanOrderCandidate(it, capturedAt) }
                    val candidateDetails = newCandidates.map { candidate ->
                        val assessment = TradeEligibilityRules.evaluate(candidate.setup, environment, finalState.journal, finalState.minimumSignalScore)
                        "${candidate.setup.symbol} · ${candidate.setup.direction?.label ?: "NO CLEAR SIDE"} · scan ${candidate.setup.score}/100 · app ${assessment.appRating}/100 · ${candidate.setup.setup}"
                    }
                    val notificationPosted = ScanAlertNotifications.showQualifyingSetups(
                        getApplication(), details = candidateDetails, scannedCount = scannedCount, totalQualifyingCount = qualifying.size, includeSubmitAllAction = true,
                    )
                    val sweepComplete = failedBatchCount == 0 && scannedCount == symbols.size
                    val notificationStatus = when {
                        qualifying.isEmpty() -> if (sweepComplete) "Full-universe sweep complete; no candidates passed every alert gate, so no notification was needed." else "Partial sweep: $scannedCount/${symbols.size} symbols completed; no qualifying candidates found in completed chunks. Retrying."
                        !ScanAlertNotifications.hasPermission(getApplication()) -> "$scannedCount markets scanned; ${qualifying.size} candidates found, but Android notification permission is off."
                        !ScanAlertNotifications.notificationsEnabled(getApplication()) -> "$scannedCount markets scanned; ${qualifying.size} candidates found, but Android notifications are disabled."
                        notificationPosted -> "One consolidated notification sent for ${qualifying.size} candidate(s) across all baskets."
                        else -> "${qualifying.size} candidates found, but Android did not accept the consolidated notification; check notification settings."
                    }
                    val batchSummary = allBatchResults.mapIndexed { index, result ->
                        "group=${index + 1}/${batches.size}; symbols=${result.requestedSymbols.size}; findings=${result.snapshots.size}; apiElapsedMs=${result.elapsedMs}; failed=${result.debugLogs.any { it.startsWith("[SWEEP BATCH FAILED]") }}"
                    }
                    _state.update {
                        val queuedBySymbol = it.autoScanConfirmQueue.associateBy { candidate -> candidate.setup.symbol }.toMutableMap()
                        newCandidates.forEach { candidate -> queuedBySymbol[candidate.setup.symbol] = candidate }
                        it.copy(
                            autoScanResults = completeResults,
                            autoScanConfirmQueue = queuedBySymbol.values.sortedByDescending { candidate -> candidate.capturedAtEpochMs },
                            autoScanConfirmSelectedSymbols = it.autoScanConfirmSelectedSymbols.intersect(queuedBySymbol.keys),
                            autoScanLastCompletedAtEpochMs = if (sweepComplete) System.currentTimeMillis() else it.autoScanLastCompletedAtEpochMs,
                            autoScanScannedCount = scannedCount,
                            autoScanProgress = if (sweepComplete) "Full-universe sweep complete · $scannedCount/${symbols.size} symbols · ${completeResults.size} findings · next full sweep in 60 seconds" else "Partial sweep · $scannedCount/${symbols.size} symbols completed; $failedBatchCount request group(s) failed · retrying in 10 seconds",
                            autoScanNotificationStatus = notificationStatus,
                            scanMonitorStatus = if (sweepComplete) "All ${symbols.size} unique symbols scanned together; ${qualifying.size} eligible candidates found." else "Partial all-basket sweep: $scannedCount/${symbols.size} symbols succeeded; $failedBatchCount chunk(s) failed.",
                            debuggerTerminal = appendDebug(it, listOf("AUTO SCAN SWEEP" to "symbols=$scannedCount/${symbols.size}; chunks=${batches.size}; concurrency=${ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES}; findings=${completeResults.size}; eligible=${qualifying.size}; queued=${queuedBySymbol.size}; notifications=one-consolidated; elapsedMs=$sweepElapsedMs; ordersSubmitted=false.") + batchSummary.map { summary -> "BATCH" to summary }),
                            terminal = appendActivity(it, listOf("AUTO SCAN SWEEP" to "${if (sweepComplete) "Completed" else "Partially completed"} one all-basket sweep: $scannedCount/${symbols.size} symbols; ${qualifying.size} eligible candidates; ${if (notificationPosted) "one consolidated notification sent" else "no notification sent"}; elapsed=${sweepElapsedMs}ms; ordersSubmitted=false.")),
                        )
                    }
                    delay(if (sweepComplete) 60_000 else 10_000)
                }
            }
        }
    }

    private suspend fun scanMarketsNow(
        symbols: List<String>,
        preserveScannerState: Boolean = false,
        autoScanBatch: Boolean = false,
    ): List<MarketSnapshot> {
        val environment = _state.value.environment ?: return emptyList()
        val key = apiKey ?: return emptyList()
        val secret = apiSecret ?: return emptyList()
        val startedAtMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                isScanning = true,
                error = null,
                info = "Scanning ${symbols.size} markets on ${environment.label}...",
                snapshots = if (preserveScannerState || autoScanBatch) it.snapshots else emptyList(),
                latestScanSymbols = if (autoScanBatch) it.latestScanSymbols else emptySet(),
                latestScanAtEpochMs = if (autoScanBatch) it.latestScanAtEpochMs else 0L,
                selectedTradeSymbol = if (preserveScannerState) it.selectedTradeSymbol else null,
                selectedTradeSymbols = if (preserveScannerState) it.selectedTradeSymbols else emptySet(),
                permissionWarning = null,
                terminal = appendActivity(
                    it,
                    listOf(
                        "SCAN" to "Started ${symbols.size}-market scan on ${environment.label}: ${symbols.joinToString(", ")}.",
                        "SCAN PLAN" to "Stocks request 1Day, 1Hour, 15Min, 1Min OHLCV plus latest quotes; forex requests supported rates then derives 15Min/1Hour locally.",
                    ),
                ),
                debuggerTerminal = appendDebug(
                    it,
                    listOf(
                        "SCAN" to "Scan started; requestedSymbols=${symbols.joinToString(",")}; environment=${environment.label}.",
                        "SAFETY" to "Diagnostic output includes endpoint, status, timing, payload metadata, parser counts, and setup checks; it excludes credentials.",
                    ),
                ),
            )
        }

        val results = mutableListOf<MarketSnapshot>()
        try {
            val batch = environment.scanMarkets(key, secret, symbols)
            results += batch.snapshots
            val grantWarning = batch.debugLogs.firstOrNull { it.contains("insufficient grants", ignoreCase = true) }
            val resultActivity = results.map(::activityForSnapshot)
            val resultDiagnostics = results.flatMap(::debugForSnapshot)
            _state.update {
                it.copy(
                    snapshots = when {
                        autoScanBatch -> it.snapshots
                        preserveScannerState -> ScannerResultMerger.merge(it.snapshots, results)
                        else -> results.toList()
                    },
                    terminal = appendActivity(
                        it,
                        listOf("SCAN" to "Data collection completed in ${batch.elapsedMs}ms; usable setups=${results.size}/${symbols.size}.") +
                            resultActivity.map { "RESULT" to it },
                    ),
                    debuggerTerminal = appendDebug(
                        it,
                        listOf("SCAN" to "Client scan elapsed=${batch.elapsedMs}ms; requested=${batch.requestedSymbols.joinToString(",")}; snapshots=${results.size}.") +
                            batch.debugLogs.map { "DATA" to it } +
                            resultDiagnostics.map { "SETUP" to it },
                    ),
                    permissionWarning = grantWarning?.let {
                        "Alpaca denied this data request: insufficient grants. Enable the required market-data permission in Alpaca, then reconnect with a newly generated key."
                    },
                    journalEntries = appendAutomaticJournal(it, "SCAN", "Market scan", "${results.size}/${symbols.size} usable setup(s) returned in ${batch.elapsedMs}ms."),
                )
            }
        } catch (error: CancellationException) {
            _state.update { it.copy(isScanning = false) }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "market-data batch failed"
            _state.update {
                it.copy(
                    debuggerTerminal = appendDebug(it, listOf("FATAL" to "Market scan failed: $message")),
                    terminal = appendActivity(it, listOf("SCAN ERROR" to "Market-data batch failed: $message")),
                    journalEntries = appendAutomaticJournal(it, "SCAN", "Market scan error", message),
                )
            }
        }

        val best = results.maxByOrNull { it.score }
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val finalActivity = if (best == null) {
            "SCAN DONE" to "Completed in ${elapsedMs}ms with no usable setup."
        } else {
            "BEST" to "${best.symbol} selected at ${best.score}/100; setup=${best.setup}; price=${format(best.lastPrice)}."
        }
        _state.update {
            val summary = when {
                best != null -> "Best setup: ${best.symbol} (${best.score}/100)"
                it.permissionWarning != null -> it.permissionWarning
                else -> "No usable setups found."
            }
            it.copy(
                isScanning = false,
                info = if (autoScanBatch) it.info else summary,
                latestScanSymbols = if (autoScanBatch) it.latestScanSymbols else results.map { it.symbol.uppercase(Locale.US) }.toSet(),
                latestScanAtEpochMs = if (autoScanBatch) it.latestScanAtEpochMs else if (results.isNotEmpty()) System.currentTimeMillis() else 0L,
                terminal = appendActivity(
                    it,
                    listOf(finalActivity),
                ),
                debuggerTerminal = appendDebug(
                    it,
                    listOf("SCAN DONE" to "Total view-model elapsed=${elapsedMs}ms; best=${best?.symbol ?: "none"}; score=${best?.score ?: 0}."),
                ),
                journalEntries = appendAutomaticJournal(it, best?.symbol ?: "SCAN", "Scan complete", finalActivity.second),
            )
        }
        if (autoScanBatch) return results.toList()
        val alertState = _state.value
        val qualifying = results.filter { setup ->
            TradeEligibilityRules.evaluate(setup, environment, alertState.journal, alertState.minimumSignalScore).eligible
        }
        val alertDetails = qualifying.map { setup ->
            val assessment = TradeEligibilityRules.evaluate(setup, environment, alertState.journal, alertState.minimumSignalScore)
            "${setup.symbol} · ${setup.direction?.label ?: "NO CLEAR SIDE"} · scan ${setup.score}/100 · app ${assessment.appRating}/100 · adjusted ${assessment.adjustedScore}/100 · ${setup.setup}"
        }
        val notificationPosted = ScanAlertNotifications.showQualifyingSetups(getApplication(), alertDetails, symbols.size)
        _state.update {
            it.copy(
                debuggerTerminal = appendDebug(
                    it,
                    listOf("SCAN ALERT" to "fresh=${results.size}; executionQualified=${qualifying.size}; notificationPosted=$notificationPosted; ordersSubmitted=false."),
                ),
            )
        }
        return results.toList()
    }

    fun executeBestSetup() {
        viewModelScope.launch { executeBestSetupNow() }
    }

    fun executeSelectedSet() {
        val selected = _state.value.selectedTradeSymbols.toList()
        if (selected.isEmpty()) {
            _state.update { it.copy(orderStatus = "Execution blocked: select at least one qualifying symbol first.") }
            return
        }
        viewModelScope.launch {
            val riskBudgetPct = _state.value.perTradeRiskBudgetPct
            val batchRiskPct = riskBudgetPct * selected.size
            selected.forEach { symbol -> executeBestSetupNow(requestedSymbol = symbol, requestedBatchRiskPct = batchRiskPct, requestedRiskBudgetPct = riskBudgetPct) }
        }
    }

    fun toggleAutoScanCandidateSelection(symbol: String) {
        val normalized = symbol.trim().uppercase(Locale.US)
        _state.update { current ->
            val candidate = current.autoScanConfirmQueue.firstOrNull { it.setup.symbol == normalized }
            when {
                candidate == null -> current.copy(orderStatus = "$normalized is no longer in Confirm orders.")
                System.currentTimeMillis() - candidate.capturedAtEpochMs !in 0..180_000L -> current.copy(orderStatus = "$normalized scan result expired; wait for its next scan before confirming.")
                normalized in current.autoScanConfirmSelectedSymbols -> current.copy(autoScanConfirmSelectedSymbols = current.autoScanConfirmSelectedSymbols - normalized)
                else -> current.copy(autoScanConfirmSelectedSymbols = current.autoScanConfirmSelectedSymbols + normalized)
            }
        }
    }

    fun dismissAutoScanCandidate(symbol: String) {
        val normalized = symbol.trim().uppercase(Locale.US)
        _state.update {
            it.copy(
                autoScanConfirmQueue = it.autoScanConfirmQueue.filterNot { candidate -> candidate.setup.symbol == normalized },
                autoScanConfirmSelectedSymbols = it.autoScanConfirmSelectedSymbols - normalized,
            )
        }
    }

    fun executeAutoScanSelected() {
        val current = _state.value
        val selected = current.autoScanConfirmSelectedSymbols.toSet()
        if (selected.isEmpty()) {
            _state.update { it.copy(orderStatus = "No auto-scan orders selected; nothing was submitted.") }
            return
        }
        if (current.isLoading) {
            _state.update { it.copy(orderStatus = "Wait for the current account/order request to finish before confirming orders.") }
            return
        }
        val candidates = current.autoScanConfirmQueue.filter { it.setup.symbol in selected }
        viewModelScope.launch {
            val riskBudgetPct = _state.value.perTradeRiskBudgetPct
            val batchRiskPct = riskBudgetPct * candidates.size
            candidates.forEach { candidate -> executeBestSetupNow(autoCandidate = candidate, requestedBatchRiskPct = batchRiskPct, requestedRiskBudgetPct = riskBudgetPct) }
        }
    }

    private suspend fun executeBestSetupNow(
        requestedSymbol: String? = null,
        autoCandidate: AutoScanOrderCandidate? = null,
        requestedBatchRiskPct: Double = _state.value.perTradeRiskBudgetPct,
        requestedRiskBudgetPct: Double = _state.value.perTradeRiskBudgetPct,
    ) {
        val environment = _state.value.environment ?: return
        val key = apiKey ?: return
        val secret = apiSecret ?: return
        val setup = autoCandidate?.setup ?: _state.value.snapshots.firstOrNull { it.symbol == (requestedSymbol ?: _state.value.selectedTradeSymbol) } ?: run {
            _state.update {
                it.copy(
                    orderStatus = "Execution blocked: select a qualifying symbol first.",
                    terminal = appendActivity(it, listOf("GUARD" to "No selected qualifying symbol; no order sent.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "Execution requires explicit symbol selection from the filtered results.")),
                )
            }
            return
        }
        val autoCandidateAge = autoCandidate?.let { System.currentTimeMillis() - it.capturedAtEpochMs }
        val hasFreshSource = if (autoCandidate != null) {
            autoCandidateAge != null && autoCandidateAge in 0..180_000L
        } else {
            _state.value.hasFreshScanResult(setup.symbol)
        }
        if (environment == DirectEnvironment.LIVE) {
            _state.update {
                it.copy(
                    orderStatus = "Execution blocked: this build is paper-only; no order request was sent to a live trading endpoint.",
                    terminal = appendActivity(it, listOf("GUARD" to "Paper-only execution boundary blocked ${setup.symbol} before any live order or account-risk request.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "LIVE environment is ineligible in this build; no live order API call was made.")),
                )
            }
            return
        }
        if (!hasFreshSource) {
            _state.update {
                it.copy(
                    orderStatus = "Execution blocked: ${setup.symbol} scan result is older than three minutes. Wait for a fresh scan and review it before execution.",
                    terminal = appendActivity(it, listOf("GUARD" to "${setup.symbol} setup is stale or missing from the latest scan; no order sent.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "Fresh-scan guard failed for ${setup.symbol}; autoCandidate=${autoCandidate != null}; latestScanAt=${it.latestScanAtEpochMs}; no order sent.")),
                )
            }
            return
        }
        if (setup.direction == null) {
            _state.update {
                it.copy(
                    orderStatus = "Execution blocked: ${setup.symbol} has mixed or ambiguous LONG/SHORT signals; no side was selected and no order was sent.",
                    terminal = appendActivity(it, listOf("GUARD" to "${setup.symbol} has conflicting/no clear directional signals; no order sent.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "Directional conflict for ${setup.symbol}; execution fail-closed.")),
                )
            }
            return
        }
        if (setup.symbol.contains('/')) {
            _state.update {
                it.copy(
                    orderStatus = "Data-only asset: Forex execution is not routed through the stock order endpoint.",
                    terminal = appendActivity(it, listOf("GUARD" to "${setup.symbol} analyzed but not submitted because forex order routing is unavailable.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "Forex symbol ${setup.symbol} intentionally excluded from /v2/orders submission.")),
                )
            }
            return
        }
        if (!setup.dataQualityPassed) {
            _state.update {
                it.copy(
                    orderStatus = "Execution blocked: market-data quality failed for ${setup.symbol}: ${setup.dataQualityReason}",
                    terminal = appendActivity(it, listOf("GUARD" to "${setup.symbol} failed market-data quality; no order sent.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "dataQualityPassed=false; ${setup.dataQualityReason}")),
                )
            }
            return
        }
        val eligibility = TradeEligibilityRules.evaluate(setup, environment, _state.value.journal, _state.value.minimumSignalScore)
        val minimumSignalScore = _state.value.minimumSignalScore
        val memoryDecision = eligibility.memoryDecision
        val effectiveScore = eligibility.adjustedScore
        val appRating = eligibility.appRating
        val ratingDiagnostic = "symbol=${setup.symbol}; scannerScore=${setup.score}/100; displayedMemoryRating=${memoryDecision.rating}/100; eligibilityMemoryInput=${memoryDecision.eligibilityRating}/100 (capped at neutral before ${TradeJournalEngine.MIN_MEMORY_SAMPLE_FOR_BONUS} samples); blend=round(0.70×${setup.score}+0.30×${memoryDecision.eligibilityRating})=$appRating/100; memoryAdjustment=${memoryDecision.scoreAdjustment}; adjustedScore=$effectiveScore/100; minimum=$minimumSignalScore; setupExecutable=${setup.isExecutableSetup}."
        _state.update { it.copy(debuggerTerminal = appendDebug(it, listOf("RATING" to ratingDiagnostic))) }
        if (memoryDecision.isRemembered) {
            val message = "${setup.symbol}: remembered ${memoryDecision.rating}/100 ${memoryDecision.directive.name}; score ${setup.score} → $effectiveScore. ${memoryDecision.reason}"
            _state.update { it.copy(terminal = appendActivity(it, listOf("MEMORY" to message)), debuggerTerminal = appendDebug(it, listOf("MEMORY" to message))) }
        }
        val paperTestEligible = eligibility.paperTestEligible
        val executionEligible = eligibility.eligible
        if (!executionEligible) {
            val requirement = "A rule-qualified candidate with verified data, adjusted score >=$minimumSignalScore/100, and app rating >=$minimumSignalScore/100 is required."
            _state.update {
                it.copy(
                    orderStatus = "Execution blocked: $requirement Scanner ${setup.score}/100; memory ${memoryDecision.rating}/100 (${memoryDecision.directive}); app rating ${appRating}/100; adjusted score ${effectiveScore}/100.",
                    terminal = appendActivity(it, listOf("GUARD" to "${setup.symbol} blocked; score=${setup.score}; adjusted=$effectiveScore; directive=${memoryDecision.directive.name}; executable=${setup.isExecutableSetup}; no order sent.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "Eligibility=false; $ratingDiagnostic paperTestEligible=$paperTestEligible; directive=${memoryDecision.directive.name}; no order sent.")),
                )
            }
            return
        }
        val orderSide = setup.direction.orderSide
        if (setup.symbol == lastOrderSymbol && System.currentTimeMillis() - lastOrderAtMs < 15 * 60 * 1000) {
            _state.update {
                it.copy(
                    terminal = appendActivity(it, listOf("GUARD" to "Duplicate-order cooldown active for ${setup.symbol}; no order sent.")),
                    debuggerTerminal = appendDebug(it, listOf("GUARD" to "Duplicate cooldown has ${15 * 60 * 1000 - (System.currentTimeMillis() - lastOrderAtMs)}ms remaining for ${setup.symbol}.")),
                )
            }
            return
        }

        val modeLabel = if (environment == DirectEnvironment.PAPER && !setup.isExecutableSetup) "PAPER TEST MODE; " else ""
        _state.update {
            it.copy(
                isLoading = true,
                orderStatus = null,
                terminal = appendActivity(it, listOf("ORDER" to "${modeLabel}Preparing a stop-risk-sized ${setup.direction.label} (${orderSide.uppercase(Locale.US)}) bracket for ${setup.symbol}; scanPrice=${format(setup.lastPrice)}; scanStop=${setup.scannerStopPrice?.let { value -> format(value) }}; scanTarget=${setup.scannerTargetPrice?.let { value -> format(value) }}; score=${setup.score}/100.")),
                debuggerTerminal = appendDebug(it, listOf("ORDER" to "Shared manual/auto eligibility passed; symbol=${setup.symbol}; direction=${setup.direction.label}; side=$orderSide; environment=${environment.label}; entryType=${if (setup.symbol in SECOND_BASKET_SYMBOLS) "limit" else "market"}; quote refresh determines quantity; scanner stop/target are preserved and revalidated.")),
            )
        }
        var orderSubmissionAttempted = false
        try {
            val requestNow = System.currentTimeMillis()
            if (!MarketDataQuality.regularSessionOpen(requestNow)) {
                throw IllegalStateException("Paper bracket execution is available only during the regular 09:30–16:00 New York weekday equity session. Extended-hours candidates are scan-only; no order was sent.")
            }
            val clock = environment.marketClock(key, secret)
            val brokerClockIsOpen = clock["is_open"]?.jsonPrimitive?.booleanOrNull
            val clockTimestamp = clock["timestamp"]?.jsonPrimitive?.contentOrNull?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val clockReceivedAtMs = System.currentTimeMillis()
            val clockBlockReason = BrokerClockPolicy.blockReason(
                isOpen = brokerClockIsOpen,
                timestampEpochMs = clockTimestamp,
                receivedAtEpochMs = clockReceivedAtMs,
            )
            _state.update { it.copy(debuggerTerminal = appendDebug(it, listOf("CLOCK" to "isOpen=$brokerClockIsOpen; bracketExecutionSession=regular-only; brokerTimestamp=${clock["timestamp"]?.jsonPrimitive?.contentOrNull}; responseReceivedAt=$clockReceivedAtMs; ageMs=${clockTimestamp?.let { timestamp -> clockReceivedAtMs - timestamp }}; block=${clockBlockReason ?: "none"}."))) }
            if (clockBlockReason != null) throw IllegalStateException(clockBlockReason)
            // Fresh account + positions + orders are required before every request, including both
            // manual and scheduled-scan requests. No cached risk value is used for order gating.
            val account = environment.account(key, secret)
            val equity = account["equity"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: throw IllegalStateException("Execution blocked: fresh positive account equity could not be verified.")
            val positions = environment.positions(key, secret)
            val workingOrders = environment.orders(key, secret, status = "open", limit = 500)
            val conflict = DuplicateExposurePolicy.blockReason(
                symbol = setup.symbol,
                openPositionSymbols = positions.mapNotNull { it["symbol"]?.jsonPrimitive?.contentOrNull },
                workingOrderSymbols = workingOrders.mapNotNull { it["symbol"]?.jsonPrimitive?.contentOrNull },
            )
            if (conflict != null) throw IllegalStateException("Existing-exposure review blocked ${setup.symbol}: $conflict No duplicate or netting order was sent.")

            val stop = setup.scannerStopPrice ?: throw IllegalStateException("Execution blocked: scanner stop is missing.")
            val target = setup.scannerTargetPrice ?: throw IllegalStateException("Execution blocked: scanner target is missing.")
            val geometryError = TradeGeometry.validationError(orderSide == "buy", setup.lastPrice, stop, target)
            _state.update { it.copy(debuggerTerminal = appendDebug(it, listOf("BRACKET GEOMETRY" to "source=scanner snapshot; symbol=${setup.symbol}; direction=${setup.direction.label}; side=$orderSide; scanEntry=${format(setup.lastPrice)}; stop=${format(stop)}; target=${format(target)}; validation=${geometryError ?: "pass"}."))) }
            if (geometryError != null) throw IllegalStateException("Execution blocked: $geometryError No order was sent.")
            val repairedStops = environment.repairMissingProtectiveStops(key, secret, positions, workingOrders)
            val verifiedPositions = if (repairedStops.isEmpty()) positions else environment.positions(key, secret)
            val verifiedWorkingOrders = if (repairedStops.isEmpty()) workingOrders else environment.orders(key, secret, status = "open", limit = 500)
            val riskSnapshot = OpenStopRiskCalculator.calculate(account, verifiedPositions, verifiedWorkingOrders)
            val orderDiagnostics = verifiedWorkingOrders.map { order ->
                val symbol = order["symbol"]?.jsonPrimitive?.contentOrNull ?: "?"
                val id = order["id"]?.jsonPrimitive?.contentOrNull ?: order["client_order_id"]?.jsonPrimitive?.contentOrNull ?: "?"
                val status = order["status"]?.jsonPrimitive?.contentOrNull ?: "?"
                val side = order["side"]?.jsonPrimitive?.contentOrNull ?: "?"
                val type = order["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                val qty = order["qty"]?.jsonPrimitive?.contentOrNull ?: "?"
                val filled = order["filled_qty"]?.jsonPrimitive?.contentOrNull ?: "0"
                val remaining = order["remaining_qty"]?.jsonPrimitive?.contentOrNull ?: "?"
                val stopPrice = order["stop_price"]?.jsonPrimitive?.contentOrNull
                    ?: (order["stop_loss"] as? JsonObject)?.get("stop_price")?.jsonPrimitive?.contentOrNull
                    ?: "?"
                "$symbol id=$id status=$status side=$side type=$type qty=$qty filled=$filled remaining=$remaining stop=$stopPrice"
            }
            _state.update { it.copy(debuggerTerminal = appendDebug(it, listOf("STOP RISK INPUT" to "broker=${environment.label}; automaticRepairs=${repairedStops.joinToString().ifBlank { "none" }}; account=selected-session; positions=${verifiedPositions.size}; openOrders=${verifiedWorkingOrders.size}; ${orderDiagnostics.joinToString(" | ").ifBlank { "no open orders" }}; calculated=${riskSnapshot.downsideRiskDollars?.let(::format) ?: "unknown"}; verified=${riskSnapshot.isVerified}; issue=${riskSnapshot.issue ?: "none"}."))) }
            if (!riskSnapshot.isFresh()) throw IllegalStateException("Execution blocked: open stop risk could not be freshly verified (${riskSnapshot.issue ?: "snapshot stale"}).")
            orderIntentStore.get(environment.name, setup.symbol)?.let { persistedClientOrderId ->
                val priorOrder = try {
                    environment.orderByClientOrderId(key, secret, persistedClientOrderId)
                } catch (error: Exception) {
                    if (ClientOrderRetryPolicy.blocksUnknownLookup(error)) throw IllegalStateException("Execution blocked: prior client order $persistedClientOrderId could not be verified; refresh broker state before retrying.")
                    null
                }
                if (priorOrder != null) {
                    val priorStatus = priorOrder["status"]?.jsonPrimitive?.contentOrNull
                    if (!ClientOrderRetryPolicy.mayRetryAfterLookup(true, priorStatus)) {
                        throw IllegalStateException("Execution blocked: prior client order $persistedClientOrderId is still active/ambiguous (status=${priorStatus ?: "missing"}); no duplicate was sent.")
                    }
                }
                orderIntentStore.remove(environment.name, setup.symbol)
            }
            val positionNotionals = verifiedPositions.map { position ->
                PositionNotional(
                    position["symbol"]?.jsonPrimitive?.contentOrNull ?: "?",
                    position["market_value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    position["side"]?.jsonPrimitive?.contentOrNull,
                )
            }
            val orderNotionals = verifiedWorkingOrders.filter { order -> order["parent_order_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
                .map { order ->
                    val symbol = order["symbol"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val qty = order["qty"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    val filledQty = order["filled_qty"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val remainingQty = qty?.let { (abs(it) - abs(filledQty)).coerceAtLeast(0.0) }
                    val price = order["limit_price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        ?: order["stop_price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        ?: order["filled_avg_price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        ?: setup.lastPrice.takeIf { symbol.equals(setup.symbol, ignoreCase = true) }
                    val notional = when {
                        remainingQty == 0.0 -> 0.0
                        remainingQty != null && price != null -> remainingQty * price
                        else -> null
                    }
                    OrderNotional(symbol, notional, order["side"]?.jsonPrimitive?.contentOrNull)
                }
            val grossExposure = NotionalExposurePolicy.grossNotional(positionNotionals, orderNotionals)
                ?: throw IllegalStateException("Execution blocked: open notional exposure cannot be verified.")
            val remainingNotionalCapacity = NotionalExposurePolicy.remainingCapacityDollars(equity, grossExposure)
                ?: throw IllegalStateException("Execution blocked: remaining gross-notional capacity cannot be verified.")
            val plan = TradeGeometry.plan(
                isLong = orderSide == "buy",
                entryPrice = setup.lastPrice,
                stopPrice = stop,
                targetPrice = target,
                equityDollars = equity,
                maximumNotionalDollars = remainingNotionalCapacity,
            )
            val batchRiskDollars = equity * requestedBatchRiskPct
            val riskGate = PortfolioRiskPolicy.assessStopRisk(
                equityDollars = equity,
                currentOpenRiskDollars = riskSnapshot.downsideRiskDollars!!,
                proposedOrderRiskDollars = plan.estimatedRiskDollars,
                proposedBatchRiskDollars = maxOf(plan.estimatedRiskDollars, batchRiskDollars),
            )
            if (!riskGate.allowed) throw IllegalStateException("Execution blocked: ${riskGate.reason}")
            _state.update { current ->
                current.copy(debuggerTerminal = appendDebug(current, listOf("NOTIONAL SIZING" to
                    "equity=${format(equity)} USD; existingGross=${format(grossExposure)} USD (${format(grossExposure / equity * 100.0)}%); 80% cap headroom=${format(remainingNotionalCapacity)} USD; risk-sizedQty=${plan.riskSizedQuantity}; finalQty=${plan.quantity}; exposureLimited=${plan.limitedByNotionalExposure}; proposed=${format(setup.lastPrice * plan.quantity)} USD (${format((setup.lastPrice * plan.quantity / equity) * 100.0)}% equity).")))
            }
            val exposureGate = NotionalExposurePolicy.assess(equity, grossExposure, setup.lastPrice * plan.quantity)
            if (!exposureGate.allowed) throw IllegalStateException("Execution blocked: ${exposureGate.reason}")

            val clientOrderId = TradeTicketFactory.newClientOrderId()
            orderIntentStore.put(environment.name, setup.symbol, clientOrderId)
            orderSubmissionAttempted = true
            val submission = environment.submitBracketOrder(
                key,
                secret,
                setup.symbol,
                orderSide,
                stop,
                target,
                useLimitEntry = setup.symbol in SECOND_BASKET_SYMBOLS,
                referenceEntryPrice = setup.lastPrice,
                clientOrderId = clientOrderId,
                equityDollars = equity,
                currentGrossExposureDollars = grossExposure,
                riskBudgetPct = requestedRiskBudgetPct,
            )
            val id = submission.order["id"]?.jsonPrimitive?.content ?: "accepted"
            val status = submission.order["status"]?.jsonPrimitive?.content ?: "accepted"
            val filledQty = submission.order["filled_qty"]?.jsonPrimitive?.content ?: "0"
            val lifecycleMessage = when (status.lowercase()) {
                "filled", "partially_filled" -> "${setup.symbol} entry status=$status; filledQty=$filledQty; position should appear after Alpaca position refresh."
                "new", "pending_new", "accepted" -> "${setup.symbol} entry submitted with status=$status; it is not filled yet, so no live position is expected."
                else -> "${setup.symbol} entry returned status=$status; check Alpaca order details before assuming a position exists."
            }
            lastOrderSymbol = setup.symbol
            lastOrderAtMs = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isLoading = false,
                    orderStatus = "${if (environment == DirectEnvironment.PAPER) "Paper order submitted" else "Order submitted"}: id=$id; status=$status; filledQty=$filledQty",
                    autoScanConfirmQueue = if (autoCandidate == null) it.autoScanConfirmQueue else it.autoScanConfirmQueue.filterNot { candidate -> candidate.setup.symbol == setup.symbol },
                    autoScanConfirmSelectedSymbols = if (autoCandidate == null) it.autoScanConfirmSelectedSymbols else it.autoScanConfirmSelectedSymbols - setup.symbol,
                    terminal = appendActivity(it, listOf("SUBMITTED" to "${modeLabel}${lifecycleMessage} bracketId=$id; qty=${submission.quantity}; reference=${format(submission.referencePrice)}; stop=${format(submission.stopPrice)}; target=${format(submission.targetPrice)}.")),
                    debuggerTerminal = appendDebug(it, listOf("ORDER RESPONSE" to "id=$id; side=$orderSide; direction=${setup.direction.label}; status=$status; filledQty=$filledQty; freshQuoteUsed=${submission.usedFreshQuote}; protectionDistance=${format(submission.protectionDistancePct * 100.0)}%; reference=${format(submission.referencePrice)}; stop=${format(submission.stopPrice)}; target=${format(submission.targetPrice)}; riskPerShare=${format(abs(submission.referencePrice - submission.stopPrice))}; rewardPerShare=${format(abs(submission.targetPrice - submission.referencePrice))}.")),
                    journalEntries = appendAutomaticJournal(it, setup.symbol, "Order submitted", "${environment.label}: id=$id; status=$status; filledQty=$filledQty; stop=${format(submission.stopPrice)}; target=${format(submission.targetPrice)}."),
                )
            }
            recordOpenTicket(setup, environment, submission, id, appRating, memoryDecision.directive)
        } catch (error: CancellationException) {
            _state.update {
                it.copy(
                    isLoading = false,
                    orderStatus = "Execution cancelled during an order request; broker outcome is unverified. Refresh Alpaca before retrying.",
                    terminal = appendActivity(it, listOf("ORDER CANCELLED" to "${setup.symbol} request coroutine cancelled; broker acceptance may be unverified.")),
                    debuggerTerminal = appendDebug(it, listOf("ORDER CANCELLED" to "${setup.symbol}; manual submission was cancelled; broker outcome unverified; no automatic retry.")),
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "unknown Alpaca order error"
            val statusMessage = if (orderSubmissionAttempted) "Order rejected: $message" else if (message.startsWith("Execution blocked")) message else "Execution blocked: $message"
            val eventType = if (orderSubmissionAttempted) "ORDER ERROR" else "GUARD"
            val grantWarning = if (message.contains("403") && message.contains("insufficient grants", ignoreCase = true)) {
                "Alpaca denied this request: insufficient grants. Enable the required permission in Alpaca and reconnect with a newly generated key."
            } else {
                null
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    orderStatus = statusMessage,
                    permissionWarning = grantWarning ?: it.permissionWarning,
                    terminal = appendActivity(it, listOf(eventType to if (orderSubmissionAttempted) "${setup.symbol} bracket rejected: $message" else "${setup.symbol} preflight blocked: $message")),
                    debuggerTerminal = appendDebug(it, listOf(eventType to if (orderSubmissionAttempted) "Submission failed for ${setup.symbol}: $message" else "No broker order request was sent for ${setup.symbol}: $message")),
                    journalEntries = appendAutomaticJournal(it, setup.symbol, if (orderSubmissionAttempted) "Order error" else "Execution blocked", message),
                )
            }
        }
    }

    fun refresh() {
        val environment = _state.value.environment ?: return
        val key = apiKey ?: return
        val secret = apiSecret ?: return
        viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    info = "Refreshing account from Alpaca...",
                    terminal = appendActivity(it, listOf("ACCOUNT" to "Refreshing account, open positions, and pending orders from ${environment.label}.")),
                    debuggerTerminal = appendDebug(it, listOf("ACCOUNT" to "Refresh started; credentials are not logged.")),
                )
            }
            var stopRiskSnapshot = OpenStopRiskSnapshot.unverified("Waiting for a complete Alpaca order snapshot.")
            try {
                val account = environment.account(key, secret)
                val positions = environment.positions(key, secret)
                val pendingOrders = environment.openOrders(key, secret)
                data class BrokerSnapshot(
                    val history: List<ClosedTrade>,
                    val journal: JournalState,
                    val newlyClosed: List<com.alpacatalk.trading.data.ClosedTrade>,
                )
                val brokerSnapshot = runCatching {
                    val rawOrders = environment.orders(key, secret, status = "all", limit = 500)
                    stopRiskSnapshot = OpenStopRiskCalculator.calculate(account, positions, rawOrders)
                    val brokerOrders = BrokerOrderMapper.fromAll(rawOrders)
                    val filledIds = filledBrokerOrderIds(rawOrders)
                    val bracketResult = ClosedTradeReconciler.reconcile(journalState.openTickets, brokerOrders)
                    var stagedJournal = journalState
                    bracketResult.closed.forEach { trade -> stagedJournal = TradeJournalEngine.recordTrade(stagedJournal, trade) }
                    stagedJournal = TradeJournalEngine.recordTickets(stagedJournal, bracketResult.stillOpen + bracketResult.untouched)
                    bracketResult.cancelled.forEach { ticket -> stagedJournal = TradeJournalEngine.removeTicket(stagedJournal, ticket.ticketId) }
                    val activities = environment.fillActivities(key, secret)
                    val history = deriveClosedTrades(activities, filledIds, stagedJournal.openTickets, brokerOrders)
                    val (verifiedJournal, fillClosures) = storeVerifiedTicketClosures(stagedJournal, history, brokerOrders, filledIds)
                    val historicalImports = importHistoricalClosedTrades(history, rawOrders, verifiedJournal)
                    val fullySyncedJournal = historicalImports.fold(verifiedJournal) { journal, trade ->
                        TradeJournalEngine.recordTrade(journal, trade)
                    }
                    BrokerSnapshot(history, fullySyncedJournal, bracketResult.closed + fillClosures + historicalImports)
                }.getOrNull()
                val closedTrades = brokerSnapshot?.history.orEmpty()
                val newlyClosed = brokerSnapshot?.newlyClosed.orEmpty()
                if (brokerSnapshot != null && brokerSnapshot.journal != journalState) {
                    journalState = journalStore.save(brokerSnapshot.journal)
                }
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                _state.update {
                    it.copy(
                        isLoading = false,
                        account = account,
                        positions = positions,
                        pendingOrders = pendingOrders,
                        openStopRisk = stopRiskSnapshot,
                        scanMonitorPaused = it.autoTrading && (!stopRiskSnapshot.isFresh() || stopRiskSnapshot.capReached),
                        scanMonitorStatus = if (!it.autoTrading) it.scanMonitorStatus else when {
                            !stopRiskSnapshot.isFresh() -> stopRiskSnapshot.issue ?: "Broker risk snapshot is stale; scans are paused."
                            stopRiskSnapshot.capReached -> "Modeled downside risk has reached 10% of equity; scans are paused until verified risk falls."
                            else -> "Verified downside risk is below the 10% cap."
                        },
                        closedTrades = closedTrades,
                        journal = journalStore.snapshot(journalState),
                        journalSyncedAtEpochMs = if (newlyClosed.isNotEmpty()) System.currentTimeMillis() else it.journalSyncedAtEpochMs,
                        journalNotice = if (newlyClosed.isEmpty()) it.journalNotice else
                            journalStore.lastError ?: "Alpaca verified ${newlyClosed.size} closed trade(s); actual fill price(s) saved to the journal.",
                        info = "Account refreshed from Alpaca.",
                        terminal = appendActivity(
                            it,
                            listOf("ACCOUNT" to "Refresh complete in ${elapsedMs}ms; positions=${positions.size}; pendingOrders=${pendingOrders.size}.") +
                                newlyClosed.map { trade -> "JOURNAL VERIFIED" to "${trade.symbol} closed at actual Alpaca fill ${TradeMath.formatPrice(trade.exitPrice)}; stored after Alpaca confirmed the filled entry and completed exit quantity." },
                        ),
                        debuggerTerminal = appendDebug(it, listOf("ACCOUNT" to "Refresh succeeded; elapsed=${elapsedMs}ms; positions=${positions.size}; pendingOrders=${pendingOrders.size}.")),
                        journalEntries = appendAutomaticJournal(it, "ACCOUNT", "Refresh", "Loaded ${positions.size} open position(s) and ${pendingOrders.size} pending order(s)."),
                    )
                }
            } catch (error: Exception) {
                val message = error.message ?: "Refresh failed."
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = message,
                        openStopRisk = OpenStopRiskSnapshot.unverified("Risk refresh failed: $message"),
                        terminal = appendActivity(it, listOf("ACCOUNT ERROR" to "Refresh failed: $message")),
                        debuggerTerminal = appendDebug(it, listOf("ACCOUNT ERROR" to "Refresh failed: $message")),
                        journalEntries = appendAutomaticJournal(it, "ACCOUNT", "Refresh error", message),
                    )
                }
            }
        }
    }

    /** Keeps account, positions, pending orders and unrealized P/L current without requiring a
     * manual refresh. The manual button remains available for an immediate sync. */
    private fun startAccountRefreshLoop() {
        accountRefreshJob?.cancel()
        accountRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val current = _state.value
                if (current.connected && !current.isLoading && (!current.isScanning || current.autoTrading) && !current.journalBusy) {
                    refresh()
                }
            }
        }
    }

    fun cancelPendingOrder(orderId: String, confirmed: Boolean) {
        val environment = _state.value.environment ?: return
        val key = apiKey ?: return
        val secret = apiSecret ?: return
        if (environment == DirectEnvironment.LIVE && !confirmed) {
            _state.update { it.copy(orderStatus = "Live order cancellation requires confirmation.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    orderStatus = null,
                    terminal = appendActivity(it, listOf("ORDER CANCEL" to "Cancelling broker order $orderId.")),
                )
            }
            try {
                environment.cancelOrder(key, secret, orderId)
                val account = environment.account(key, secret)
                val positions = environment.positions(key, secret)
                val pendingOrders = environment.openOrders(key, secret)
                _state.update {
                    it.copy(
                        isLoading = false,
                        account = account,
                        positions = positions,
                        pendingOrders = pendingOrders,
                        orderStatus = "Order cancellation accepted. ${pendingOrders.size} pending order(s) remain.",
                        terminal = appendActivity(it, listOf("ORDER CANCEL" to "Cancellation accepted for $orderId; pendingOrders=${pendingOrders.size}.")),
                        journalEntries = appendAutomaticJournal(it, "ORDER", "Order cancelled", "Order $orderId cancelled; ${pendingOrders.size} pending order(s) remain."),
                    )
                }
            } catch (error: Exception) {
                val message = error.message ?: "unknown broker error"
                _state.update {
                    it.copy(
                        isLoading = false,
                        orderStatus = "Order cancellation failed: $message",
                        terminal = appendActivity(it, listOf("ORDER CANCEL ERROR" to "Cancellation failed for $orderId: $message")),
                        journalEntries = appendAutomaticJournal(it, "ORDER", "Cancellation error", "Order $orderId: $message"),
                    )
                }
            }
        }
    }

    fun closeOpenPosition(symbol: String, confirmed: Boolean) {
        val environment = _state.value.environment ?: return
        val key = apiKey ?: return
        val secret = apiSecret ?: return
        if (!confirmed) {
            _state.update { it.copy(orderStatus = "Closing a position requires final confirmation.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    orderStatus = null,
                    terminal = appendActivity(it, listOf("POSITION CLOSE" to "Checking attached protection and same-symbol exit orders that may reserve quantity for $symbol.")),
                    debuggerTerminal = appendDebug(it, listOf("POSITION CLOSE PREFLIGHT" to "symbol=$symbol; inspect position side/quantity; cancel working protective exit legs, then re-read order history and position before close.")),
                )
            }
            try {
                val result = submitPositionClosesSafely(environment, key, secret, listOf(symbol))
                val refreshed = runCatching {
                    Triple(environment.account(key, secret), environment.positions(key, secret), environment.openOrders(key, secret))
                }.getOrNull()
                val summary = if (result.submitted.isNotEmpty()) {
                    "${result.submitted.size} close request submitted for $symbol. The journal is waiting for Alpaca fill confirmation."
                } else result.alreadyFlat.firstOrNull() ?: result.failed.firstOrNull() ?: "No close request was submitted for $symbol."
                _state.update {
                    it.copy(
                        isLoading = false,
                        account = refreshed?.first ?: it.account,
                        positions = refreshed?.second ?: it.positions,
                        pendingOrders = refreshed?.third ?: it.pendingOrders,
                        orderStatus = summary,
                        debuggerTerminal = appendDebug(
                            it,
                            result.diagnostics.map { detail -> "CLOSE PREFLIGHT" to detail } +
                                listOf("POSITION CLOSE RESULT" to "symbol=$symbol; submitted=${result.submitted.size}; alreadyFlat=${result.alreadyFlat.size}; failed=${result.failed.joinToString(" | ").ifBlank { "none" }}"),
                        ),
                        terminal = appendActivity(
                            it,
                            listOf("POSITION CLOSE" to summary) +
                                result.submitted.map { item -> "CLOSE REQUEST" to item } +
                                result.alreadyFlat.map { item -> "CLOSE ALREADY FLAT" to item } +
                                result.failed.map { item -> "CLOSE ERROR" to item },
                        ),
                        journalEntries = result.submitted.fold(it.journalEntries) { entries, item ->
                            appendAutomaticJournal(it.copy(journalEntries = entries), symbol, "Position close requested", item)
                        },
                    )
                }
            } catch (error: Exception) {
                val message = error.message ?: "unknown broker error"
                _state.update {
                    it.copy(
                        isLoading = false,
                        orderStatus = "Close request failed: $message",
                        terminal = appendActivity(it, listOf("POSITION CLOSE ERROR" to "$symbol close failed: $message")),
                        debuggerTerminal = appendDebug(it, listOf("POSITION CLOSE ERROR" to "symbol=$symbol; brokerError=$message; no automatic retry submitted.")),
                        journalEntries = appendAutomaticJournal(it, symbol, "Position close error", message),
                    )
                }
            }
        }
    }

    /** Sends broker close requests only after attached protective bracket legs are cleared. */
    fun closeAllOpenPositions(confirmed: Boolean) {
        val environment = _state.value.environment ?: return
        val key = apiKey ?: return
        val secret = apiSecret ?: return
        val symbols = _state.value.positions.mapNotNull { position ->
            position["symbol"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
        if (symbols.isEmpty()) {
            _state.update { it.copy(orderStatus = "No open positions to close in the latest Alpaca snapshot.") }
            return
        }
        if (!confirmed) {
            _state.update { it.copy(orderStatus = "Close-all action requires final confirmation.") }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    orderStatus = null,
                    terminal = appendActivity(it, listOf("CLOSE ALL" to "Checking/cancelling attached protection and same-symbol working exit orders, then submitting one close request per selected position; unrelated pending orders are preserved.")),
                    debuggerTerminal = appendDebug(it, listOf("CLOSE ALL PREFLIGHT" to "symbols=${symbols.joinToString()}; same-symbol entry orders remain blockers; no unverified close is submitted.")),
                )
            }
            val result = runCatching { submitPositionClosesSafely(environment, key, secret, symbols) }
                .getOrElse { error -> PositionCloseResult(failed = symbols.map { "$it: ${error.message ?: "broker close failed"}" }) }
            val refreshed = runCatching {
                Triple(environment.account(key, secret), environment.positions(key, secret), environment.openOrders(key, secret))
            }.getOrNull()
            val summary = "Close-all: ${result.submitted.size}/${symbols.size} close request(s) submitted, ${result.alreadyFlat.size} already flat, ${result.failed.size} failed. Journal prices are saved only after Alpaca confirms the completed exit fills."
            _state.update {
                it.copy(
                    isLoading = false,
                    account = refreshed?.first ?: it.account,
                    positions = refreshed?.second ?: it.positions,
                    pendingOrders = refreshed?.third ?: it.pendingOrders,
                    orderStatus = summary,
                    debuggerTerminal = appendDebug(
                        it,
                        result.diagnostics.map { detail -> "CLOSE PREFLIGHT" to detail } +
                            listOf("CLOSE ALL RESULT" to "requested=${symbols.size}; submitted=${result.submitted.size}; alreadyFlat=${result.alreadyFlat.size}; failed=${result.failed.joinToString(" | ").ifBlank { "none" }}"),
                    ),
                    terminal = appendActivity(
                        it,
                        listOf("CLOSE ALL" to summary) +
                            result.submitted.map { item -> "CLOSE REQUEST" to item } +
                            result.alreadyFlat.map { item -> "CLOSE ALREADY FLAT" to item } +
                            result.failed.map { item -> "CLOSE ERROR" to item },
                    ),
                    journalEntries = result.submitted.fold(it.journalEntries) { entries, item ->
                        appendAutomaticJournal(it.copy(journalEntries = entries), item.substringBefore(' '), "Position close requested", item)
                    },
                )
            }
        }
    }

    /** Cancels only active stop/target legs of bracket orders for the selected symbols. */
    private suspend fun submitPositionClosesSafely(
        environment: DirectEnvironment,
        key: String,
        secret: String,
        symbols: List<String>,
    ): PositionCloseResult {
        val requested = symbols.map { it.uppercase(Locale.US) }.distinct()
        if (requested.isEmpty()) return PositionCloseResult()
        val before = runCatching {
            environment.orders(key, secret, status = "all", limit = 500) +
                environment.orders(key, secret, status = "open", limit = 500)
        }.getOrElse { error ->
            return PositionCloseResult(failed = requested.map { "$it: could not inspect broker orders; no close was sent (${error.message ?: "order-history error"})." })
        }

        val activeStatuses = setOf("new", "accepted", "pending_new", "accepted_for_bidding", "held", "partially_filled", "pending_cancel", "pending_replace")
        val positionSnapshot = runCatching { environment.positions(key, secret) }.getOrElse { error ->
            return PositionCloseResult(failed = requested.map { "$it: current position could not be verified; no close was sent (${error.message ?: "position request error"})." })
        }
        fun positionSide(position: JsonObject): String? {
            val explicit = position["side"]?.jsonPrimitive?.content?.lowercase(Locale.US)
            if (explicit == "long" || explicit == "short") return explicit
            val quantity = position["qty"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
            return when {
                quantity > 0.0 -> "long"
                quantity < 0.0 -> "short"
                else -> null
            }
        }
        val positionSides = positionSnapshot.mapNotNull { position ->
            val symbol = position["symbol"]?.jsonPrimitive?.content?.uppercase(Locale.US) ?: return@mapNotNull null
            positionSide(position)?.let { symbol to it }
        }.toMap()
        val positionsBySymbol = positionSnapshot.mapNotNull { position ->
            val symbol = position["symbol"]?.jsonPrimitive?.content?.uppercase(Locale.US) ?: return@mapNotNull null
            symbol to position
        }.toMap()
        val diagnostics = mutableListOf<String>()
        fun activeBracketLegs(raw: List<JsonObject>, forSymbols: Set<String>): Map<String, List<String>> {
            return raw.flatMap { parent ->
                val symbol = parent["symbol"]?.jsonPrimitive?.content?.uppercase(Locale.US) ?: return@flatMap emptyList()
                val orderClass = parent["order_class"]?.jsonPrimitive?.content?.lowercase(Locale.US)
                val parentStatus = parent["status"]?.jsonPrimitive?.content?.lowercase(Locale.US)
                // Bracket/OTO protection is safe to cancel after its entry fills; OCO contains exit legs.
                val hasLivePositionProtection =
                    ((orderClass == "bracket" || orderClass == "oto") && parentStatus == "filled") ||
                        (orderClass == "oco" && parentStatus in activeStatuses)
                if (symbol !in forSymbols || !hasLivePositionProtection) return@flatMap emptyList()
                val legs = (parent["legs"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                legs.mapNotNull { leg ->
                    val type = leg["type"]?.jsonPrimitive?.content?.lowercase(Locale.US).orEmpty()
                    val status = leg["status"]?.jsonPrimitive?.content?.lowercase(Locale.US).orEmpty()
                    val id = leg["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if ((type.contains("stop") || type.contains("limit")) && status in activeStatuses) symbol to id else null
                }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct() }
        }
        fun activeSameSymbolOrders(raw: List<JsonObject>, forSymbols: Set<String>): Map<String, List<String>> = raw.mapNotNull { order ->
            val symbol = order["symbol"]?.jsonPrimitive?.content?.uppercase(Locale.US) ?: return@mapNotNull null
            val status = order["status"]?.jsonPrimitive?.content?.lowercase(Locale.US) ?: return@mapNotNull null
            if (symbol !in forSymbols || status !in activeStatuses) return@mapNotNull null
            val orderClass = order["order_class"]?.jsonPrimitive?.content?.lowercase(Locale.US)
            // Filled bracket/OTO parents and active OCO wrappers are groups, not executable open orders.
            if (((orderClass == "bracket" || orderClass == "oto") && status == "filled") || orderClass == "oco") return@mapNotNull null
            val id = order["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            symbol to id
        }.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct() }

        val attachedExitLegs = activeBracketLegs(before, requested.toSet())
        val standaloneExitOrders = before.mapNotNull { order ->
            val symbol = order["symbol"]?.jsonPrimitive?.content?.uppercase(Locale.US) ?: return@mapNotNull null
            val status = order["status"]?.jsonPrimitive?.content?.lowercase(Locale.US) ?: return@mapNotNull null
            val orderClass = order["order_class"]?.jsonPrimitive?.content?.lowercase(Locale.US)
            val orderSide = order["side"]?.jsonPrimitive?.content?.lowercase(Locale.US)
            val type = order["type"]?.jsonPrimitive?.content?.lowercase(Locale.US).orEmpty()
            val currentSide = positionSides[symbol]
            val reducesPosition = (currentSide == "long" && orderSide == "sell") || (currentSide == "short" && orderSide == "buy")
            val isWorkingExit = type.contains("stop") || type == "limit"
            val isNestedGroup = orderClass in setOf("bracket", "oco", "oto")
            val id = order["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (symbol in requested && status in activeStatuses && reducesPosition && isWorkingExit && !isNestedGroup) symbol to id else null
        }.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct() }
        val toCancel = (attachedExitLegs.keys + standaloneExitOrders.keys).associateWith { symbol ->
            (attachedExitLegs[symbol].orEmpty() + standaloneExitOrders[symbol].orEmpty()).distinct()
        }
        requested.forEach { symbol ->
            val position = positionsBySymbol[symbol]
            val quantity = position?.get("qty")?.jsonPrimitive?.content ?: "none"
            val side = positionSides[symbol] ?: "unknown"
            diagnostics += "symbol=$symbol; brokerPositionSide=$side; brokerPositionQty=$quantity; bracketOrOCOExitIds=${attachedExitLegs[symbol].orEmpty().joinToString().ifBlank { "none" }}; standaloneExitIds=${standaloneExitOrders[symbol].orEmpty().joinToString().ifBlank { "none" }}."
        }
        toCancel.values.flatten().distinct().forEach { orderId ->
            // OCO sibling cancellation can make a subsequent cancel request unnecessary; final broker
            // history below, rather than this response alone, decides whether it is safe to liquidate.
            val cancelError = runCatching { environment.cancelOrder(key, secret, orderId) }.exceptionOrNull()
            diagnostics += "cancelOrderId=$orderId; result=${cancelError?.message ?: "accepted; awaiting broker-state verification"}."
        }
        val after = runCatching {
            environment.orders(key, secret, status = "all", limit = 500) +
                environment.orders(key, secret, status = "open", limit = 500)
        }.getOrElse { error ->
            return PositionCloseResult(failed = requested.map { "$it: protective-order cancellation could not be verified; no close was sent (${error.message ?: "order-history error"})." })
        }
        val remainingProtective = activeBracketLegs(after, requested.toSet())
        val remainingSameSymbolOrders = activeSameSymbolOrders(after, requested.toSet())
        val currentPositions = runCatching { environment.positions(key, secret) }.getOrElse { error ->
            return PositionCloseResult(failed = requested.map { "$it: current position could not be verified; no close was sent (${error.message ?: "position request error"})." })
        }
        val positionSymbols = currentPositions.mapNotNull { it["symbol"]?.jsonPrimitive?.content?.uppercase(Locale.US) }.toSet()
        requested.forEach { symbol ->
            val position = currentPositions.firstOrNull { it["symbol"]?.jsonPrimitive?.content?.equals(symbol, ignoreCase = true) == true }
            val quantity = position?.get("qty")?.jsonPrimitive?.content ?: "none"
            diagnostics += "symbol=$symbol; verifiedPositionQty=$quantity; remainingProtectiveIds=${remainingProtective[symbol].orEmpty().joinToString().ifBlank { "none" }}; remainingSameSymbolOrderIds=${remainingSameSymbolOrders[symbol].orEmpty().joinToString().ifBlank { "none" }}."
        }
        val submitted = mutableListOf<String>()
        val alreadyFlat = mutableListOf<String>()
        val failed = mutableListOf<String>()
        requested.forEach { symbol ->
            val activeLegIds = remainingProtective[symbol].orEmpty()
            if (activeLegIds.isNotEmpty()) {
                failed += "$symbol: protective bracket leg(s) still active (${activeLegIds.joinToString()}); no close was sent."
                return@forEach
            }
            val activeOrders = remainingSameSymbolOrders[symbol].orEmpty()
            if (activeOrders.isNotEmpty()) {
                failed += "$symbol: another active same-symbol order remains (${activeOrders.joinToString()}); no close was sent to avoid an order conflict or reopening the position."
                return@forEach
            }
            if (symbol !in positionSymbols) {
                alreadyFlat += "$symbol: Alpaca currently reports no open position."
                return@forEach
            }
            val availability = awaitFullPositionAvailability(environment, key, secret, symbol)
            if (availability.alreadyFlat) {
                alreadyFlat += "$symbol: Alpaca currently reports no open position."
                return@forEach
            }
            val currentPosition = availability.position
            if (currentPosition == null || availability.error != null) {
                val detail = availability.error ?: "current position could not be verified"
                failed += "$symbol: $detail; no close request was sent."
                diagnostics += "symbol=$symbol; fullCloseAvailability=blocked; reason=$detail."
                return@forEach
            }
            val positionQty = currentPosition["qty"]?.jsonPrimitive?.contentOrNull
            val availableQty = currentPosition["qty_available"]?.jsonPrimitive?.contentOrNull
            diagnostics += "symbol=$symbol; final ${PositionCloseAvailability.describe(positionQty, availableQty)}; fullPositionCloseAllowed=true."
            try {
                val response = environment.closePosition(key, secret, symbol)
                val orderId = response["id"]?.jsonPrimitive?.content ?: "accepted"
                val status = response["status"]?.jsonPrimitive?.content ?: "submitted"
                submitted += "$symbol id=$orderId status=$status; protective bracket legs cancelled/clear"
            } catch (error: Exception) {
                val brokerMessage = error.message ?: "broker close request failed"
                val latestPosition = runCatching { environment.positions(key, secret).firstOrNull { it["symbol"]?.jsonPrimitive?.content?.equals(symbol, ignoreCase = true) == true } }.getOrNull()
                val rejectedQty = latestPosition?.get("qty")?.jsonPrimitive?.contentOrNull ?: positionQty ?: "unknown"
                val rejectedAvailable = latestPosition?.get("qty_available")?.jsonPrimitive?.contentOrNull ?: availableQty ?: "unknown"
                val detail = if (brokerMessage.contains("insufficient qty available", ignoreCase = true)) {
                    "Alpaca rejected the close for insufficient available quantity (${PositionCloseAvailability.describe(rejectedQty, rejectedAvailable)}). No automatic retry was sent; refresh and check for broker-side reservations or orders that changed after preflight."
                } else brokerMessage
                failed += "$symbol: $brokerMessage; $detail"
            }
        }
        return PositionCloseResult(submitted, alreadyFlat, failed, diagnostics)
    }

    /** Wait for Alpaca to release canceled-order reservations; never submit a full close on partial availability. */
    private suspend fun awaitFullPositionAvailability(
        environment: DirectEnvironment,
        key: String,
        secret: String,
        symbol: String,
    ): FullPositionAvailability {
        var lastPosition: JsonObject? = null
        repeat(13) { attempt ->
            val positions = try {
                environment.positions(key, secret)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return FullPositionAvailability(error = "latest position/available-quantity check failed (${error.message ?: "broker request error"})")
            }
            val position = positions.firstOrNull { it["symbol"]?.jsonPrimitive?.content?.equals(symbol, ignoreCase = true) == true }
                ?: return FullPositionAvailability(alreadyFlat = true)
            lastPosition = position
            val quantity = position["qty"]?.jsonPrimitive?.contentOrNull
            val available = position["qty_available"]?.jsonPrimitive?.contentOrNull
            if (quantity == null || available == null) {
                return FullPositionAvailability(position = position, error = "Alpaca did not report both qty and qty_available, so the full close cannot be verified")
            }
            if (PositionCloseAvailability.canCloseEntirePosition(quantity, available)) {
                return FullPositionAvailability(position = position)
            }
            if (attempt < 12) delay(400)
        }
        val quantity = lastPosition?.get("qty")?.jsonPrimitive?.contentOrNull
        val available = lastPosition?.get("qty_available")?.jsonPrimitive?.contentOrNull
        return FullPositionAvailability(
            position = lastPosition,
            error = "Alpaca still reports ${PositionCloseAvailability.describe(quantity, available)} after waiting for order reservations to release; check active broker orders",
        )
    }

    private fun deriveClosedTrades(
        activities: List<JsonObject>,
        filledOrderIds: Set<String>,
        journalTickets: List<com.alpacatalk.trading.data.OpenTradeTicket> = emptyList(),
        brokerOrders: List<com.alpacatalk.trading.data.BrokerOrderRecord> = emptyList(),
    ): List<ClosedTrade> = BrokerFillJournalReconciler.deriveClosedTrades(activities, filledOrderIds, journalTickets, brokerOrders)

    private fun filledBrokerOrderIds(rawOrders: List<JsonObject>): Set<String> = rawOrders.flatMap { parent ->
        val legs = (parent["legs"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        listOf(parent) + legs
    }.filter { order ->
        val status = order["status"]?.jsonPrimitive?.content.orEmpty()
        status.equals("filled", ignoreCase = true) || status.equals("partially_filled", ignoreCase = true)
    }.mapNotNull { it["id"]?.jsonPrimitive?.content }.toSet()

    /** Closes app tickets only when both the entry order and every required exit quantity are Alpaca fills. */
    private fun storeVerifiedTicketClosures(
        initial: JournalState,
        brokerHistory: List<ClosedTrade>,
        brokerOrders: List<com.alpacatalk.trading.data.BrokerOrderRecord>,
        filledOrderIds: Set<String>,
    ): Pair<JournalState, List<com.alpacatalk.trading.data.ClosedTrade>> =
        BrokerFillJournalReconciler.storeVerifiedTicketClosures(initial, brokerHistory, brokerOrders, filledOrderIds)

    fun addJournalEntry(symbol: String, setup: String, notes: String) {
        val cleanNotes = notes.trim()
        if (cleanNotes.isBlank()) return
        val entry = JournalEntry(
            id = System.currentTimeMillis(),
            createdAt = Instant.now().toString(),
            symbol = symbol.trim().ifBlank { "GENERAL" }.uppercase(),
            setup = setup.trim().ifBlank { "Manual note" },
            notes = cleanNotes,
        )
        _state.update { it.copy(journalEntries = listOf(entry) + it.journalEntries) }
    }

    private fun appendAutomaticJournal(current: DirectTradingState, symbol: String, setup: String, notes: String): List<JournalEntry> =
        listOf(journalEntry(symbol, setup, notes)) + current.journalEntries.take(MAX_JOURNAL_ENTRIES - 1)

    private fun journalEntry(symbol: String, setup: String, notes: String): JournalEntry = JournalEntry(
        id = System.nanoTime(),
        createdAt = Instant.now().toString(),
        symbol = symbol.trim().ifBlank { "GENERAL" }.uppercase(),
        setup = setup.trim().ifBlank { "Automatic event" },
        notes = notes.trim().ifBlank { "No details reported." },
    )

    // ---------------------------------------------------------------------------------------------
    // Trade journal and setup memory
    // ---------------------------------------------------------------------------------------------

    /** Re-reads the journal from disk and republishes the rebuilt memory. */
    fun refreshJournal() {
        journalState = journalStore.load()
        _state.update {
            it.copy(
                journal = journalStore.snapshot(journalState),
                journalNotice = journalStore.lastError
                    ?: "Journal reloaded: ${journalState.trades.size} stored closed trade(s), ${journalState.openTickets.size} open ticket(s).",
            )
        }
    }

    /**
     * Reconciles the app's open tickets with the broker's own order history, so a closed bracket is
     * journalled automatically and the memory learns from the real exit instead of an assumption.
     */
    fun syncClosedTrades() {
        val environment = _state.value.environment
        val key = apiKey
        val secret = apiSecret
        if (environment == null || key == null || secret == null) {
            _state.update { it.copy(journalNotice = "Sync needs a live Alpaca session. Connect first, then sync.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(journalBusy = true, journalNotice = "Checking Alpaca orders and fills from ${environment.label}...") }
            try {
                val rawOrders = environment.orders(key, secret, status = "all", limit = 500)
                val orders = BrokerOrderMapper.fromAll(rawOrders)
                val result = ClosedTradeReconciler.reconcile(journalState.openTickets, orders)
                var next = journalState
                result.closed.forEach { trade -> next = TradeJournalEngine.recordTrade(next, trade) }
                next = TradeJournalEngine.recordTickets(next, result.stillOpen + result.untouched)
                result.cancelled.forEach { ticket -> next = TradeJournalEngine.removeTicket(next, ticket.ticketId) }
                // Also import historical round trips that predate this journal. Those trades have no
                // app ticket to match, so use Alpaca's filled activities plus bracket legs when present.
                val filledOrderIds = filledBrokerOrderIds(rawOrders)
                val brokerHistory = deriveClosedTrades(environment.fillActivities(key, secret), filledOrderIds, next.openTickets, orders)
                val (fillSynced, verifiedClosed) = storeVerifiedTicketClosures(next, brokerHistory, orders, filledOrderIds)
                next = fillSynced
                val imported = importHistoricalClosedTrades(brokerHistory, rawOrders, next)
                imported.forEach { trade -> next = TradeJournalEngine.recordTrade(next, trade) }
                journalState = journalStore.save(next)
                val summary = "Journal synced: ${result.closed.size} bracket(s) reconciled, ${verifiedClosed.size} fill-verified close(s) stored, ${imported.size} historical trade(s) imported, ${result.stillOpen.size} still open, " +
                    "${result.cancelled.size} cancelled, ${result.untouched.size} unmatched; broker orders read=${orders.size}."
                _state.update {
                    it.copy(
                        journal = journalStore.snapshot(journalState),
                        closedTrades = brokerHistory,
                        journalBusy = false,
                        journalSyncedAtEpochMs = System.currentTimeMillis(),
                        journalNotice = journalStore.lastError ?: summary,
                        terminal = appendActivity(
                            it,
                            listOf("JOURNAL" to summary) +
                                result.notes.take(12).map { note -> "JOURNAL" to note } +
                                result.closed.map { trade -> "JOURNAL LESSON" to trade.lesson },
                        ),
                        debuggerTerminal = appendDebug(
                            it,
                            listOf(
                                "JOURNAL" to "orders=${orders.size}; storedTrades=${journalState.trades.size}; openTickets=${journalState.openTickets.size}; file=${journalStore.path}.",
                                "JOURNAL" to "closed=${result.closed.size}; stillOpen=${result.stillOpen.size}; cancelled=${result.cancelled.size}; unmatched=${result.untouched.size}.",
                            ),
                        ),
                    )
                }
            } catch (error: Exception) {
                val message = error.message ?: "order history request failed"
                _state.update {
                    it.copy(
                        journalBusy = false,
                        journalNotice = "Journal sync failed: $message",
                        terminal = appendActivity(it, listOf("JOURNAL ERROR" to message)),
                    )
                }
            }
        }
    }

    /** Imports old broker round trips even when this app did not create a journal ticket for them. */
    private fun importHistoricalClosedTrades(
        closedTrades: List<ClosedTrade>,
        rawOrders: List<JsonObject>,
        existing: JournalState,
    ): List<com.alpacatalk.trading.data.ClosedTrade> {
        fun text(json: JsonObject?, key: String): String? = json?.get(key)?.jsonPrimitive?.contentOrNull
        fun number(json: JsonObject?, key: String): Double? = json?.get(key)?.jsonPrimitive?.doubleOrNull
        fun time(value: String?): Long = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

        val knownIds = existing.trades.map { it.tradeId }.toSet()
        val knownTicketOrderIds = existing.trades.filter { it.ticketId != null }.mapNotNull { it.brokerOrderId }.toSet()
        val result = mutableListOf<com.alpacatalk.trading.data.ClosedTrade>()
        closedTrades.forEach { item ->
            val parent = rawOrders.firstOrNull { order ->
                val legs = (order["legs"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                text(order, "id") == item.exitOrderId || legs.any { text(it, "id") == item.exitOrderId }
            }
            val parentId = text(parent, "id") ?: item.exitOrderId
            val stableId = "broker-history-${item.symbol}-${item.exitOrderId}-${item.closedAt}-${item.quantity}-${item.entryPrice}"
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
            if (stableId in knownIds || parentId in knownTicketOrderIds || item.entryOrderId in knownTicketOrderIds) return@forEach

            val legs = (parent?.get("legs") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val stopLeg = legs.firstOrNull { text(it, "type")?.contains("stop", ignoreCase = true) == true }
            val targetLeg = legs.firstOrNull { text(it, "type")?.contains("limit", ignoreCase = true) == true }
            val stopObject = parent?.get("stop_loss") as? JsonObject
            val targetObject = parent?.get("take_profit") as? JsonObject
            val stop = number(stopLeg, "stop_price") ?: number(stopObject, "stop_price") ?: item.entryPrice
            val target = number(targetLeg, "limit_price") ?: number(targetObject, "limit_price") ?: item.entryPrice
            val hasStop = number(stopLeg, "stop_price") != null || number(stopObject, "stop_price") != null
            val hasTarget = number(targetLeg, "limit_price") != null || number(targetObject, "limit_price") != null
            val side = if (item.side.equals("SHORT", ignoreCase = true) || item.side.equals("SELL", ignoreCase = true)) "sell" else "buy"
            val openedAt = time(text(parent, "filled_at") ?: text(parent, "submitted_at")).takeIf { it > 0L }
                ?: time(item.closedAt).takeIf { it > 0L } ?: System.currentTimeMillis()
            val closedAt = time(item.closedAt).takeIf { it > 0L } ?: System.currentTimeMillis()
            val ticket = TradeTicketFactory.ticket(
                symbol = item.symbol,
                side = side,
                accountMode = _state.value.environment?.label ?: "BROKER HISTORY",
                entryPrice = item.entryPrice,
                stopPrice = stop,
                targetPrice = target,
                quantity = item.quantity,
                setupLabel = "BROKER HISTORY · SETUP/RATING UNKNOWN",
                signalScoreAtEntry = TradeJournalEngine.NEUTRAL_RATING,
                appRatingAtEntry = TradeJournalEngine.NEUTRAL_RATING,
                directiveAtEntry = TradeDirective.ALLOW,
                entryType = "historical broker import",
                openedAtEpochMs = openedAt,
                clientOrderId = "history-${item.exitOrderId}",
                brokerOrderId = parentId,
                notes = buildString {
                    append("Imported from Alpaca fill history. Historical setup and app rating were not available; 50/100 is a neutral placeholder.")
                    if (!hasStop || !hasTarget) append(" Broker history did not expose ${if (!hasStop && !hasTarget) "stop-loss or take-profit levels" else if (!hasStop) "the stop-loss level" else "the take-profit level"}; missing levels are shown as entry price/0 pips, not inferred.")
                },
            )
            val outcome = when {
                item.pnl > 0.005 -> TradeOutcome.WIN
                item.pnl < -0.005 -> TradeOutcome.LOSS
                else -> TradeOutcome.BREAKEVEN
            }
            result += TradeTicketFactory.close(
                ticket = ticket,
                exitPrice = item.exitPrice,
                closedAtEpochMs = closedAt,
                source = TradeJournalSource.BROKER_SYNC,
                outcomeOverride = outcome,
                realizedPnlOverride = item.pnl,
                brokerOrderId = parentId,
                tradeIdOverride = stableId,
                notes = ticket.notes,
            )
        }
        return result
    }

    /** Records a trade the user typed in, then re-rates the setup from the updated journal. */
    fun recordManualTrade(entry: ManualJournalEntry) {
        val decision = TradeJournalEngine.decision(_state.value.journal, entry.symbol, entry.setupLabel)
        val trade = TradeTicketFactory.manualTrade(
            symbol = entry.symbol,
            side = entry.side,
            accountMode = entry.accountMode,
            setupLabel = entry.setupLabel,
            entryPrice = entry.entryPrice,
            stopPrice = entry.stopPrice,
            targetPrice = entry.targetPrice,
            exitPrice = entry.exitPrice,
            quantity = entry.quantity,
            signalScoreAtEntry = entry.signalScore,
            appRatingAtEntry = entry.appRating ?: TradeJournalEngine.appRatingForTrade(entry.signalScore, decision),
            directiveAtEntry = entry.directiveAtEntry ?: decision.directive,
            openedAtEpochMs = entry.openedAtEpochMs,
            closedAtEpochMs = entry.closedAtEpochMs,
            outcomeOverride = entry.outcomeOverride,
            notes = entry.notes,
        )
        commitTrade(trade, "JOURNAL")
    }

    /** Closes one of the app's own tickets with a result the user saw at the broker. */
    fun closeOpenTicket(ticketId: String, exitPrice: Double, outcomeOverride: TradeOutcome?) {
        val ticket = journalState.openTickets.firstOrNull { it.ticketId == ticketId }
        if (ticket == null) {
            _state.update { it.copy(journalNotice = "That ticket is no longer open.") }
            return
        }
        val trade = TradeTicketFactory.close(
            ticket = ticket,
            exitPrice = exitPrice,
            closedAtEpochMs = System.currentTimeMillis(),
            source = TradeJournalSource.MANUAL,
            outcomeOverride = outcomeOverride,
            notes = "Closed from the journal screen with the exit price the user observed.",
        )
        commitTrade(trade, "JOURNAL CLOSE")
    }

    /** Forgets one open ticket without journalling anything, for orders that never became a trade. */
    fun discardOpenTicket(ticketId: String) {
        val next = TradeJournalEngine.removeTicket(journalState, ticketId)
        if (next.openTickets.size == journalState.openTickets.size) {
            _state.update { it.copy(journalNotice = "That ticket is no longer open.") }
            return
        }
        journalState = journalStore.save(next)
        _state.update {
            it.copy(
                journal = journalStore.snapshot(journalState),
                journalNotice = "Open ticket discarded; the memory is unchanged.",
                terminal = appendActivity(it, listOf("JOURNAL" to "Discarded an open ticket without journalling it.")),
            )
        }
    }

    /** Deletes a stored trade and honestly rewinds the memory that was built from it. */
    fun deleteJournalTrade(tradeId: String) {
        val next = TradeJournalEngine.removeTrade(journalState, tradeId)
        if (next.trades.size == journalState.trades.size) return
        journalState = journalStore.save(next)
        _state.update {
            it.copy(
                journal = journalStore.snapshot(journalState),
                journalNotice = "Trade deleted; every affected setup rating was recomputed from the remaining trades.",
                terminal = appendActivity(it, listOf("JOURNAL" to "Removed one stored trade and rewound the setup memory.")),
            )
        }
    }

    /** Clears the memory of one setup, symbol-specific or all-markets, so it starts at 50/100 again. */
    fun forgetSetup(memoryKey: String) {
        val next = TradeJournalEngine.forget(journalState, memoryKey)
        val removed = journalState.trades.size - next.trades.size
        if (removed == 0) {
            _state.update { it.copy(journalNotice = "Nothing to forget for that setup.") }
            return
        }
        journalState = journalStore.save(next)
        _state.update {
            it.copy(
                journal = journalStore.snapshot(journalState),
                journalNotice = "Forgot ${TradeJournalEngine.displayKey(memoryKey)}: $removed stored trade(s) removed and ratings recomputed.",
                terminal = appendActivity(it, listOf("JOURNAL" to "Forgot setup ${TradeJournalEngine.displayKey(memoryKey)}; $removed trade(s) removed.")),
            )
        }
    }

    /** The whole journal as CSV, ready for the clipboard or a spreadsheet. */
    fun journalCsv(): String = journalStore.exportCsv(journalState)

    /** The memory decision the app would apply to this symbol and setup right now. */
    fun memoryDecision(symbol: String, setupLabel: String): TradeMemoryDecision =
        TradeJournalEngine.decision(_state.value.journal, symbol, setupLabel)

    private fun commitTrade(trade: com.alpacatalk.trading.data.ClosedTrade, tag: String) {
        journalState = journalStore.save(TradeJournalEngine.recordTrade(journalState, trade))
        val snapshot = journalStore.snapshot(journalState)
        val memory = snapshot.memories.firstOrNull { it.key == trade.memoryKey }
        val message = "Journalled ${TradeMath.sideLabel(trade.side)} ${trade.symbol}: ${trade.outcome.name} ${TradeMath.formatPips(trade.resultPips)} ${TradeMath.pipLabel(trade.symbol)} " +
            "(stop ${trade.stopPips}, target ${trade.targetPips}, app rating at entry ${trade.appRatingAtEntry}/100). " +
            "Setup memory is now ${memory?.rating ?: TradeJournalEngine.NEUTRAL_RATING}/100 → ${memory?.directive?.name ?: TradeDirective.ALLOW.name}."
        _state.update {
            it.copy(
                journal = snapshot,
                journalNotice = journalStore.lastError ?: message,
                terminal = appendActivity(it, listOf(tag to message, "JOURNAL LESSON" to trade.lesson)),
                debuggerTerminal = appendDebug(
                    it,
                    listOf("JOURNAL" to "memoryKey=${trade.memoryKey}; weighted=${memory?.weightedWins}/${memory?.weightedLosses}; highRatedLosses=${memory?.highRatedLosses}; adjustment=${memory?.scoreAdjustment}."),
                ),
            )
        }
    }

    private fun recordOpenTicket(
        setup: MarketSnapshot,
        environment: DirectEnvironment,
        submission: BracketSubmission,
        orderId: String,
        appRating: Int,
        directive: TradeDirective,
    ) {
        val ticket = TradeTicketFactory.ticket(
            symbol = setup.symbol,
            side = setup.direction?.orderSide ?: "buy",
            accountMode = environment.name,
            entryPrice = submission.referencePrice,
            stopPrice = submission.stopPrice,
            targetPrice = submission.targetPrice,
            quantity = submission.quantity,
            setupLabel = setup.setup,
            signalScoreAtEntry = setup.score,
            appRatingAtEntry = appRating,
            directiveAtEntry = directive,
            entryType = if (setup.symbol in SECOND_BASKET_SYMBOLS) "limit" else "market",
            openedAtEpochMs = System.currentTimeMillis(),
            clientOrderId = submission.clientOrderId,
            brokerOrderId = orderId,
            notes = "Opened ${setup.direction?.label ?: "LONG"} by the app at ${setup.score}/100 with an app rating of $appRating/100.",
        )
        journalState = journalStore.save(TradeJournalEngine.recordTickets(journalState, listOf(ticket)))
        _state.update {
            it.copy(
                journal = journalStore.snapshot(journalState),
                terminal = appendActivity(
                    it,
                    listOf(
                        "JOURNAL" to "Ticket stored for ${setup.symbol}: stop ${ticket.stopPips} ${TradeMath.pipLabel(setup.symbol)}, target ${ticket.targetPips} ${TradeMath.pipLabel(setup.symbol)}, " +
                            "app rating ${ticket.appRatingAtEntry}/100, directive ${directive.name}. Sync the journal after the bracket closes and the result is remembered automatically.",
                    ),
                ),
            )
        }
    }

    /**
     * OANDA market orders have no order-history route in this app, so they are journalled as open
     * tickets and closed from the journal screen with the exit price the user observed.
     */
    private fun journalOandaTicket(environment: OandaEnvironment, units: String, stop: String, target: String): Pair<String, String>? {
        val size = units.toDoubleOrNull() ?: return null
        if (size == 0.0) return null
        val mid = oandaMidQuote()
            ?: return "JOURNAL" to "This OANDA trade was not journalled automatically because no XAU_USD price was available for its entry. Add it with 'Record a closed trade'."
        val setupLabel = "OANDA GOLD MARKET"
        val decision = TradeJournalEngine.decision(_state.value.journal, "XAU/USD", setupLabel)
        val ticket = TradeTicketFactory.ticket(
            symbol = "XAU/USD",
            side = if (size < 0) "sell" else "buy",
            accountMode = environment.label,
            entryPrice = mid,
            stopPrice = stop.toDoubleOrNull() ?: 0.0,
            targetPrice = target.toDoubleOrNull() ?: 0.0,
            quantity = abs(size),
            setupLabel = setupLabel,
            signalScoreAtEntry = 0,
            appRatingAtEntry = decision.rating,
            directiveAtEntry = decision.directive,
            entryType = "market",
            openedAtEpochMs = System.currentTimeMillis(),
            clientOrderId = TradeTicketFactory.newClientOrderId(),
            notes = "OANDA XAU_USD market order; entry recorded from the live bid/ask midpoint.",
        )
        journalState = journalStore.save(TradeJournalEngine.recordTickets(journalState, listOf(ticket)))
        _state.update { it.copy(journal = journalStore.snapshot(journalState)) }
        return "JOURNAL" to "Ticket stored for XAU/USD at ${TradeMath.formatPrice(mid)}: stop ${ticket.stopPips} pips, target ${ticket.targetPips} pips. Close it from the journal screen when you exit, and the result is remembered."
    }

    private fun oandaMidQuote(): Double? = runCatching {
        val prices = _state.value.oandaQuote?.get("prices") as? JsonArray ?: return@runCatching null
        val first = prices.firstOrNull() as? JsonObject ?: return@runCatching null
        val bid = (first["bids"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("price")?.jsonPrimitive?.doubleOrNull
        val ask = (first["asks"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("price")?.jsonPrimitive?.doubleOrNull
        when {
            bid != null && ask != null -> (bid + ask) / 2.0
            ask != null -> ask
            else -> bid
        }
    }.getOrNull()


    fun disconnect() {
        stopAutoTrading("Stopped on disconnect.")
        accountRefreshJob?.cancel()
        accountRefreshJob = null
        apiKey = null
        apiSecret = null
        oandaToken = null
        ctraderClientId = null
        ctraderClientSecret = null
        ctraderToken = null
        _state.value = DirectTradingState(
            info = "Disconnected. API credentials cleared from memory.",
            journal = journalStore.snapshot(journalState),
            journalNotice = "Trade journal and setup ratings remain saved on this phone.",
        )
    }

    override fun onCleared() {
        autoJob?.cancel()
        accountRefreshJob?.cancel()
        apiKey = null
        apiSecret = null
        super.onCleared()
    }

    private fun parseSymbols(input: String): List<String> = input
        .split(',', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(String::uppercase)
        .distinct()
        .take(10)

    private fun stopAutoTrading(message: String) {
        autoJob?.cancel()
        autoJob = null
        appContext.getSharedPreferences(BackgroundScanService.PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(BackgroundScanService.KEY_ENABLED, false).apply()
        appContext.startService(Intent(appContext, BackgroundScanService::class.java).apply { action = BackgroundScanService.ACTION_STOP })
        _state.update {
            it.copy(
                autoTrading = false,
                scanMonitorPaused = false,
                scanMonitorStatus = message,
                autoScanProgress = "Monitor stopped. Last complete auto-scan findings remain available below.",
                terminal = appendActivity(it, listOf("SCAN MONITOR" to message)),
                debuggerTerminal = appendDebug(it, listOf("SCAN MONITOR" to message)),
            )
        }
    }

    private fun activityForSnapshot(snapshot: MarketSnapshot): String =
        "${snapshot.symbol}; price=${format(snapshot.lastPrice)}; score=${snapshot.score}/100 (scalp=${snapshot.scalpScore}, swing=${snapshot.swingScore}); Δday=${format(snapshot.dayChangePct)}%; trend=${format(snapshot.trendPct)}%; volatility=${format(snapshot.volatilityPct)}%; bars=${snapshot.historyBars}; ${snapshot.setup}."

    private fun debugForSnapshot(snapshot: MarketSnapshot): List<String> = listOf(
        "symbol=${snapshot.symbol}; price=${format(snapshot.lastPrice)}; score=${snapshot.score}; scalp=${snapshot.scalpScore}; swing=${snapshot.swingScore}; executable=${snapshot.isExecutableSetup}; gate=${snapshot.executionGate}.",
        "symbol=${snapshot.symbol}; ${snapshot.rationale}",
    ) + snapshot.timeframeDiagnostics.map { diagnostic -> "symbol=${snapshot.symbol}; $diagnostic" }

    private fun appendActivity(current: DirectTradingState, entries: List<Pair<String, String>>): List<String> =
        (current.terminal + entries.map { (tag, message) -> activity(tag, message) }).takeLast(MAX_ACTIVITY_LINES)

    private fun appendDebug(current: DirectTradingState, entries: List<Pair<String, String>>): List<String> =
        (current.debuggerTerminal + entries.map { (tag, message) -> debug(tag, message) }).takeLast(MAX_DEBUG_LINES)

    private fun activity(tag: String, message: String): String = "${Instant.now()} [$tag] $message"

    private fun debug(tag: String, message: String): String = "${Instant.now()} [$tag] $message"

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private companion object {
        val SECOND_BASKET_SYMBOLS = setOf("AVGO", "ORCL", "COST", "CRM", "ADBE", "INTC", "QCOM", "MU", "BA", "DIS", "VOO", "IVV", "SCHD", "VUG", "VTV", "RSP", "MDY", "VB", "VXUS", "ACWI", "GDX", "GDXJ", "SIL", "SILJ", "COPX", "DBA", "DBC", "BOIL", "KOLD", "UUP", "USD/SEK", "USD/NOK", "USD/SGD", "USD/HKD", "USD/MXN", "USD/ZAR", "EUR/CHF", "EUR/AUD", "GBP/AUD", "AUD/JPY", "XLV", "XLP", "XLY", "XLC", "XLB", "XLI", "XLU", "XRT", "KRE", "EWT")
        const val MAX_ACTIVITY_LINES = 500
        const val MAX_DEBUG_LINES = 1_500
        const val MAX_JOURNAL_ENTRIES = 500
    }
}
