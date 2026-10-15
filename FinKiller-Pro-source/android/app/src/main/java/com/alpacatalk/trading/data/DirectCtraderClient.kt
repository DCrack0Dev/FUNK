package com.alpacatalk.trading.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class DirectCtraderException(message: String) : Exception(message)

data class CtraderToken(val accessToken: String, val refreshToken: String, val expiresIn: Long)

object DirectCtraderClient {
    private const val AUTH_URL = "https://id.ctrader.com/my/settings/openapi/grantingaccess/"
    private const val TOKEN_URL = "https://openapi.ctrader.com/apps/token"
    private const val DEMO_SOCKET = "wss://demo.ctraderapi.com:5036"
    private const val LIVE_SOCKET = "wss://live.ctraderapi.com:5036"
    const val REDIRECT_URI = "ctrader://oauth/callback"

    private val http = HttpClient(Android) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
        expectSuccess = false
    }

    fun authorizationUrl(clientId: String, state: String): String = URLBuilder(AUTH_URL).apply {
        parameters.append("client_id", clientId)
        parameters.append("redirect_uri", REDIRECT_URI)
        parameters.append("scope", "trading")
        parameters.append("product", "web")
        parameters.append("state", state)
    }.buildString()

    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String): CtraderToken {
        val response = http.get(TOKEN_URL) {
            parameter("grant_type", "authorization_code")
            parameter("code", code)
            parameter("redirect_uri", REDIRECT_URI)
            parameter("client_id", clientId)
            parameter("client_secret", clientSecret)
            accept(ContentType.Application.Json)
        }
        val raw = response.bodyAsText()
        val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { throw DirectCtraderException("cTrader token response was invalid.") }
        val error = body["description"]?.jsonPrimitive?.content ?: body["errorCode"]?.jsonPrimitive?.content
        if (response.status.value !in 200..299 || error != null) throw DirectCtraderException("cTrader authorization failed: ${error ?: "HTTP ${response.status.value}"}")
        return CtraderToken(
            body["accessToken"]?.jsonPrimitive?.content ?: throw DirectCtraderException("cTrader returned no access token."),
            body["refreshToken"]?.jsonPrimitive?.content ?: throw DirectCtraderException("cTrader returned no refresh token."),
            body["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): CtraderToken {
        val response = http.get(TOKEN_URL) {
            parameter("grant_type", "refresh_token")
            parameter("refresh_token", refreshToken)
            parameter("client_id", clientId)
            parameter("client_secret", clientSecret)
            accept(ContentType.Application.Json)
        }
        val body = runCatching { response.body<JsonObject>() }.getOrElse { throw DirectCtraderException("cTrader refresh response was invalid.") }
        if (response.status.value !in 200..299) throw DirectCtraderException("cTrader token refresh failed.")
        return CtraderToken(body["accessToken"]?.jsonPrimitive?.content ?: throw DirectCtraderException("No refreshed access token."), body["refreshToken"]?.jsonPrimitive?.content ?: refreshToken, body["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
    }

    suspend fun accounts(clientId: String, clientSecret: String, accessToken: String, live: Boolean): List<JsonObject> {
        val socketUrl = if (live) LIVE_SOCKET else DEMO_SOCKET
        var result: List<JsonObject> = emptyList()
        http.webSocket(socketUrl) {
            send(message(2100, buildJsonObject { put("clientId", clientId); put("clientSecret", clientSecret) }))
            receiveTextOrThrow()
            send(message(2149, buildJsonObject { put("accessToken", accessToken) }))
            val response = Json.parseToJsonElement(receiveTextOrThrow()).jsonObject
            val payload = response["payload"]?.jsonObject ?: response
            val accounts = payload["ctidTraderAccount"] as? JsonArray ?: JsonArray(emptyList())
            result = accounts.mapNotNull { it as? JsonObject }
        }
        return result
    }

    suspend fun authorizeAccount(clientId: String, clientSecret: String, accessToken: String, accountId: String, live: Boolean): Boolean {
        val socketUrl = if (live) LIVE_SOCKET else DEMO_SOCKET
        var authorized = false
        http.webSocket(socketUrl) {
            send(message(2100, buildJsonObject { put("clientId", clientId); put("clientSecret", clientSecret) }))
            receiveTextOrThrow()
            send(message(2102, buildJsonObject { put("ctidTraderAccountId", accountId.toLong()); put("accessToken", accessToken) }))
            val response = Json.parseToJsonElement(receiveTextOrThrow()).jsonObject
            val payload = response["payload"]?.jsonObject ?: response
            authorized = response["payloadType"]?.jsonPrimitive?.content == "2103" && payload["ctidTraderAccountId"] != null
        }
        return authorized
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveTextOrThrow(): String {
        repeat(5) {
            val frame = incoming.receive()
            if (frame is Frame.Text) return frame.readText()
        }
        throw DirectCtraderException("cTrader returned no usable account response.")
    }

    private fun message(payloadType: Int, payload: JsonObject): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("clientMsgId", UUID.randomUUID().toString())
        put("payloadType", payloadType)
        put("payload", payload)
    })
}
