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
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class DirectOandaException(message: String) : Exception(message)

enum class OandaEnvironment(val label: String, private val baseUrl: String) {
    PRACTICE("OANDA PRACTICE", "https://api-fxpractice.oanda.com"),
    LIVE("OANDA LIVE", "https://api-fxtrade.oanda.com");

    suspend fun accounts(token: String): List<JsonObject> = requestObject(token, "/v3/accounts")["accounts"]
        ?.let { it as? JsonArray }
        ?.mapNotNull { it as? JsonObject } ?: emptyList()

    suspend fun summary(token: String, accountId: String): JsonObject =
        (requestObject(token, "/v3/accounts/$accountId/summary")["account"] as? JsonObject)
            ?: throw DirectOandaException("OANDA account summary was missing.")

    suspend fun instruments(token: String, accountId: String): JsonObject = requestObject(token, "/v3/accounts/$accountId/instruments")

    suspend fun xauUsdEligibility(token: String, accountId: String): String {
        val instruments = instruments(token, accountId)["instruments"] as? JsonArray ?: return "XAU_USD is not listed by this OANDA account."
        val gold = instruments.firstOrNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content == "XAU_USD" } as? JsonObject
            ?: return "XAU_USD is not available to this OANDA account."
        val displayPrecision = gold["displayPrecision"]?.jsonPrimitive?.content ?: "unknown"
        val tradeUnitsPrecision = gold["tradeUnitsPrecision"]?.jsonPrimitive?.content ?: "unknown"
        val minimumTradeSize = gold["minimumTradeSize"]?.jsonPrimitive?.content ?: "unknown"
        return "XAU_USD enabled; displayPrecision=$displayPrecision; tradeUnitsPrecision=$tradeUnitsPrecision; minimumTradeSize=$minimumTradeSize"
    }

    suspend fun pricing(token: String, accountId: String, instrument: String = "XAU_USD"): JsonObject =
        requestObject(token, "/v3/accounts/$accountId/pricing?instruments=$instrument")

    suspend fun candles(token: String, instrument: String = "XAU_USD", granularity: String = "M15", count: Int = 200): JsonArray =
        requestObject(token, "/v3/instruments/$instrument/candles?granularity=$granularity&count=$count")["candles"] as? JsonArray
            ?: throw DirectOandaException("OANDA returned no candles for $instrument.")

    suspend fun marketOrder(token: String, accountId: String, units: String, stopLoss: Double?, takeProfit: Double?): JsonObject {
        if (this != PRACTICE) throw DirectOandaException("Execution blocked: this build is paper-only; live OANDA order requests are disabled.")
        val order = buildJsonObject {
            put("type", "MARKET")
            put("instrument", "XAU_USD")
            put("units", units)
            put("timeInForce", "FOK")
            put("positionFill", "DEFAULT")
            stopLoss?.let { put("stopLossOnFill", buildJsonObject { put("price", it.toString()); put("timeInForce", "GTC") }) }
            takeProfit?.let { put("takeProfitOnFill", buildJsonObject { put("price", it.toString()); put("timeInForce", "GTC") }) }
        }
        return requestObject(token, "/v3/accounts/$accountId/orders", buildJsonObject { put("order", order) }, method = "POST")
    }

    suspend fun cancelOrder(token: String, accountId: String, orderId: String): JsonObject {
        if (this != PRACTICE) throw DirectOandaException("Execution blocked: live OANDA order mutations are disabled in this paper-only build.")
        return requestObject(token, "/v3/accounts/$accountId/orders/$orderId", method = "PUT", body = buildJsonObject { put("order", buildJsonObject { put("units", "0") }) })
    }

    private suspend fun requestObject(token: String, path: String, body: JsonObject? = null, method: String = "GET"): JsonObject {
        val response = when (method) {
            "POST" -> client.post("$baseUrl$path") { authorized(token); contentType(ContentType.Application.Json); accept(ContentType.Application.Json); setBody(body) }
            "PUT" -> client.put("$baseUrl$path") { authorized(token); contentType(ContentType.Application.Json); accept(ContentType.Application.Json); setBody(body) }
            else -> client.get("$baseUrl$path") { authorized(token) }
        }
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) throw DirectOandaException("OANDA HTTP ${response.status.value}: ${raw.replace(Regex("\\s+"), " ").take(400)}")
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { throw DirectOandaException("OANDA returned invalid JSON.") }
    }

    private fun HttpRequestBuilder.authorized(token: String) { header("Authorization", "Bearer $token") }

    companion object {
        private val client = HttpClient(Android) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
            expectSuccess = false
        }
    }
}
