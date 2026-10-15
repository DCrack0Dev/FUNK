package com.alpacatalk.trading

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alpacatalk.trading.data.AccountResponse
import com.alpacatalk.trading.data.ApiClient
import com.alpacatalk.trading.data.ApiException
import com.alpacatalk.trading.data.AutomationStatusResponse
import com.alpacatalk.trading.data.ConnectionState
import com.alpacatalk.trading.data.EnvironmentResponse
import com.alpacatalk.trading.data.HealthResponse
import com.alpacatalk.trading.data.RemoteScreenData
import com.alpacatalk.trading.data.TradingUiState
import com.alpacatalk.trading.data.VerifiedEnvironment
import com.alpacatalk.trading.security.BackendUrlStore
import com.alpacatalk.trading.security.KeystoreSessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI state contains only server-derived summaries. It intentionally has no fields for a broker key,
 * broker secret, or backend passphrase. The backend remains the sole order-submission authority.
 */
class TradingViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = KeystoreSessionStore(application.applicationContext)
    private val backendUrlStore = BackendUrlStore(application.applicationContext)
    private var apiClient: ApiClient? = null

    private val _uiState = MutableStateFlow(TradingUiState())
    val uiState: StateFlow<TradingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { restoreEncryptedSession() }
    }

    fun selectBroker(broker: String) {
        if (_uiState.value.connectionState == ConnectionState.CONNECTED) return
        _uiState.update { it.copy(selectedBroker = broker, errorMessage = null, infoMessage = null, ibkrAccounts = emptyList()) }
    }

    private suspend fun restoreEncryptedSession() {
        val url = backendUrlStore.read()
        val token = sessionStore.read()
        if (url.isNullOrBlank() || token.isNullOrBlank()) return
        try {
            apiClient = ApiClient.create(url, token)
            _uiState.update { it.copy(connectionState = ConnectionState.CONNECTED, isLoading = true) }
            refreshDashboard()
        } catch (_: Exception) {
            // A malformed stale configuration cannot be used; remove it without printing its value.
            clearLocalSession()
        }
    }

    /** Creates a backend session. The passphrase is used for this request only and never stored. */
    suspend fun establishBackendSession(rawUrl: String, passphrase: String): Boolean {
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, infoMessage = null, backendOffline = false)
        }
        var bootstrap: ApiClient? = null
        return try {
            bootstrap = ApiClient.create(rawUrl, sessionToken = null)
            val response = bootstrap.createSession(passphrase)
            val token = response.accessToken
                ?: throw ApiException("The backend did not return a session token.")
            if (token.isBlank()) throw ApiException("The backend returned an empty session token.")

            // Only this backend token is persisted, using Android Keystore encryption.
            sessionStore.save(token)
            backendUrlStore.save(com.alpacatalk.trading.data.BackendUrlPolicy.normalize(rawUrl))
            apiClient?.close()
            apiClient = ApiClient.create(rawUrl, token)
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.SESSION_READY,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            true
        } catch (error: Exception) {
            // Do not retain a partial setup session if local persistence or client setup failed.
            apiClient?.close()
            apiClient = null
            sessionStore.clear()
            backendUrlStore.clear()
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    isLoading = false,
                    backendOffline = error.isBackendUnavailable(),
                    errorMessage = safeMessage(error, "Unable to establish a backend session."),
                )
            }
            false
        } finally {
            // The active authenticated client owns its own engine; bootstrap is no longer needed.
            bootstrap?.close()
        }
    }

    /**
     * One-step setup for the simplified UI. Broker credentials still go only to the authenticated
     * backend validator; they are never persisted on the device and never used for direct trading.
     */
    suspend fun connectAndValidate(
        rawUrl: String,
        passphrase: String,
        apiKey: CharArray,
        apiSecret: CharArray,
    ): Boolean {
        return try {
            if (!establishBackendSession(rawUrl, passphrase)) return false
            validateBrokerCredentials(apiKey, apiSecret)
        } finally {
            apiKey.fill('\u0000')
            apiSecret.fill('\u0000')
        }
    }

    /**
     * Sends temporary broker credentials once to the backend validator. Char arrays are cleared in
     * this ViewModel after the response; they are never added to StateFlow or device storage.
     */
    suspend fun validateBrokerCredentials(apiKey: CharArray, apiSecret: CharArray): Boolean {
        val activeClient = apiClient
        if (activeClient == null) {
            apiKey.fill('\u0000')
            apiSecret.fill('\u0000')
            _uiState.update { it.copy(errorMessage = "Create a backend session before validating credentials.") }
            return false
        }

        _uiState.update {
            it.copy(
                connectionState = ConnectionState.VALIDATING,
                isLoading = true,
                errorMessage = null,
                infoMessage = null,
            )
        }
        var keyForRequest: String? = null
        var secretForRequest: String? = null
        return try {
            keyForRequest = apiKey.concatToString()
            secretForRequest = apiSecret.concatToString()
            activeClient.validateConnection(keyForRequest, secretForRequest)
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTED,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            refreshDashboard()
            _uiState.value.errorMessage == null
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.SESSION_READY,
                    isLoading = false,
                    backendOffline = error.isBackendUnavailable(),
                    errorMessage = safeMessage(error, "Credential validation failed."),
                )
            }
            false
        } finally {
            // Kotlin Strings cannot be zeroed, so references are released immediately after use.
            keyForRequest = null
            secretForRequest = null
            apiKey.fill('\u0000')
            apiSecret.fill('\u0000')
        }
    }

    suspend fun checkIbkrSession(): Boolean {
        val activeClient = apiClient ?: return false
        return try {
            val payload = activeClient.ibkrSession().jsonObject
            val accountArray = payload["accounts"] as? JsonArray ?: JsonArray(emptyList())
            val accounts = accountArray.mapNotNull { element: JsonElement ->
                (element as? JsonObject)?.get("account_id")?.jsonPrimitive?.content
            }
            _uiState.update {
                it.copy(
                    selectedBroker = "ibkr",
                    ibkrAccounts = accounts,
                    errorMessage = null,
                    infoMessage = if (accounts.isEmpty()) "IBKR session is authenticated but returned no selectable accounts." else "IBKR session connected. Select one account.",
                )
            }
            accounts.isNotEmpty()
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = safeMessage(error, "IBKR session is unavailable.")) }
            false
        }
    }

    suspend fun selectIbkrAccount(accountId: String, environment: String): Boolean {
        val activeClient = apiClient ?: return false
        return try {
            activeClient.selectIbkrAccount(accountId, environment)
            _uiState.update {
                it.copy(
                    selectedBroker = "ibkr",
                    connectionState = ConnectionState.CONNECTED,
                    errorMessage = null,
                    infoMessage = "Connected to the selected IBKR account.",
                )
            }
            refreshDashboard()
            true
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = safeMessage(error, "IBKR account selection failed.")) }
            false
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshDashboard() }
    }

    /** Loads a backend-provided detail response as redacted, read-only UI state. */
    fun loadReadOnlyData(screenKey: String, endpoint: String) {
        val existing = _uiState.value.screenData[screenKey]
        if (existing?.isLoading == true || existing?.content != null) return
        val activeClient = apiClient ?: return
        viewModelScope.launch {
            putScreenData(screenKey, RemoteScreenData(isLoading = true))
            try {
                val safeContent = activeClient.readOnlyPayload(endpoint).redactedDisplay()
                putScreenData(screenKey, RemoteScreenData(content = safeContent))
            } catch (error: Exception) {
                putScreenData(
                    screenKey,
                    RemoteScreenData(error = safeMessage(error, "Unable to load backend data for this screen.")),
                )
            }
        }
    }

    /** Calls health, account, verified environment, and automation endpoints as one server refresh. */
    private suspend fun refreshDashboard() {
        val activeClient = apiClient ?: return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val health = activeClient.health()
            if (!health.isAvailable()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        backendOffline = true,
                        health = health,
                        errorMessage = "Backend reported an unavailable state.",
                    )
                }
                return
            }

            val snapshot = coroutineScope {
                val account = async { activeClient.account() }
                val environment = async { activeClient.environment() }
                val automation = async { activeClient.automationStatus() }
                DashboardSnapshot(account.await(), environment.await(), automation.await())
            }
            val verifiedEnvironment = VerifiedEnvironment.fromServer(
                snapshot.environment.environment,
                snapshot.environment.verifiedAt,
            )
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTED,
                    isLoading = false,
                    backendOffline = false,
                    errorMessage = null,
                    account = snapshot.account,
                    environment = verifiedEnvironment,
                    environmentDetails = snapshot.environment,
                    automation = snapshot.automation,
                    health = health,
                )
            }
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    backendOffline = error.isBackendUnavailable(),
                    errorMessage = safeMessage(error, "Unable to refresh backend state."),
                )
            }
        }
    }

    fun disableAutomation() {
        val environment = currentEnvironmentPath()
        viewModelScope.launch {
            runAction("Automation disabled.") { disableAutomation(environment) }
        }
    }

    fun savePaperSetup(symbolsInput: String, riskConfigurationInput: String) {
        val activeClient = apiClient ?: return
        val symbols = symbolsInput.split(',').map(String::trim).filter(String::isNotBlank).distinct()
        val riskConfiguration = try {
            ApiClient.JSON.parseToJsonElement(riskConfigurationInput) as? JsonObject
                ?: throw IllegalArgumentException("Risk settings must be a JSON object.")
        } catch (_: Exception) {
            _uiState.update { it.copy(errorMessage = "Enter valid JSON for the paper risk settings.") }
            return
        }
        if (symbols.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Add at least one paper watchlist symbol.") }
            return
        }
        _uiState.update {
            it.copy(
                isActionInProgress = true,
                paperSetupSaved = false,
                errorMessage = null,
                infoMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                activeClient.savePaperSetup(symbols, riskConfiguration)
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        paperSetupSaved = true,
                        paperSetupLoaded = true,
                        paperWatchlistText = symbols.joinToString(", "),
                        paperRiskConfigurationText = ApiClient.JSON.encodeToString(
                            JsonObject.serializer(),
                            riskConfiguration,
                        ),
                        infoMessage = "Paper watchlist and risk limits saved and validated.",
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        paperSetupSaved = false,
                        backendOffline = error.isBackendUnavailable(),
                        errorMessage = safeMessage(
                            error,
                            "Paper settings were not saved. Check the symbol and risk fields.",
                        ),
                    )
                }
            }
        }
    }

    fun loadPaperSetup() {
        val activeClient = apiClient ?: return
        viewModelScope.launch {
            try {
                val watchlist = activeClient.paperWatchlist()
                val riskConfiguration = activeClient.paperRiskConfiguration()
                val configText = riskConfiguration.config?.let {
                    ApiClient.JSON.encodeToString(JsonObject.serializer(), it)
                }.orEmpty()
                _uiState.update {
                    it.copy(
                        paperSetupLoaded = true,
                        paperSetupSaved = watchlist.enabled && watchlist.symbols.isNotEmpty() && riskConfiguration.complete,
                        paperWatchlistText = watchlist.symbols.joinToString(", "),
                        paperRiskConfigurationText = configText,
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        paperSetupLoaded = true,
                        backendOffline = error.isBackendUnavailable(),
                        errorMessage = safeMessage(error, "Unable to load saved Paper setup."),
                    )
                }
            }
        }
    }

    fun enablePaperAutomation() {
        viewModelScope.launch {
            runAction("Paper automation enabled.") { enablePaperAutomation() }
        }
    }

    fun disablePaperAutomation() {
        viewModelScope.launch {
            runAction("Paper automation disabled.") { disableAutomation("paper") }
        }
    }

    fun activateKillSwitch() {
        viewModelScope.launch {
            runAction("Emergency stop activated.") { activateKillSwitch() }
        }
    }

    private suspend fun runAction(successMessage: String, action: suspend ApiClient.() -> Unit) {
        val activeClient = apiClient ?: return
        _uiState.update { it.copy(isActionInProgress = true, errorMessage = null, infoMessage = null) }
        try {
            activeClient.action()
            _uiState.update { it.copy(isActionInProgress = false, infoMessage = successMessage) }
            refreshDashboard()
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isActionInProgress = false,
                    backendOffline = error.isBackendUnavailable(),
                    errorMessage = safeMessage(error, "Backend safety action failed."),
                )
            }
        }
    }

    fun disconnect() {
        val environment = currentEnvironmentPath()
        viewModelScope.launch {
            // Server revocation is attempted but local token deletion is never contingent on network success.
            try {
                apiClient?.disconnect(environment)
            } catch (_: Exception) {
                // No response details are logged or surfaced during local credential removal.
            } finally {
                clearLocalSession()
            }
        }
    }

    private fun clearLocalSession() {
        apiClient?.close()
        apiClient = null
        sessionStore.clear()
        backendUrlStore.clear()
        _uiState.value = TradingUiState(infoMessage = "Disconnected. The local session was removed.")
    }

    private fun currentEnvironmentPath(): String = when (_uiState.value.environment) {
        VerifiedEnvironment.LIVE -> "live"
        VerifiedEnvironment.PAPER, VerifiedEnvironment.UNKNOWN -> "paper"
    }

    private fun putScreenData(screenKey: String, data: RemoteScreenData) {
        _uiState.update { current ->
            current.copy(screenData = current.screenData + (screenKey to data))
        }
    }

    /** Server detail payloads are redacted again on-device before entering observable UI state. */
    private fun JsonElement.redactedDisplay(): String {
        fun redact(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> buildJsonObject {
                value.forEach { (key, child) ->
                    put(key, if (key.isSensitiveField()) JsonPrimitive("[redacted]") else redact(child))
                }
            }
            is JsonArray -> JsonArray(value.map(::redact))
            else -> value
        }
        return ApiClient.JSON.encodeToString(JsonElement.serializer(), redact(this)).take(MAX_SCREEN_PAYLOAD_CHARS)
    }

    private fun String.isSensitiveField(): Boolean {
        val normalized = lowercase()
        return normalized in sensitiveFieldNames ||
            normalized.contains("secret") ||
            normalized.contains("token") ||
            normalized.contains("passphrase") ||
            normalized.contains("authorization") ||
            normalized.contains("credential") ||
            normalized.contains("password") ||
            normalized.contains("private") ||
            normalized.endsWith("key") ||
            normalized.contains("api-key")
    }

    private fun HealthResponse.isAvailable(): Boolean =
        !status.equals("offline", ignoreCase = true) &&
            !status.equals("down", ignoreCase = true) &&
            !status.equals("unhealthy", ignoreCase = true) &&
            automationAvailable != false

    /** Do not surface server response bodies because a faulty server must not echo a submitted secret. */
    private fun safeMessage(error: Exception, fallback: String): String = when (error) {
        is ApiException -> if (error.statusCode == 401) "Session was rejected. Reconnect to the backend." else fallback
        else -> fallback
    }

    private fun Exception.isBackendUnavailable(): Boolean =
        this !is ApiException || (statusCode != null && statusCode >= 500)

    private data class DashboardSnapshot(
        val account: AccountResponse,
        val environment: EnvironmentResponse,
        val automation: AutomationStatusResponse,
    )

    private companion object {
        const val MAX_SCREEN_PAYLOAD_CHARS = 6_000
        val sensitiveFieldNames = setOf(
            "api_key", "api_secret", "key", "secret", "token", "session_token",
            "passphrase", "authorization", "account_id", "account_number",
        )
    }
}
