package com.alpacatalk.trading.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DirectAlpacaException(message: String) : Exception(message)

data class ParsedCandle(val timestamp: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

internal object AlpacaForexRatesParser {
    fun parse(payload: JsonObject, symbol: String): List<ParsedCandle> {
        val rates = payload["rates"] as? JsonObject ?: return emptyList()
        val value = rates.entries.firstOrNull { it.key.uppercase().replace("/", "") == symbol.uppercase().replace("/", "") }?.value
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val rate = element as? JsonObject ?: return@mapNotNull null
            val t = rate["t"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val midpoint = rate["mp"]?.jsonPrimitive?.doubleOrNull
                ?: ((rate["ap"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null) + (rate["bp"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null)) / 2.0
            if (runCatching { Instant.parse(t) }.isFailure || !midpoint.isFinite()) return@mapNotNull null
            // Forex rates have no volume and no OHLC fields; each observed midpoint is a valid rate sample.
            ParsedCandle(t, midpoint, midpoint, midpoint, midpoint, 0.0)
        }
    }
}

internal object AlpacaBarsParser {
    fun parse(payload: JsonObject, symbol: String): List<ParsedCandle> =
        rawCandles(payload, symbol).mapNotNull(::parseCandle)

    fun describe(payload: JsonObject): String {
        val container = payload["bars"] ?: payload["data"]
        val map = container as? JsonObject
        val arrays = map?.entries?.mapNotNull { (key, value) -> (value as? JsonArray)?.let { key to it.size } }
        val first = rawCandles(payload, map?.keys?.firstOrNull() ?: "").firstOrNull()
        val candle = first?.let { sample ->
            "firstCandle={t:${sample["t"]?.jsonPrimitive?.content ?: "missing"},o:${sample["o"]?.jsonPrimitive?.content ?: sample["open"]?.jsonPrimitive?.content ?: "missing"},h:${sample["h"]?.jsonPrimitive?.content ?: sample["high"]?.jsonPrimitive?.content ?: "missing"},l:${sample["l"]?.jsonPrimitive?.content ?: sample["low"]?.jsonPrimitive?.content ?: "missing"},c:${sample["c"]?.jsonPrimitive?.content ?: sample["close"]?.jsonPrimitive?.content ?: "missing"},v:${sample["v"]?.jsonPrimitive?.content ?: sample["volume"]?.jsonPrimitive?.content ?: "missing"}}"
        } ?: "firstCandle=none"
        return when {
            arrays != null -> "barsObjectKeys=${arrays.map { it.first }.take(10)} sizes=${arrays.take(10).joinToString { "${it.first}:${it.second}" }}; $candle"
            container is JsonArray -> "barsArraySize=${container.size}; $candle"
            else -> "barsContainer=${container?.javaClass?.simpleName ?: "missing"}; topKeys=${payload.keys.take(5)}; $candle"
        }
    }

    private fun rawCandles(payload: JsonObject, symbol: String): List<JsonObject> {
        val container = payload["bars"] ?: payload["data"] ?: payload
        val map = container as? JsonObject
        val exact = map?.get(symbol) as? JsonArray
        val normalized = map?.entries?.firstOrNull { normalize(it.key) == normalize(symbol) }?.value as? JsonArray
        val direct = container as? JsonArray
        return (exact ?: normalized ?: direct)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun parseCandle(json: JsonObject): ParsedCandle? {
        val timestamp = json["t"]?.jsonPrimitive?.content ?: return null
        val open = number(json, "o", "open") ?: return null
        val high = number(json, "h", "high") ?: return null
        val low = number(json, "l", "low") ?: return null
        val close = number(json, "c", "close") ?: return null
        val volume = number(json, "v", "volume") ?: return null
        if (timestamp.isBlank() || runCatching { Instant.parse(timestamp) }.isFailure || listOf(open, high, low, close, volume).any { !it.isFinite() } || volume < 0.0) return null
        return ParsedCandle(timestamp, open, high, low, close, volume)
    }

    private fun number(json: JsonObject, vararg keys: String): Double? = keys.firstNotNullOfOrNull { key -> json[key]?.jsonPrimitive?.doubleOrNull }
    private fun normalize(value: String): String = value.uppercase().replace("/", "")
}

data class BatchScanResult(
    val snapshots: List<MarketSnapshot>,
    val debugLogs: List<String>,
    val requestedSymbols: List<String>,
    val elapsedMs: Long,
)

data class BracketSubmission(
    val order: JsonObject,
    val referencePrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val quantity: Double,
    val usedFreshQuote: Boolean,
    val protectionDistancePct: Double,
    val clientOrderId: String,
)

/** Shared equity bracket geometry: keep take-profit distance at 2× stop distance, including retries. */
internal object BracketPriceRules {
    fun levels(referencePrice: Double, side: String, stopDistancePct: Double): Pair<Double, Double> {
        require(referencePrice.isFinite() && referencePrice >= 0.01) { "Bracket reference price must be at least one cent." }
        require(stopDistancePct.isFinite() && stopDistancePct > 0.0 && stopDistancePct < 0.5) { "Bracket stop distance is outside the supported range." }
        val normalizedSide = side.lowercase()
        require(normalizedSide == "buy" || normalizedSide == "sell") { "Unsupported order side: $side" }
        fun down(value: Double) = BigDecimal.valueOf(value).setScale(2, RoundingMode.FLOOR).toDouble().coerceAtLeast(0.01)
        fun up(value: Double) = BigDecimal.valueOf(value).setScale(2, RoundingMode.CEILING).toDouble().coerceAtLeast(0.01)
        val targetDistancePct = stopDistancePct * 2.0
        return if (normalizedSide == "buy") {
            val stop = down((referencePrice * (1.0 - stopDistancePct)).coerceAtMost(referencePrice - 0.01))
            val target = up(referencePrice * (1.0 + targetDistancePct))
            stop to target
        } else {
            val stop = up((referencePrice * (1.0 + stopDistancePct)).coerceAtLeast(referencePrice + 0.01))
            val target = down(referencePrice * (1.0 - targetDistancePct))
            stop to target
        }
    }
}

private data class BatchPayload(val body: JsonObject, val diagnostic: String)
private data class HistoricalRange(val start: Instant, val end: Instant, val limit: Int)

data class MarketSnapshot(
    val symbol: String,
    val lastPrice: Double,
    val dayChangePct: Double,
    val trendPct: Double,
    val volatilityPct: Double,
    val historyBars: Int,
    val score: Int,
    val setup: String,
    val isExecutableSetup: Boolean,
    val rationale: String,
    val scalpScore: Int,
    val swingScore: Int,
    val scalpRationale: String,
    val swingRationale: String,
    val executionGate: String,
    val timeframeDiagnostics: List<String>,
    val direction: TradeDirection? = TradeDirection.LONG,
    val scannerStopPrice: Double? = null,
    val scannerTargetPrice: Double? = null,
    val dataQualityPassed: Boolean = false,
    val dataQualityReason: String = "Market-data quality has not been verified.",
    val timeframesCovered: Int = 0,
    val quoteTimestampEpochMs: Long? = null,
    val spreadPct: Double? = null,
    val scoreBreakdown: List<String> = emptyList(),
    val rewardRisk: Double? = null,
)

enum class TradeDirection(val label: String, val orderSide: String) {
    LONG("LONG", "buy"),
    SHORT("SHORT", "sell"),
}

internal object AlpacaShortabilityRules {
    fun canOpenShort(status: String?, tradable: Boolean?, shortable: Boolean?, easyToBorrow: Boolean?): Boolean =
        status?.equals("active", ignoreCase = true) == true && tradable == true && shortable == true && easyToBorrow == true
}

enum class DirectEnvironment(val label: String, private val baseUrl: String) {
    PAPER("PAPER TRADING", "https://paper-api.alpaca.markets"),
    LIVE("LIVE TRADING", "https://api.alpaca.markets");

    suspend fun probe(apiKey: String, apiSecret: String): Boolean = requestObject(apiKey, apiSecret, "/v2/account") != null

    suspend fun account(apiKey: String, apiSecret: String): JsonObject =
        requestObject(apiKey, apiSecret, "/v2/account")
            ?: throw DirectAlpacaException("Unable to read the ${label.lowercase()} account.")

    suspend fun marketClock(apiKey: String, apiSecret: String): JsonObject =
        requestObject(apiKey, apiSecret, "/v2/clock")
            ?: throw DirectAlpacaException("Unable to verify the ${label.lowercase()} market clock.")

    suspend fun positions(apiKey: String, apiSecret: String): List<JsonObject> =
        requestArray(apiKey, apiSecret, "/v2/positions")

    /**
     * Returns all broker-side orders that are still working. This includes pending entries and
     * any open protective legs; a filled order is intentionally not presented as pending.
     */
    suspend fun openOrders(apiKey: String, apiSecret: String): List<JsonObject> =
        requestArray(apiKey, apiSecret, "/v2/orders?status=open&direction=desc&limit=500")

    suspend fun closedOrders(apiKey: String, apiSecret: String): List<JsonObject> =
        requestArray(apiKey, apiSecret, "/v2/orders?status=closed&direction=desc&limit=500")

    /** Read-only nested order history used to reconcile the app's own bracket tickets. */
    suspend fun orders(apiKey: String, apiSecret: String, status: String = "all", limit: Int = 200): List<JsonObject> =
        requestArray(apiKey, apiSecret, "/v2/orders?status=$status&direction=desc&limit=${limit.coerceIn(1, 500)}&nested=true")

    suspend fun orderByClientOrderId(apiKey: String, apiSecret: String, clientOrderId: String): JsonObject? {
        val encodedId = URLEncoder.encode(clientOrderId, StandardCharsets.UTF_8.name())
        val response = client.get("$baseUrl/v2/orders:by_client_order_id?client_order_id=$encodedId") {
            authorized(apiKey, apiSecret)
        }
        if (response.status.value == 404) return null
        if (response.status.value !in 200..299) throw DirectAlpacaException("Unable to verify persisted client order id (HTTP ${response.status.value}); no retry is allowed.")
        return response.body()
    }

    /** Returns filled account activities so the app can reconstruct completed round trips. */
    suspend fun fillActivities(apiKey: String, apiSecret: String): List<JsonObject> =
        requestArray(apiKey, apiSecret, "/v2/account/activities?activity_types=FILL&direction=desc&page_size=100")

    suspend fun cancelOrder(apiKey: String, apiSecret: String, orderId: String) {
        if (this != PAPER) throw DirectAlpacaException("Execution blocked: live Alpaca order mutations are disabled in this paper-only build.")
        val response = client.delete("$baseUrl/v2/orders/${pathSegment(orderId)}") {
            authorized(apiKey, apiSecret)
        }
        if (response.status.value !in 200..299) throw brokerError("cancel order", response.status.value, response.bodyAsText())
    }

    /**
     * Requests an opposite-side market order from Alpaca to close the named position. The caller
     * must obtain a deliberate confirmation before invoking this method on a live account.
     */
    suspend fun closePosition(apiKey: String, apiSecret: String, symbol: String): JsonObject {
        if (this != PAPER) throw DirectAlpacaException("Execution blocked: live Alpaca position closing is disabled in this paper-only build.")
        val response = client.delete("$baseUrl/v2/positions/${pathSegment(symbol)}") {
            authorized(apiKey, apiSecret)
            accept(ContentType.Application.Json)
        }
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) throw brokerError("close position", response.status.value, raw)
        return runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw DirectAlpacaException("Alpaca close-position response was invalid JSON (HTTP ${response.status.value}).") }
    }

    internal data class Bar(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val timestampEpochMs: Long = 0L,
    ) {
        companion object {
            fun from(json: JsonObject): Bar? = AlpacaBarsParser.parse(buildJsonObject { put("bars", JsonArray(listOf(json))) }, "").firstOrNull()?.let {
                Bar(it.open, it.high, it.low, it.close, it.volume, runCatching { Instant.parse(it.timestamp).toEpochMilli() }.getOrDefault(0L))
            }
        }
    }

    internal data class SetupStats(
        val dayChangePct: Double,
        val trendPct: Double,
        val volatilityPct: Double,
        val score: Int,
        val aligned: Boolean,
        val vwapConfirmed: Boolean,
        val breakout: Boolean,
        val liquiditySweep: Boolean,
        val volumeExpanded: Boolean,
        val rr: Double,
        val rationale: String,
        val stopPrice: Double = 0.0,
        val targetPrice: Double = 0.0,
        val mode: String = "scalp",
        val barCount: Int = 0,
    )

    suspend fun scanMarket(apiKey: String, apiSecret: String, symbol: String): MarketSnapshot =
        scanMarkets(apiKey, apiSecret, listOf(symbol)).snapshots.firstOrNull()
            ?: throw DirectAlpacaException("No market result returned for $symbol")

    /** Fetches one timeframe for the whole group, then analyzes each symbol independently. */
    suspend fun scanMarkets(
        apiKey: String,
        apiSecret: String,
        symbols: List<String>,
        includeControlledDiagnostics: Boolean = true,
    ): BatchScanResult {
        val scanStartedAtMs = System.currentTimeMillis()
        val cleanSymbols = symbols.map { it.trim().uppercase() }.distinct().take(10)
        if (cleanSymbols.isEmpty()) {
            return BatchScanResult(
                snapshots = emptyList(),
                debugLogs = listOf("[FAIL] No symbols supplied"),
                requestedSymbols = emptyList(),
                elapsedMs = System.currentTimeMillis() - scanStartedAtMs,
            )
        }
        val forex = cleanSymbols.all { it.contains('/') }
        val apiSymbols = if (forex) cleanSymbols.map { it.replace("/", "") } else cleanSymbols
        val timeframes = if (forex) listOf("1Day", "1Min") else listOf("1Day", "1Hour", "15Min", "1Min")
        val seriesBySymbol = cleanSymbols.associateWith { linkedMapOf<String, List<Bar>>() }.toMutableMap()
        val errorsBySymbol = cleanSymbols.associateWith { mutableListOf<String>() }.toMutableMap()
        val debugLogs = mutableListOf<String>()
        debugLogs += "[SCAN] mode=${if (forex) "forex rates" else "stocks"}; requestedSymbols=${cleanSymbols.joinToString(",")}; apiSymbols=${apiSymbols.joinToString(",")}; timeframes=${timeframes.joinToString(",")}; requestStrategy=timeframe-first"
        debugLogs += marketClockDiagnostic()
        if (includeControlledDiagnostics) {
            debugLogs += controlledBarsDiagnostic(apiKey, apiSecret, cleanSymbols)
        } else {
            debugLogs += "[DIAG] Controlled probe requests skipped for the all-basket sweep; primary timeframe/quote requests still report timing and errors."
        }

        // Deliberately timeframe-first: 4 requests per group instead of 4 requests per symbol.
        timeframes.forEach { timeframe ->
            val range = historicalRange(timeframe)
            val started = System.currentTimeMillis()
            val endpointLabel = if (forex) "forex rates" else "stocks"
            debugLogs += "[REQ] $endpointLabel bars timeframe=$timeframe symbols=${cleanSymbols.size}"
            debugLogs += "[RANGE] $timeframe start=${range.start}; end=${range.end}; limit=${range.limit}; requestedFeed=${if (forex) "not-applicable" else "iex"}"
            try {
                val response = requestDataBarsBatch(apiKey, apiSecret, apiSymbols, timeframe, forex, includeFeed = !forex, range = range)
                val payload = response.body
                debugLogs += "[RESP] $timeframe HTTP 2xx in ${System.currentTimeMillis() - started}ms; ${response.diagnostic}; ${AlpacaBarsParser.describe(payload)}"
                cleanSymbols.forEach { symbol ->
                    val bars = findSymbolBars(payload, symbol, forex)
                    seriesBySymbol.getValue(symbol)[timeframe] = bars
                    if (bars.isEmpty()) errorsBySymbol.getValue(symbol) += "$timeframe: empty batch result"
                }
                debugLogs += "[PARSE] $timeframe perSymbolBars=${cleanSymbols.joinToString(",") { symbol -> "$symbol=${seriesBySymbol.getValue(symbol)[timeframe]?.size ?: 0}" }}"
            } catch (first: DirectAlpacaException) {
                debugLogs += "[RETRY] $timeframe ${first.message ?: "request failed"}"
                if (isInsufficientGrant(first.message)) {
                    debugLogs += "[ENTITLEMENT] Alpaca denied forex market data: insufficient grants. Enable forex data entitlements for this account or use a broker/account with FX market-data access."
                    cleanSymbols.forEach { symbol ->
                        seriesBySymbol.getValue(symbol)[timeframe] = emptyList()
                        errorsBySymbol.getValue(symbol) += "$timeframe: insufficient forex market-data grants"
                    }
                    return@forEach
                }
                try {
                    val response = requestDataBarsBatch(apiKey, apiSecret, apiSymbols, timeframe, forex, includeFeed = false, range = range)
                    val payload = response.body
                    debugLogs += "[RESP] $timeframe fallback HTTP 2xx in ${System.currentTimeMillis() - started}ms; ${response.diagnostic}; ${AlpacaBarsParser.describe(payload)}"
                    cleanSymbols.forEach { symbol ->
                        val bars = findSymbolBars(payload, symbol, forex)
                        seriesBySymbol.getValue(symbol)[timeframe] = bars
                        if (bars.isEmpty()) errorsBySymbol.getValue(symbol) += "$timeframe: empty fallback batch result"
                    }
                    debugLogs += "[PARSE] $timeframe fallback perSymbolBars=${cleanSymbols.joinToString(",") { symbol -> "$symbol=${seriesBySymbol.getValue(symbol)[timeframe]?.size ?: 0}" }}"
                } catch (second: DirectAlpacaException) {
                    debugLogs += "[FAIL] $timeframe ${second.message ?: first.message ?: "batch request failed"}"
                    cleanSymbols.forEach { symbol ->
                        seriesBySymbol.getValue(symbol)[timeframe] = emptyList()
                        errorsBySymbol.getValue(symbol) += "$timeframe: ${second.message ?: first.message ?: "batch request failed"}"
                    }
                }
            }
        }

        if (forex) {
            cleanSymbols.forEach { symbol ->
                val oneMinute = seriesBySymbol.getValue(symbol)["1Min"].orEmpty()
                seriesBySymbol.getValue(symbol)["15Min"] = aggregateBars(oneMinute, 15)
                seriesBySymbol.getValue(symbol)["1Hour"] = aggregateBars(oneMinute, 60)
            }
            debugLogs += "[DERIVE] Forex 15Min/1Hour aggregated locally from 1Min midpoint rates; volume unavailable"
        }

        val latest = try {
            val started = System.currentTimeMillis()
            val result = requestDataObject(apiKey, apiSecret,
                if (forex) "/v1beta1/forex/latest/rates?currency_pairs=${apiSymbols.joinToString(",")}"
                else "/v2/stocks/quotes/latest?symbols=${cleanSymbols.joinToString(",")}&feed=iex")
            debugLogs += "[RESP] quotes HTTP 2xx in ${System.currentTimeMillis() - started}ms"
            result
        } catch (error: DirectAlpacaException) {
            debugLogs += "[FAIL] quotes ${error.message ?: "quote request failed"}"
            null
        }
        val quotes = (latest?.get("quotes") ?: latest?.get("rates")) as? JsonObject
        val quoteSymbols = cleanSymbols.filter { symbol ->
            val quote = (quotes?.get(symbol) as? JsonObject)
                ?: (quotes?.entries?.firstOrNull { normalizeSymbol(it.key) == normalizeSymbol(symbol) }?.value as? JsonObject)
                ?: (latest?.get("quote") as? JsonObject)
            quote != null
        }
        val missingQuotes = cleanSymbols.filterNot(quoteSymbols::contains)
        debugLogs += "[QUOTE] available=${quoteSymbols.size}/${cleanSymbols.size}; resolved=${quoteSymbols.joinToString(",").ifBlank { "none" }}; missing=${missingQuotes.joinToString(",").ifBlank { "none" }}"
        val snapshots = cleanSymbols.mapNotNull { symbol ->
            val quote = (quotes?.get(symbol) as? JsonObject)
                ?: (quotes?.entries?.firstOrNull { normalizeSymbol(it.key) == normalizeSymbol(symbol) }?.value as? JsonObject)
                ?: (latest?.get("quote") as? JsonObject)
            try {
                buildSnapshot(symbol, seriesBySymbol.getValue(symbol), errorsBySymbol.getValue(symbol), quote)
            } catch (_: DirectAlpacaException) {
                null
            }
        }
        val totalBars = seriesBySymbol.values.sumOf { map -> map.values.sumOf { it.size } }
        debugLogs += "[PARSE] symbols=${snapshots.size}/${cleanSymbols.size}; parsedBars=$totalBars"
        snapshots.forEach { snapshot ->
            debugLogs += "[SETUP] ${snapshot.symbol} price=${format(snapshot.lastPrice)} score=${snapshot.score}/100 scalp=${snapshot.scalpScore} swing=${snapshot.swingScore} executable=${snapshot.isExecutableSetup}; gate=${snapshot.executionGate}"
            snapshot.timeframeDiagnostics.forEach { diagnostic -> debugLogs += "[TIMEFRAME] ${snapshot.symbol} $diagnostic" }
        }
        val actionableCount = snapshots.count { it.isExecutableSetup && (it.rewardRisk ?: 0.0) + 1e-9 >= TradeGeometry.MIN_SCAN_REWARD_RISK }
        val excludedCount = snapshots.size - actionableCount
        debugLogs += "[FILTER] actionable=${actionableCount}/${snapshots.size}; blockedForDisplay=${excludedCount}; minimumRewardRisk=1:${format(TradeGeometry.MIN_SCAN_REWARD_RISK)}"
        val elapsedMs = System.currentTimeMillis() - scanStartedAtMs
        if (snapshots.isEmpty() && forex) {
            debugLogs += "[ACTION] No forex setups were produced because Alpaca returned no permitted forex data. This is an account entitlement issue, not a strategy signal."
        }
        debugLogs += "[DONE] scanElapsed=${elapsedMs}ms; findings=${snapshots.size}; actionable=${actionableCount}; requested=${cleanSymbols.size}; parsedBars=$totalBars"
        return BatchScanResult(snapshots, debugLogs, cleanSymbols, elapsedMs)
    }

    private fun isInsufficientGrant(message: String?): Boolean =
        message?.contains("insufficient grants", ignoreCase = true) == true ||
            message?.contains("forbidden", ignoreCase = true) == true

    private fun historicalRange(timeframe: String): HistoricalRange {
        val end = Instant.now()
        val lookbackSeconds = when (timeframe) {
            "1Day" -> 45L * 24L * 60L * 60L
            "1Hour" -> 30L * 24L * 60L * 60L
            "15Min" -> 14L * 24L * 60L * 60L
            "1Min" -> 3L * 24L * 60L * 60L
            else -> 14L * 24L * 60L * 60L
        }
        val limit = when (timeframe) {
            "1Day" -> 1000
            "1Hour" -> 1000
            "15Min" -> 1000
            "1Min" -> 10000
            else -> 1000
        }
        return HistoricalRange(end.minusSeconds(lookbackSeconds), end, limit)
    }

    private fun marketClockDiagnostic(): String {
        val utc = Instant.now()
        val ny = ZonedDateTime.now(ZoneId.of("America/New_York"))
        val weekday = ny.dayOfWeek.value in 1..5
        val minutes = ny.hour * 60 + ny.minute
        val regularHours = minutes in (9 * 60 + 30) until (16 * 60)
        return "[CLOCK] utc=$utc; NewYork=${ny.toLocalDate()}T${ny.toLocalTime()}; weekday=$weekday; regularSessionWindow=$regularHours"
    }

    private suspend fun controlledBarsDiagnostic(apiKey: String, apiSecret: String, symbols: List<String>): List<String> {
        if (symbols.all { it.contains('/') }) return controlledForexDiagnostic(apiKey, apiSecret, symbols)
        val now = Instant.now()
        val dailyStart = now.minusSeconds(14L * 24L * 60L * 60L)
        val minuteStart = now.minusSeconds(3L * 24L * 60L * 60L)
        val encodedNow = now.toString()
        val encodedDailyStart = dailyStart.toString()
        val encodedMinuteStart = minuteStart.toString()
        val fullSymbols = symbols.joinToString(",")
        val probes = listOf(
            "AAPL daily IEX" to "/v2/stocks/bars?symbols=AAPL&timeframe=1Day&start=$encodedDailyStart&end=$encodedNow&limit=100&sort=asc&feed=iex",
            "AAPL 1Min IEX" to "/v2/stocks/bars?symbols=AAPL&timeframe=1Min&start=$encodedMinuteStart&end=$encodedNow&limit=1000&sort=asc&feed=iex",
            "batch daily IEX" to "/v2/stocks/bars?symbols=$fullSymbols&timeframe=1Day&start=$encodedDailyStart&end=$encodedNow&limit=1000&sort=asc&feed=iex",
        )
        return probes.map { (label, path) ->
            val url = "$DATA_BASE_URL$path"
            try {
                val started = System.currentTimeMillis()
                val response = client.get(url) { authorized(apiKey, apiSecret) }
                val raw = response.bodyAsText()
                val preview = raw.replace(Regex("\\s+"), " ").take(300)
                val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                val bars = body?.get("bars") as? JsonObject
                val counts = bars?.entries?.mapNotNull { (key, value) -> (value as? JsonArray)?.let { "$key:${it.size}" } }?.joinToString(",") ?: "none"
                "[PROBE] $label status=${response.status.value} ms=${System.currentTimeMillis() - started} bodyChars=${raw.length} symbols=$fullSymbols bars=${bars?.keys?.joinToString(",") ?: "none"} counts=$counts preview=$preview"
            } catch (error: Exception) {
                "[PROBE] $label ERROR=${error.message ?: "request failed"} url=$url"
            }
        }
    }

    private suspend fun controlledForexDiagnostic(apiKey: String, apiSecret: String, symbols: List<String>): List<String> {
        val now = Instant.now()
        val start = now.minusSeconds(3L * 24L * 60L * 60L)
        val pairs = symbols.map { it.replace("/", "") }
        val probes = listOf(
            "EURUSD 1Min" to "/v1beta1/forex/rates?currency_pairs=EURUSD&timeframe=1Min&start=$start&end=$now&limit=1000&sort=asc",
            "EURUSD 1Day" to "/v1beta1/forex/rates?currency_pairs=EURUSD&timeframe=1D&start=$start&end=$now&limit=100&sort=asc",
            "Forex batch 1Min" to "/v1beta1/forex/rates?currency_pairs=${pairs.joinToString(",")}&timeframe=1Min&start=$start&end=$now&limit=1000&sort=asc",
            "Forex latest" to "/v1beta1/forex/latest/rates?currency_pairs=${pairs.joinToString(",")}"
        )
        return probes.map { (label, path) ->
            val url = "$DATA_BASE_URL$path"
            try {
                val started = System.currentTimeMillis()
                val response = client.get(url) { authorized(apiKey, apiSecret) }
                val raw = response.bodyAsText()
                val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                val rates = body?.get("rates") as? JsonObject
                val counts = rates?.entries?.mapNotNull { (key, value) -> (value as? JsonArray)?.let { "$key:${it.size}" } }?.joinToString(",")
                    ?: rates?.keys?.joinToString(",") ?: "none"
                "[PROBE] $label status=${response.status.value} ms=${System.currentTimeMillis() - started} pairs=${pairs.joinToString(",")} rates=$counts preview=${raw.replace(Regex("\\s+"), " ").take(300)}"
            } catch (error: Exception) {
                "[PROBE] $label ERROR=${error.message ?: "request failed"} url=$url"
            }
        }
    }

    internal fun buildSnapshot(symbol: String, series: Map<String, List<Bar>>, dataErrors: List<String>, quote: JsonObject?): MarketSnapshot {
        val nowEpochMs = System.currentTimeMillis()
        val completedSeries = series.mapValues { (timeframe, bars) ->
            val lastBar = bars.lastOrNull()
            if (lastBar != null && MarketDataQuality.dropFormingFinalBar(lastBar.timestampEpochMs, timeframe, nowEpochMs)) bars.dropLast(1) else bars
        }
        val allBars = completedSeries.values.flatten()
        val last = listOfNotNull(
            quote?.number("mp")?.takeIf { it > 0.0 },
            quote?.number("ap")?.takeIf { it > 0.0 },
            quote?.number("bp")?.takeIf { it > 0.0 },
            allBars.lastOrNull()?.close?.takeIf { it > 0.0 },
        ).firstOrNull()
            ?: throw DirectAlpacaException("No quote or OHLCV bars returned for $symbol")
        val longScalp = listOf("1Min", "15Min").mapNotNull { completedSeries[it]?.let { bars -> analyzeBars(bars, "scalp", TradeDirection.LONG) } }
        val shortScalp = listOf("1Min", "15Min").mapNotNull { completedSeries[it]?.let { bars -> analyzeBars(bars, "scalp", TradeDirection.SHORT) } }
        val longSwing = listOf("1Hour", "1Day").mapNotNull { completedSeries[it]?.let { bars -> analyzeBars(bars, "swing", TradeDirection.LONG) } }
        val shortSwing = listOf("1Hour", "1Day").mapNotNull { completedSeries[it]?.let { bars -> analyzeBars(bars, "swing", TradeDirection.SHORT) } }
        fun meanScore(stats: List<SetupStats>) = stats.map { it.score }.ifEmpty { listOf(0) }.average().toInt()
        val longScalpScore = meanScore(longScalp)
        val shortScalpScore = meanScore(shortScalp)
        val longSwingScore = meanScore(longSwing)
        val shortSwingScore = meanScore(shortSwing)
        val minScanRr = TradeGeometry.MIN_SCAN_REWARD_RISK
        val longScalpCandidate = longScalp.count { it.aligned } >= 2 && longScalp.any { it.breakout || it.liquiditySweep } && longScalp.any { it.volumeExpanded } && (longScalp.maxOfOrNull { it.rr } ?: 0.0) >= minScanRr
        val shortScalpCandidate = shortScalp.count { it.aligned } >= 2 && shortScalp.any { it.breakout || it.liquiditySweep } && shortScalp.any { it.volumeExpanded } && (shortScalp.maxOfOrNull { it.rr } ?: 0.0) >= minScanRr
        val longSwingCandidate = longSwing.any { it.aligned && (it.breakout || it.liquiditySweep) && it.rr >= minScanRr }
        val shortSwingCandidate = shortSwing.any { it.aligned && (it.breakout || it.liquiditySweep) && it.rr >= minScanRr }
        val longCandidate = longScalpCandidate || longSwingCandidate
        val shortCandidate = shortScalpCandidate || shortSwingCandidate
        val longTotalScore = maxOf(longScalpScore, longSwingScore)
        val shortTotalScore = maxOf(shortScalpScore, shortSwingScore)
        val direction = when {
            longCandidate && shortCandidate -> null
            longCandidate -> TradeDirection.LONG
            shortCandidate -> TradeDirection.SHORT
            longTotalScore > shortTotalScore -> TradeDirection.LONG
            shortTotalScore > longTotalScore -> TradeDirection.SHORT
            else -> null
        }
        val scalp = when (direction) { TradeDirection.LONG -> longScalp; TradeDirection.SHORT -> shortScalp; null -> emptyList() }
        val swing = when (direction) { TradeDirection.LONG -> longSwing; TradeDirection.SHORT -> shortSwing; null -> emptyList() }
        val scalpScore = when (direction) { TradeDirection.LONG -> longScalpScore; TradeDirection.SHORT -> shortScalpScore; null -> maxOf(longScalpScore, shortScalpScore) }
        val swingScore = when (direction) { TradeDirection.LONG -> longSwingScore; TradeDirection.SHORT -> shortSwingScore; null -> maxOf(longSwingScore, shortSwingScore) }
        val score = when (direction) { TradeDirection.LONG -> longTotalScore; TradeDirection.SHORT -> shortTotalScore; null -> maxOf(longTotalScore, shortTotalScore) }
        val strategyQualified = when (direction) {
            TradeDirection.LONG -> longCandidate
            TradeDirection.SHORT -> shortCandidate
            null -> false
        }
        val quoteTimestamp = quote?.text("t")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: quote?.text("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val quoteBid = quote?.number("bp")
        val quoteAsk = quote?.number("ap")
        val dataQuality = MarketDataQuality.assess(
            barCounts = completedSeries.mapValues { it.value.size },
            dataErrors = dataErrors,
            quoteTimestampEpochMs = quoteTimestamp,
            bid = quoteBid,
            ask = quoteAsk,
            nowEpochMs = nowEpochMs,
            halted = quote?.get("halted")?.jsonPrimitive?.booleanOrNull == true,
        )
        val allStats = longScalp + shortScalp + longSwing + shortSwing
        val selectedStats = when (direction) {
            TradeDirection.LONG -> if (longScalpScore >= longSwingScore) longScalp else longSwing
            TradeDirection.SHORT -> if (shortScalpScore >= shortSwingScore) shortScalp else shortSwing
            null -> allStats
        }
        val bestStats = selectedStats.maxByOrNull { it.score }
            ?: SetupStats(0.0, 0.0, 0.0, 0, false, false, false, false, false, 0.0, "No usable OHLCV history")
        val scannerPriceError = direction?.let {
            TradeGeometry.validationError(it == TradeDirection.LONG, last, bestStats.stopPrice, bestStats.targetPrice)
        }
        val isExecutable = strategyQualified && dataQuality.passed && scannerPriceError == null
        val setup = when {
            allBars.isEmpty() -> "DATA INCOMPLETE / NO OHLCV PARSED"
            longCandidate && shortCandidate -> "MIXED LONG/SHORT SIGNALS / NO TRADE"
            direction == TradeDirection.LONG && longScalpCandidate && longSwingCandidate -> "SCALP + SWING / ALIGNED LONG SETUP"
            direction == TradeDirection.LONG && longScalpCandidate -> "SCALP / LONG MOMENTUM BREAKOUT OR BULLISH SWEEP"
            direction == TradeDirection.LONG && longSwingCandidate -> "SWING / LONG LIQUIDITY-TO-LIQUIDITY SETUP"
            direction == TradeDirection.SHORT && shortScalpCandidate && shortSwingCandidate -> "SCALP + SWING / ALIGNED SHORT SETUP"
            direction == TradeDirection.SHORT && shortScalpCandidate -> "SCALP / SHORT MOMENTUM BREAKDOWN OR BEARISH SWEEP"
            direction == TradeDirection.SHORT && shortSwingCandidate -> "SWING / SHORT BEARISH BREAKDOWN SETUP"
            direction == null && score >= 55 -> "DEVELOPING / MIXED DIRECTION — EXECUTION GATE NOT CONFIRMED"
            score >= 70 -> "DEVELOPING / HIGH SCORE — EXECUTION GATE NOT CONFIRMED"
            score >= 55 -> "DEVELOPING SETUP"
            else -> "NO TRADE"
        }
        val executionGate = when {
            longCandidate && shortCandidate -> "execution blocked: conflicting long and short signals"
            !dataQuality.passed -> "execution blocked: ${dataQuality.reasons.joinToString("; ")}"
            scannerPriceError != null -> "execution blocked: $scannerPriceError"
            isExecutable -> "${direction?.label?.lowercase()} rule-qualified candidate; market-data checks passed"
            direction == null -> "execution gate not confirmed: no unambiguous direction"
            score >= 70 -> "high score is not sufficient: directional breakout/liquidity, alignment, volume, and/or R:R confirmation is missing"
            else -> "execution gate not confirmed"
        }
        val history = completedSeries.filterValues { it.isNotEmpty() }.entries.joinToString { "${it.key}:${it.value.size}" }
        val scalpRationale = if (direction == null) "LONG $longScalpScore/100; SHORT $shortScalpScore/100; conflicting/no clear direction" else "1m/15m ${direction.label} score $scalpScore/100; " + (scalp.maxByOrNull { it.score }?.rationale ?: "no intraday OHLCV history")
        val swingRationale = if (direction == null) "LONG $longSwingScore/100; SHORT $shortSwingScore/100; conflicting/no clear direction" else "1h/1d ${direction.label} score $swingScore/100; " + (swing.maxByOrNull { it.score }?.rationale ?: "no swing OHLCV history")
        val errorText = if (dataErrors.isEmpty()) "no data errors" else "data errors: ${dataErrors.joinToString(" | ")}"
        val rationale = "Price ${format(last)}; [$history]; scalp $scalpScore; swing $swingScore; parsed bars=${allBars.size}; $executionGate; $errorText"
        val timeframeDiagnostics = listOf("1Min", "15Min", "1Hour", "1Day").map { timeframe ->
            val bars = completedSeries[timeframe].orEmpty()
            val mode = if (timeframe == "1Min" || timeframe == "15Min") "scalp" else "swing"
            val longAnalysis = analyzeBars(bars, mode, TradeDirection.LONG)
            val shortAnalysis = analyzeBars(bars, mode, TradeDirection.SHORT)
            "$timeframe bars=${bars.size}; LONG=${longAnalysis?.rationale ?: "insufficient bars"}; SHORT=${shortAnalysis?.rationale ?: "insufficient bars"}"
        }
        return MarketSnapshot(
            symbol = symbol,
            lastPrice = last,
            dayChangePct = bestStats.dayChangePct,
            trendPct = bestStats.trendPct,
            volatilityPct = bestStats.volatilityPct,
            historyBars = completedSeries.values.maxOfOrNull { it.size } ?: 0,
            score = score,
            setup = setup,
            isExecutableSetup = isExecutable,
            rationale = "${direction?.label ?: "NO CLEAR SIDE"} · $rationale",
            scalpScore = scalpScore,
            swingScore = swingScore,
            scalpRationale = scalpRationale,
            swingRationale = swingRationale,
            executionGate = executionGate,
            timeframeDiagnostics = timeframeDiagnostics,
            direction = direction,
            scannerStopPrice = bestStats.stopPrice.takeIf { it > 0.0 },
            scannerTargetPrice = bestStats.targetPrice.takeIf { it > 0.0 },
            dataQualityPassed = dataQuality.passed,
            dataQualityReason = dataQuality.reasons.ifEmpty { listOf("All required market-data checks passed.") }.joinToString("; "),
            timeframesCovered = dataQuality.timeframesCovered,
            quoteTimestampEpochMs = quoteTimestamp,
            spreadPct = dataQuality.spreadPct,
            scoreBreakdown = listOf(
                "Trend alignment (close vs EMA9 vs EMA21): ${if (bestStats.aligned) 25 else 0}/25",
                "Lookback VWAP confirmation: ${if (bestStats.vwapConfirmed) 15 else 0}/15",
                "Range breakout or liquidity sweep: ${if (bestStats.breakout || bestStats.liquiditySweep) 20 else 0}/20",
                "Volume expansion (last bar >=1.5x prior average): ${if (bestStats.volumeExpanded) 15 else 0}/15",
                "Reward/risk threshold ${format(minScanRr)}+ (1:${format(minScanRr)} minimum): ${if (bestStats.rr >= minScanRr) 15 else 0}/15 (observed ${format(bestStats.rr)})",
                "History depth (>=20 bars on this timeframe): ${if (bestStats.barCount >= 20) 10 else 0}/10 (${bestStats.barCount} bars)",
                "Scoring rule: mean of available 1m/15m scores vs mean of available 1h/1d scores; displayed scanner score is the larger mean, not an empirical probability.",
            ),
            rewardRisk = bestStats.rr,
        )
    }

    private fun describePayload(payload: JsonObject): String {
        val bars = payload["bars"] ?: payload["rates"] ?: payload["data"]
        return when (bars) {
            is JsonObject -> "barsObjectKeys=${bars.keys.take(3)} keyCount=${bars.size}"
            is JsonArray -> "barsArraySize=${bars.size}"
            else -> "barsField=${bars?.javaClass?.simpleName ?: "missing"} topKeys=${payload.keys.take(5)}"
        }
    }

    private fun findSymbolBars(payload: JsonObject, symbol: String, forex: Boolean): List<Bar> {
        if (forex) return AlpacaForexRatesParser.parse(payload, symbol).map {
            Bar(it.open, it.high, it.low, it.close, it.volume, runCatching { Instant.parse(it.timestamp).toEpochMilli() }.getOrDefault(0L))
        }
        return findSymbolPayload(payload, symbol).mapNotNull(Bar::from)
    }

    private fun aggregateBars(bars: List<Bar>, chunkSize: Int): List<Bar> = bars.chunked(chunkSize).filter { it.isNotEmpty() }.map { chunk ->
        Bar(chunk.first().open, chunk.maxOf { it.high }, chunk.minOf { it.low }, chunk.last().close, chunk.sumOf { it.volume }, chunk.last().timestampEpochMs)
    }

    private fun findSymbolPayload(payload: JsonObject, symbol: String): List<JsonObject> {
        val container = payload["bars"] ?: payload["data"] ?: payload
        val map = container as? JsonObject
        val exact = map?.get(symbol) as? JsonArray
        val normalized = map?.entries?.firstOrNull { normalizeSymbol(it.key) == normalizeSymbol(symbol) }?.value as? JsonArray
        val direct = container as? JsonArray
        return (exact ?: normalized ?: direct)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun normalizeSymbol(value: String): String = value.uppercase().replace("/", "")

    internal fun analyzeBars(bars: List<Bar>, mode: String, direction: TradeDirection): SetupStats? {
        if (bars.size < 2) return null
        val closes = bars.map { it.close }
        val last = bars.last()
        val ema9 = ema(closes, 9)
        val ema21 = ema(closes, 21)
        val vwapDenominator = bars.sumOf { it.volume }
        val vwap = if (vwapDenominator > 0.0) bars.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / vwapDenominator else closes.average()
        val atr = bars.takeLast(14).zipWithNext().map { (a, b) -> maxOf(b.high - b.low, abs(b.high - a.close), abs(b.low - a.close)) }.averageOrNull() ?: (last.close * 0.005)
        val prior = bars.dropLast(1).takeLast(20)
        val priorHigh = prior.maxOfOrNull { it.high } ?: last.high
        val priorLow = prior.minOfOrNull { it.low } ?: last.low
        val averageVolume = prior.map { it.volume }.averageOrNull() ?: last.volume
        val volumeExpanded = averageVolume > 0.0 && last.volume > 0.0 && last.volume >= averageVolume * 1.5
        val isLong = direction == TradeDirection.LONG
        val vwapConfirmed = vwapDenominator > 0.0 && if (isLong) last.close > vwap else last.close < vwap
        val aligned = if (isLong) last.close > ema9 && ema9 >= ema21 else last.close < ema9 && ema9 <= ema21
        val breakout = if (isLong) last.close > priorHigh else last.close < priorLow
        val liquiditySweep = if (isLong) last.low < priorLow && last.close > priorLow else last.high > priorHigh && last.close < priorHigh
        val stop = if (liquiditySweep) {
            if (isLong) last.low else last.high
        } else if (isLong) last.close - atr else last.close + atr
        val targetDistanceAtr = if (mode == "swing") 2.0 else 1.5
        val target = if (isLong) {
            maxOf(priorHigh, last.close + atr * targetDistanceAtr)
        } else {
            minOf(priorLow, last.close - atr * targetDistanceAtr).coerceAtLeast(0.01)
        }
        val rr = if (isLong && last.close > stop) {
            (target - last.close) / (last.close - stop)
        } else if (!isLong && stop > last.close) {
            (last.close - target) / (stop - last.close)
        } else 0.0
        val dayChangePct = (last.close / (bars.getOrNull(bars.size - 2)?.close ?: last.close) - 1.0) * 100.0
        val baseline = closes.takeLast(20).average()
        val trendPct = (last.close / baseline - 1.0) * 100.0
        val volatility = closes.zipWithNext().map { (a, b) -> abs(b / a - 1.0) * 100.0 }.averageOrNull() ?: 0.0
        var score = 0
        if (aligned) score += 25
        if (vwapConfirmed) score += 15
        if (breakout || liquiditySweep) score += 20
        if (volumeExpanded) score += 15
        if (rr >= if (mode == "swing") 2.0 else 1.5) score += 15
        if (bars.size >= 20) score += 10
        val event = when {
            breakout && isLong -> "upside range breakout"
            breakout -> "downside range breakdown"
            liquiditySweep && isLong -> "bullish liquidity sweep/reclaim"
            liquiditySweep -> "bearish liquidity sweep/rejection"
            else -> "no confirmed directional liquidity event"
        }
        val volumeNote = if (averageVolume <= 0.0 || last.volume <= 0.0) "unavailable" else if (volumeExpanded) ">=1.5x" else "<1.5x"
        val rationale = "${bars.size} bars; ${direction.label.lowercase()} bias; EMA9 ${if (ema9 >= ema21) ">" else "<"} EMA21; close ${if (vwapConfirmed) (if (isLong) ">" else "<") else (if (isLong) "<=" else ">=")} lookback VWAP; $event; volume $volumeNote; R:R ${format(rr)}"
        return SetupStats(dayChangePct, trendPct, volatility, score.coerceIn(0, 100), aligned, vwapConfirmed, breakout, liquiditySweep, volumeExpanded, rr, rationale, stop, target, mode, bars.size)
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val alpha = 2.0 / (period + 1.0)
        if (values.size < period) return values.average()
        var result = values.take(period).average()
        values.drop(period).forEach { result = alpha * it + (1.0 - alpha) * result }
        return result
    }

    suspend fun submitBracketOrder(
        apiKey: String, apiSecret: String, symbol: String, side: String,
        stopPrice: Double, targetPrice: Double,
        useLimitEntry: Boolean = false,
        clientOrderId: String? = null,
        equityDollars: Double,
        currentGrossExposureDollars: Double,
        riskBudgetPct: Double = TradeGeometry.DEFAULT_RISK_BUDGET_PCT,
        maxExposurePct: Double = 80.0,
    ): BracketSubmission {
        if (this != PAPER) throw DirectAlpacaException("Execution blocked: this build is paper-only; live Alpaca order placement is disabled.")
        val normalizedSymbol = symbol.trim().uppercase()
        val normalizedSide = side.trim().lowercase()
        require(normalizedSide == "buy" || normalizedSide == "sell") { "Unsupported order side: $side" }
        val asset = requestObject(apiKey, apiSecret, "/v2/assets/${pathSegment(normalizedSymbol)}")
            ?: throw DirectAlpacaException("Order blocked: Alpaca asset status and tradability for $normalizedSymbol could not be verified; no order was sent.")
        val status = asset["status"]?.jsonPrimitive?.content
        val tradable = asset["tradable"]?.jsonPrimitive?.booleanOrNull
        if (!status.equals("active", ignoreCase = true) || tradable != true) {
            throw DirectAlpacaException("Order blocked for $normalizedSymbol: asset must be active and tradable (status=$status, tradable=$tradable). No order was sent.")
        }
        if (normalizedSide == "sell") {
            val shortable = asset["shortable"]?.jsonPrimitive?.booleanOrNull
            val easyToBorrow = asset["easy_to_borrow"]?.jsonPrimitive?.booleanOrNull
            if (!AlpacaShortabilityRules.canOpenShort(status, tradable, shortable, easyToBorrow)) {
                throw DirectAlpacaException("Short blocked for $normalizedSymbol: Alpaca must report active, tradable, shortable, and easy_to_borrow=true (status=$status, tradable=$tradable, shortable=$shortable, easy_to_borrow=$easyToBorrow). No order was sent.")
            }
        }
        val freshQuote = latestStockQuote(apiKey, apiSecret, normalizedSymbol)
            ?: throw DirectAlpacaException("Cannot build bracket: a fresh two-sided quote is required; no order was sent.")
        val age = System.currentTimeMillis() - freshQuote.timestampEpochMs
        if (age !in 0..MarketDataQuality.MAX_QUOTE_AGE_MS) throw DirectAlpacaException("Cannot build bracket: quote is stale or future-dated; no order was sent.")
        if (freshQuote.spreadPct > MarketDataQuality.DEFAULT_MAX_SPREAD_PCT) throw DirectAlpacaException("Cannot build bracket: spread ${format(freshQuote.spreadPct)}% exceeds ${MarketDataQuality.DEFAULT_MAX_SPREAD_PCT}%; no order was sent.")
        val quotedPrice = if (normalizedSide == "buy") freshQuote.ask else freshQuote.bid
        val entryPrice = if (useLimitEntry) priceUp(quotedPrice) else quotedPrice
        val basePrice = entryPrice
        if (!basePrice.isFinite() || basePrice <= 0.0) throw DirectAlpacaException("Cannot build bracket: no valid current price for $normalizedSymbol.")
        val isLong = normalizedSide == "buy"
        val stop = if (isLong) priceDown(stopPrice) else priceUp(stopPrice)
        val target = if (isLong) priceUp(targetPrice) else priceDown(targetPrice)
        val remainingNotionalCapacity = NotionalExposurePolicy.remainingCapacityDollars(
            equityDollars = equityDollars,
            grossExposureDollars = currentGrossExposureDollars,
            maximumPct = maxExposurePct,
        ) ?: throw DirectAlpacaException("Cannot build bracket: remaining gross-notional capacity could not be verified.")
        val plan = try {
            TradeGeometry.plan(
                isLong = isLong,
                entryPrice = basePrice,
                stopPrice = stop,
                targetPrice = target,
                equityDollars = equityDollars,
                requestedRiskBudgetPct = riskBudgetPct,
                maximumNotionalDollars = remainingNotionalCapacity,
            )
        } catch (error: IllegalArgumentException) {
            throw DirectAlpacaException("Cannot build bracket: ${error.message ?: "fresh-quote bracket is invalid"} No order was sent.")
        }
        // Keep the broker payload directionally explicit. For a SELL/SHORT bracket,
        // the protective stop-loss is above entry and the take-profit is below entry.
        // Never send a payload whose levels are on the wrong side of the fresh bid.
        if (isLong && !(stop < basePrice && target > basePrice)) {
            throw DirectAlpacaException("Cannot build bracket: BUY/LONG requires stop-loss < entry < take-profit (stop=${format(stop)}, entry=${format(basePrice)}, target=${format(target)}). No order was sent.")
        }
        if (!isLong && !(target < basePrice && stop > basePrice)) {
            throw DirectAlpacaException("Cannot build bracket: SELL/SHORT requires take-profit < entry < protective stop-loss (target=${format(target)}, entry=${format(basePrice)}, stop=${format(stop)}). No order was sent.")
        }
        val quantity = plan.quantity.toDouble()
        val exposure = NotionalExposurePolicy.assess(equityDollars, currentGrossExposureDollars, basePrice * quantity, maxExposurePct)
        if (!exposure.allowed) throw DirectAlpacaException("Cannot build bracket: ${exposure.reason} No order was sent.")
        // Alpaca treats duplicate client ids as duplicate orders. Retried submissions use a new id.
        val resolvedClientOrderId = clientOrderId?.trim()?.takeIf { it.isNotEmpty() } ?: TradeTicketFactory.newClientOrderId()
        val order = requestObjectWithBody(apiKey, apiSecret, "/v2/orders", buildJsonObject {
            put("symbol", normalizedSymbol); put("qty", quantity.toString()); put("side", normalizedSide)
            put("client_order_id", resolvedClientOrderId)
            // Alpaca does not support extended_hours=true on bracket orders.
            // Bracket submission is guarded to the regular session in the ViewModel.
            put("type", if (useLimitEntry) "limit" else "market"); put("time_in_force", "day"); put("order_class", "bracket")
            if (useLimitEntry) put("limit_price", priceUp(basePrice).toString())
            put("stop_loss", buildJsonObject { put("stop_price", stop.toString()) })
            put("take_profit", buildJsonObject { put("limit_price", target.toString()) })
        })
        return BracketSubmission(order, basePrice, stop, target, quantity, true, abs(basePrice - stop) / basePrice, resolvedClientOrderId)
    }

    private data class FreshStockQuote(val bid: Double, val ask: Double, val timestampEpochMs: Long) {
        val spreadPct: Double get() = (ask - bid) / ((ask + bid) / 2.0) * 100.0
    }

    private suspend fun latestStockQuote(apiKey: String, apiSecret: String, symbol: String): FreshStockQuote? = runCatching {
        val body = requestDataObject(apiKey, apiSecret, "/v2/stocks/quotes/latest?symbols=$symbol&feed=iex")
        val quotes = body["quotes"] as? JsonObject ?: return@runCatching null
        val quote = (quotes[symbol] as? JsonObject)
            ?: quotes.entries.firstOrNull { it.key.equals(symbol, ignoreCase = true) }?.value as? JsonObject
            ?: return@runCatching null
        val bid = quote.number("bp")?.takeIf { it > 0.0 } ?: return@runCatching null
        val ask = quote.number("ap")?.takeIf { it >= bid } ?: return@runCatching null
        val timestamp = quote.text("t")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: quote.text("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return@runCatching null
        FreshStockQuote(bid, ask, timestamp)
    }.getOrNull()

    private fun priceDown(value: Double): Double = BigDecimal.valueOf(value).setScale(2, RoundingMode.FLOOR).toDouble().coerceAtLeast(0.01)
    private fun priceUp(value: Double): Double = BigDecimal.valueOf(value).setScale(2, RoundingMode.CEILING).toDouble().coerceAtLeast(0.01)

    private suspend fun requestObject(apiKey: String, apiSecret: String, path: String): JsonObject? {
        val response = client.get("$baseUrl$path") { authorized(apiKey, apiSecret) }
        return if (response.status.value in 200..299) response.body() else null
    }

    private suspend fun requestDataObject(apiKey: String, apiSecret: String, path: String): JsonObject {
        val response = client.get("$DATA_BASE_URL$path") { authorized(apiKey, apiSecret) }
        if (response.status.value !in 200..299) throw DirectAlpacaException("HTTP ${response.status.value} for quote request")
        return response.body()
    }

    private suspend fun requestDataBarsBatch(apiKey: String, apiSecret: String, symbols: List<String>, timeframe: String, forex: Boolean, includeFeed: Boolean, range: HistoricalRange): BatchPayload {
        val parameter = if (forex) "currency_pairs=${symbols.joinToString(",")}" else "symbols=${symbols.joinToString(",")}"
        val feed = if (!forex && includeFeed) "&feed=iex" else ""
        val apiTimeframe = if (forex && timeframe == "1Day") "1D" else timeframe
        val path = if (forex) "/v1beta1/forex/rates?$parameter&timeframe=$apiTimeframe&start=${range.start}&end=${range.end}&limit=${range.limit}&sort=asc"
            else "/v2/stocks/bars?$parameter&timeframe=$timeframe&start=${range.start}&end=${range.end}&limit=${range.limit}&sort=asc$feed"
        val response = client.get("$DATA_BASE_URL$path") { authorized(apiKey, apiSecret) }
        val raw = response.bodyAsText()
        val preview = raw.replace(Regex("\\s+"), " ").take(500)
        if (response.status.value !in 200..299) throw DirectAlpacaException("Alpaca returned HTTP ${response.status.value} for $timeframe batch market data; body=$preview")
        val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { throw DirectAlpacaException("Invalid JSON for $timeframe batch market data; body=$preview") }
        val barsType = (body["bars"] ?: body["rates"])?.let { it::class.simpleName } ?: "missing"
        val dataType = body["data"]?.let { it::class.simpleName } ?: "missing"
        val keys = body.keys.joinToString(",").take(200)
        return BatchPayload(body, "url=$DATA_BASE_URL$path; bodyChars=${raw.length}; topKeys=[$keys]; barsType=$barsType; dataType=$dataType; preview=$preview")
    }

    private suspend fun requestArray(apiKey: String, apiSecret: String, path: String): List<JsonObject> {
        val response = client.get("$baseUrl$path") { authorized(apiKey, apiSecret) }
        if (response.status.value !in 200..299) throw DirectAlpacaException("Alpaca returned HTTP ${response.status.value}.")
        return response.body()
    }

    private suspend fun requestObjectWithBody(apiKey: String, apiSecret: String, path: String, body: JsonObject): JsonObject {
        val response = client.post("$baseUrl$path") {
            authorized(apiKey, apiSecret)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(body)
        }
        val raw = response.bodyAsText()
        if (response.status.value in 200..299) {
            return runCatching { Json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw DirectAlpacaException("Alpaca order returned invalid JSON (HTTP ${response.status.value}).") }
        }
        val detail = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?.let { it["message"]?.jsonPrimitive?.content ?: it["detail"]?.jsonPrimitive?.content }
            ?: raw.replace(Regex("\\s+"), " ").take(500)
        throw DirectAlpacaException("Alpaca order HTTP ${response.status.value}: $detail")
    }

    private fun brokerError(operation: String, status: Int, raw: String): DirectAlpacaException {
        val detail = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?.let { it["message"]?.jsonPrimitive?.content ?: it["detail"]?.jsonPrimitive?.content }
            ?: raw.replace(Regex("\\s+"), " ").take(500)
        return DirectAlpacaException("Alpaca $operation HTTP $status: $detail")
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20")

    private fun HttpRequestBuilder.authorized(apiKey: String, apiSecret: String) {
        header("APCA-API-KEY-ID", apiKey); header("APCA-API-SECRET-KEY", apiSecret)
    }

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.text(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    companion object {
        private const val DATA_BASE_URL = "https://data.alpaca.markets"
        private val client = HttpClient(Android) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
            expectSuccess = false
        }
        suspend fun detect(apiKey: String, apiSecret: String): DirectEnvironment {
            val paper = PAPER.probe(apiKey, apiSecret); val live = LIVE.probe(apiKey, apiSecret)
            return when { paper && !live -> PAPER; live && !paper -> LIVE; paper && live -> throw DirectAlpacaException("Credentials authenticated against both environments; refusing to guess."); else -> throw DirectAlpacaException("Credentials were rejected by both Alpaca environments.") }
        }
        private fun format(value: Double) = "%.2f".format(java.util.Locale.US, value)
    }
}
