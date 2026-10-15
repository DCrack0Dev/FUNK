package com.alpacatalk.trading

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.alpacatalk.trading.data.CredentialVault
import com.alpacatalk.trading.data.DirectEnvironment
import com.alpacatalk.trading.data.JournalSnapshot
import com.alpacatalk.trading.data.OpenStopRiskCalculator
import com.alpacatalk.trading.data.NotionalExposurePolicy
import com.alpacatalk.trading.data.OrderNotional
import com.alpacatalk.trading.data.PositionNotional
import com.alpacatalk.trading.data.ScannerBaskets
import com.alpacatalk.trading.data.TradeGeometry
import com.alpacatalk.trading.data.TradeTicketFactory
import com.alpacatalk.trading.data.TradeEligibilityRules
import com.alpacatalk.trading.ui.ScanAlertNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Background monitor. Scans and posts local notifications by default. The only order mutation is
 * the explicit Paper-only Submit-all notification action, which revalidates every setup immediately
 * before submission; it never replaces, cancels, or manages existing broker orders.
 */
class BackgroundScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var monitorPrefs: android.content.SharedPreferences
    private var lastAlertSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        monitorPrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val notification = ScanAlertNotifications.monitorNotification(this, "Starting read-only setup monitor")
        if (notification == null) {
            stopSelf()
            return
        }
        startForeground(ScanAlertNotifications.MONITOR_NOTIFICATION_ID, notification)
        scope.launch { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONFIRM_ALL) {
            scope.launch { submitAllQualifyingOrders() }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP || !monitorPrefs.getBoolean(KEY_ENABLED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private suspend fun monitorLoop() {
        try {
            while (scope.isActive && monitorPrefs.getBoolean(KEY_ENABLED, false)) {
                if (monitorPrefs.getBoolean(KEY_APP_VISIBLE, false)) {
                    updateForeground("App open · UI auto scanner is active; background monitor is standing by")
                    delay(5_000L)
                    continue
                }
                val result = runCatching { scanOnce() }
                val status = result.fold(
                    onSuccess = { it },
                    onFailure = { error -> "Paused: ${error.message ?: error.javaClass.simpleName}" },
                )
                updateForeground(status)
                delay(SCAN_INTERVAL_MS)
            }
        } catch (_: CancellationException) {
            // Normal service shutdown.
        } finally {
            stopSelf()
        }
    }

    private suspend fun scanOnce(): String {
        val credentials = CredentialVault(applicationContext).read(ALPACA_KEY)
        val key = credentials["apiKey"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Saved Alpaca credentials are unavailable; reconnect and enable Remember credentials.")
        val secret = credentials["apiSecret"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Saved Alpaca credentials are incomplete; reconnect and save them again.")
        val environment = when (monitorPrefs.getString(KEY_ENVIRONMENT, DirectEnvironment.PAPER.name)) {
            DirectEnvironment.LIVE.name -> DirectEnvironment.LIVE
            else -> DirectEnvironment.PAPER
        }
        val account = environment.account(key, secret)
        val positions = environment.positions(key, secret)
        val openOrders = environment.openOrders(key, secret)
        val risk = OpenStopRiskCalculator.calculate(account, positions, openOrders)
        if (!risk.isVerified || !risk.isFresh()) {
            throw IllegalStateException("waiting for verified stop-risk state: ${risk.issue ?: "snapshot stale"}")
        }
        if (risk.capReached) {
            throw IllegalStateException("open downside risk has reached the configured cap")
        }

        val snapshots = scanSnapshots(environment, key, secret)
        val qualifying = snapshots.filter {
            TradeEligibilityRules.evaluate(it, environment, JournalSnapshot.EMPTY, 72).eligible
        }
        if (qualifying.isEmpty()) {
            return "Monitoring ${ScannerBaskets.allSymbols.size} markets · no qualifying setup · next scan in 60s"
        }
        val signature = qualifying.joinToString("|") { "${it.symbol}:${it.direction}:${it.score}:${"%.2f".format(java.util.Locale.US, it.rewardRisk ?: 0.0)}" }
        if (signature != lastAlertSignature) {
            val details = qualifying.take(12).map {
                "${it.symbol} · ${it.direction?.label ?: "NO SIDE"} · ${it.score}/100 · R:R 1:${"%.2f".format(java.util.Locale.US, it.rewardRisk ?: 0.0)}"
            }
            ScanAlertNotifications.showQualifyingSetups(this, details, ScannerBaskets.allSymbols.size, qualifying.size, includeSubmitAllAction = true)
            lastAlertSignature = signature
        }
        return "Monitoring ${ScannerBaskets.allSymbols.size} markets · ${qualifying.size} qualifying setup(s) · notification ${if (signature == lastAlertSignature) "updated when changed" else "sent"}"
    }

    private suspend fun submitAllQualifyingOrders() {
        val submitted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        try {
            val credentials = CredentialVault(applicationContext).read(ALPACA_KEY)
            val key = credentials["apiKey"]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("saved Alpaca key unavailable")
            val secret = credentials["apiSecret"]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("saved Alpaca secret unavailable")
            val environment = DirectEnvironment.PAPER
            if (environment.marketClock(key, secret).text("is_open")?.equals("true", ignoreCase = true) != true) {
                throw IllegalStateException("regular U.S. equity market is closed")
            }
            val account = environment.account(key, secret)
            val positions = environment.positions(key, secret)
            val openOrders = environment.openOrders(key, secret)
            val risk = OpenStopRiskCalculator.calculate(account, positions, openOrders)
            if (!risk.isFresh() || risk.capReached) throw IllegalStateException(risk.issue ?: "stop-risk verification failed or cap reached")
            val equity = account.text("equity")?.toDoubleOrNull()?.takeIf { it > 0.0 }
                ?: throw IllegalStateException("verified account equity unavailable")
            val snapshots = scanSnapshots(environment, key, secret)
            val qualifying = snapshots.filter { TradeEligibilityRules.evaluate(it, environment, JournalSnapshot.EMPTY, 72).eligible }
            if (qualifying.isEmpty()) {
                ScanAlertNotifications.showActionResult(this, "No orders submitted", listOf("No setups still pass every gate after fresh revalidation."))
                return
            }
            qualifying.forEach { setup ->
                try {
                    val latestPositions = environment.positions(key, secret)
                    val latestOrders = environment.openOrders(key, secret)
                    val latestAccount = environment.account(key, secret)
                    val freshRisk = OpenStopRiskCalculator.calculate(latestAccount, latestPositions, latestOrders)
                    if (!freshRisk.isFresh() || freshRisk.capReached) throw IllegalStateException("fresh stop-risk verification failed")
                    val gross = grossExposure(latestPositions, latestOrders, setup.lastPrice)
                    val stop = setup.scannerStopPrice ?: throw IllegalStateException("stop missing")
                    val target = setup.scannerTargetPrice ?: throw IllegalStateException("target missing")
                    val side = setup.direction?.orderSide ?: throw IllegalStateException("direction missing")
                    val remaining = NotionalExposurePolicy.remainingCapacityDollars(equity, gross) ?: throw IllegalStateException("notional capacity unavailable")
                    val plan = TradeGeometry.plan(side == "buy", setup.lastPrice, stop, target, equity, maximumNotionalDollars = remaining)
                    val batchRisk = equity * 0.005 * qualifying.size
                    val riskGate = com.alpacatalk.trading.data.PortfolioRiskPolicy.assessStopRisk(equity, freshRisk.downsideRiskDollars ?: throw IllegalStateException("risk unavailable"), plan.estimatedRiskDollars, maxOf(plan.estimatedRiskDollars, batchRisk))
                    if (!riskGate.allowed) throw IllegalStateException(riskGate.reason ?: "portfolio risk gate blocked")
                    val submission = environment.submitBracketOrder(key, secret, setup.symbol, side, stop, target, clientOrderId = TradeTicketFactory.newClientOrderId(), equityDollars = equity, currentGrossExposureDollars = gross)
                    submitted += "${setup.symbol} ${side.uppercase()} qty=${submission.quantity.toInt()}"
                } catch (error: Exception) {
                    skipped += "${setup.symbol}: ${error.message ?: "revalidation failed"}"
                }
            }
        } catch (error: Exception) {
            skipped += "All orders: ${error.message ?: "confirmation failed"}"
        }
        val details = buildList {
            if (submitted.isNotEmpty()) add("Submitted: ${submitted.joinToString(", ")}")
            if (skipped.isNotEmpty()) addAll(skipped.take(10).map { "Skipped: $it" })
            if (isEmpty()) add("No orders were submitted.")
        }
        ScanAlertNotifications.showActionResult(this, "Paper order confirmation complete", details)
    }

    private fun grossExposure(
        positions: List<kotlinx.serialization.json.JsonObject>,
        orders: List<kotlinx.serialization.json.JsonObject>,
        fallbackPrice: Double,
    ): Double {
        val positionNotionals = positions.map { position ->
            PositionNotional(position.text("symbol") ?: "?", position.text("market_value")?.toDoubleOrNull(), position.text("side"))
        }
        val orderNotionals = orders.filter { it["parent_order_id"] == null }.map { order ->
            val qty = order.text("qty")?.toDoubleOrNull()
            val filled = order.text("filled_qty")?.toDoubleOrNull() ?: 0.0
            val remaining = qty?.let { (kotlin.math.abs(it) - kotlin.math.abs(filled)).coerceAtLeast(0.0) }
            val price = order.text("limit_price")?.toDoubleOrNull() ?: order.text("stop_price")?.toDoubleOrNull() ?: fallbackPrice
            OrderNotional(order.text("symbol") ?: "?", remaining?.times(price), order.text("side"))
        }
        return NotionalExposurePolicy.grossNotional(positionNotionals, orderNotionals)
            ?: throw IllegalStateException("gross exposure could not be verified")
    }

    private suspend fun scanSnapshots(environment: DirectEnvironment, key: String, secret: String): List<com.alpacatalk.trading.data.MarketSnapshot> {
        val limiter = Semaphore(ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES)
        return coroutineScope {
            ScannerBaskets.scanBatches().map { batch ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        runCatching { environment.scanMarkets(key, secret, batch, includeControlledDiagnostics = false).snapshots }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().distinctBy { it.symbol }.sortedByDescending { it.score }
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun updateForeground(text: String) {
        ScanAlertNotifications.monitorNotification(this, text)?.let {
            NotificationManagerCompat.from(this).notify(ScanAlertNotifications.MONITOR_NOTIFICATION_ID, it)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.alpacatalk.trading.action.START_BACKGROUND_SCAN"
        const val ACTION_STOP = "com.alpacatalk.trading.action.STOP_BACKGROUND_SCAN"
        const val ACTION_CONFIRM_ALL = "com.alpacatalk.trading.action.CONFIRM_ALL_PAPER_ORDERS"
        const val ALPACA_KEY = "alpaca"
        const val PREFS = "background_scan_monitor"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_APP_VISIBLE = "app_visible"
        private const val SCAN_INTERVAL_MS = 60_000L
    }
}
