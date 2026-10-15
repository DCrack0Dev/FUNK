package com.alpacatalk.trading.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.alpacatalk.trading.data.TradeJournalEngine
import com.alpacatalk.trading.data.TradeEligibilityRules
import com.alpacatalk.trading.data.ScannerBaskets
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpacatalk.trading.DirectTradingState
import com.alpacatalk.trading.DirectTradingViewModel
import com.alpacatalk.trading.ClosedTrade
import com.alpacatalk.trading.JournalEntry
import com.alpacatalk.trading.data.DirectEnvironment
import com.alpacatalk.trading.data.OandaEnvironment
import com.alpacatalk.trading.data.CredentialVault
import com.alpacatalk.trading.data.CredentialVaultInvalidatedException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 0x4F21

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
fun DirectTradingApp(viewModel: DirectTradingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2_400)
        showSplash = false
    }
    if (showSplash) {
        FinKillerSplash()
    } else if (state.ctraderConnected) CtraderDashboard(state, viewModel) else if (state.oandaEnvironment != null) OandaDashboard(state, viewModel) else if (state.connected) DirectDashboard(state, viewModel) else DirectConnection(state, viewModel)
}

@Composable
private fun FinKillerSplash() {
    val pulse = rememberInfiniteTransition(label = "splash-pulse")
    val outerScale by pulse.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1_700, easing = LinearEasing), RepeatMode.Reverse),
        label = "outer-scale",
    )
    val innerScale by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse),
        label = "inner-scale",
    )
    val shimmer by pulse.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmer",
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(230.dp)) {
                Box(modifier = Modifier.size(215.dp).scale(outerScale).alpha(0.18f).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape))
                Box(modifier = Modifier.size(178.dp).scale(innerScale).alpha(0.30f).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                Box(modifier = Modifier.size(142.dp).background(MaterialTheme.colorScheme.background, androidx.compose.foundation.shape.CircleShape))
                Image(painter = painterResource(com.alpacatalk.trading.R.drawable.fin_killer_pro_icon), contentDescription = "Fin Killer Pro", modifier = Modifier.size(104.dp).alpha(shimmer))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("FIN KILLER PRO", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text("Your trading cockpit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("CONNECT  ·  ANALYZE  ·  EXECUTE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DirectConnection(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var broker by remember { mutableStateOf("alpaca") }
    var oandaToken by remember { mutableStateOf("") }
    var oandaEnvironment by remember { mutableStateOf(OandaEnvironment.PRACTICE) }
    var ctraderClientId by remember { mutableStateOf("") }
    var ctraderClientSecret by remember { mutableStateOf("") }
    var ctraderLive by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val vault = remember { CredentialVault(context) }
    var rememberCredentials by remember { mutableStateOf(true) }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    fun authenticateVault(onAuthenticated: () -> Unit) {
        val host = activity ?: run { savedNotice = "Biometric unlock is unavailable on this device."; return }
        val executor = ContextCompat.getMainExecutor(host)
        BiometricPrompt(host, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onAuthenticated() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { savedNotice = errString.toString() }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Fin Killer Pro").setSubtitle("Authenticate to use saved broker credentials").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }
    fun unlockSaved(brokerKey: String, onUnlocked: (Map<String, String>) -> Unit) = authenticateVault {
        runCatching { vault.read(brokerKey) }
            .onSuccess { onUnlocked(it); savedNotice = "Saved $brokerKey credentials unlocked." }
            .onFailure { error -> savedNotice = if (error is CredentialVaultInvalidatedException) error.message else "Could not unlock saved credentials. Authenticate again or re-enter them." }
    }
    fun saveThenConnect(brokerKey: String, values: Map<String, String>, connect: () -> Unit) {
        if (!rememberCredentials) { connect(); return }
        authenticateVault {
            runCatching { vault.save(brokerKey, values) }
                .onFailure { error -> savedNotice = if (error is CredentialVaultInvalidatedException) error.message else "Credentials were not saved; they will be used only for this session." }
            connect()
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painter = painterResource(com.alpacatalk.trading.R.drawable.fin_killer_pro_wordmark), contentDescription = "Fin Killer Pro", modifier = Modifier.fillMaxWidth().height(88.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Welcome to your trading cockpit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Connect securely. See clearly. Trade deliberately.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Direct broker connection with no third-party trading server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Choose your connection", fontWeight = FontWeight.Bold)
                    Text("Paper and live environments are detected automatically. Fin Killer Pro refuses to guess when credentials are ambiguous.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NavigationPill("Alpaca", Icons.Outlined.AccountBalanceWallet, broker == "alpaca", Modifier.weight(1f)) { broker = "alpaca" }
                NavigationPill("OANDA", Icons.Outlined.AutoGraph, broker == "oanda", Modifier.weight(1f)) { broker = "oanda" }
                NavigationPill("cTrader", Icons.Outlined.SwapHoriz, broker == "ctrader", Modifier.weight(1f)) { broker = "ctrader" }
            }
            state.error?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) { Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
            state.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (broker == "alpaca") "Sign in to Alpaca" else if (broker == "oanda") "Sign in to OANDA" else "Authorize cTrader", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("Your credentials are used only for the direct broker connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vault.has(broker)) OutlinedButton(onClick = { unlockSaved(broker) { saved -> apiKey = saved["apiKey"] ?: apiKey; apiSecret = saved["apiSecret"] ?: apiSecret; oandaToken = saved["token"] ?: oandaToken; ctraderClientId = saved["clientId"] ?: ctraderClientId; ctraderClientSecret = saved["clientSecret"] ?: ctraderClientSecret } }, modifier = Modifier.fillMaxWidth()) { Text("Unlock saved credentials", maxLines = 1, softWrap = false) }
            if (broker == "alpaca") OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Alpaca API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (broker == "alpaca") OutlinedTextField(
                value = apiSecret,
                onValueChange = { apiSecret = it },
                label = { Text("Alpaca API secret") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (broker == "ctrader") {
                Text("cTrader account authorization", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Authorize your cTrader ID, then choose the Pepperstone account.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { ctraderLive = false }, modifier = Modifier.weight(1f)) { Text(if (!ctraderLive) "✓ Demo" else "Demo") }
                    OutlinedButton(onClick = { ctraderLive = true }, modifier = Modifier.weight(1f)) { Text(if (ctraderLive) "✓ Live" else "Live") }
                }
                OutlinedTextField(value = ctraderClientId, onValueChange = { ctraderClientId = it }, label = { Text("cTrader Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ctraderClientSecret, onValueChange = { ctraderClientSecret = it }, label = { Text("cTrader Client Secret") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val clientId = ctraderClientId
                    val clientSecret = ctraderClientSecret
                    saveThenConnect("ctrader", mapOf("clientId" to clientId, "clientSecret" to clientSecret)) {
                        viewModel.beginCtraderAuthorization(clientId.toCharArray(), clientSecret.toCharArray(), ctraderLive) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        ctraderClientSecret = ""
                    }
                }, enabled = ctraderClientId.isNotBlank() && ctraderClientSecret.isNotBlank() && !state.isLoading, modifier = Modifier.fillMaxWidth()) { Text("Authorize cTrader", maxLines = 1, softWrap = false) }
                state.ctraderAccounts.forEach { account ->
                    val id = account["ctidTraderAccountId"]?.jsonPrimitive?.content ?: account["accountId"]?.jsonPrimitive?.content ?: return@forEach
                    OutlinedButton(onClick = { viewModel.selectCtraderAccount(account) }, modifier = Modifier.fillMaxWidth()) { Text("Use cTrader account $id") }
                }
            } else if (broker == "oanda") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { oandaEnvironment = OandaEnvironment.PRACTICE }, modifier = Modifier.weight(1f)) { Text(if (oandaEnvironment == OandaEnvironment.PRACTICE) "✓ Practice" else "Practice") }
                    OutlinedButton(onClick = { oandaEnvironment = OandaEnvironment.LIVE }, modifier = Modifier.weight(1f)) { Text(if (oandaEnvironment == OandaEnvironment.LIVE) "✓ Live" else "Live") }
                }
                OutlinedTextField(value = oandaToken, onValueChange = { oandaToken = it }, label = { Text("OANDA personal API token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val token = oandaToken
                    saveThenConnect("oanda", mapOf("token" to token)) {
                        viewModel.probeOanda(token.toCharArray(), oandaEnvironment)
                        oandaToken = ""
                    }
                }, enabled = oandaToken.isNotBlank() && !state.isLoading, modifier = Modifier.fillMaxWidth()) { Text("Connect to OANDA", maxLines = 1, softWrap = false) }
                state.oandaAccounts.forEach { account ->
                    val accountId = account["id"]?.jsonPrimitive?.content ?: return@forEach
                    OutlinedButton(onClick = { viewModel.selectOandaAccount(accountId, oandaEnvironment) }, modifier = Modifier.fillMaxWidth()) { Text("Use OANDA account $accountId") }
                }
            } else Button(
                onClick = {
                    val key = apiKey
                    val secret = apiSecret
                    fun connectNow() {
                        viewModel.connect(key.toCharArray(), secret.toCharArray())
                        apiKey = ""
                        apiSecret = ""
                    }
                    saveThenConnect("alpaca", mapOf("apiKey" to key, "apiSecret" to secret)) { connectNow() }
                },
                enabled = apiKey.isNotBlank() && apiSecret.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isLoading) "Detecting account" else "Connect to Alpaca") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberCredentials, onCheckedChange = { rememberCredentials = it })
                        Text("Remember encrypted credentials", style = MaterialTheme.typography.bodySmall)
                    }
                    savedNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("Credentials go directly to the selected broker over HTTPS and remain in session memory until disconnect. If Remember is enabled, encrypted AES-GCM credentials require recent device authentication to save or unlock. Screenshots and screen recording are allowed; avoid capturing this screen or account details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CtraderDashboard(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    val account = state.ctraderAccounts.firstOrNull()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("cTrader workspace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("Pepperstone cTrader account authorized", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Authorized account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(account?.toString() ?: "Selected account")
                    Text("Demo/live selection is fixed for this session. No fallback is performed.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("The cTrader OAuth account authorization is complete. Symbol discovery and order routing remain scoped to this selected account.", style = MaterialTheme.typography.bodyMedium)
            TerminalPanel("cTrader terminal", state.terminal, "cTrader authentication events will appear here.", "Copy cTrader terminal", onCopy = {})
            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect cTrader") }
        }
    }
}

@Composable
private fun OandaDashboard(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    var units by remember { mutableStateOf("1") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("OANDA XAU/USD", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(state.oandaEnvironment?.label ?: "OANDA", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Account summary dashboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    JsonValueRow("Account", state.oandaSummary, "id")
                    JsonValueRow("Balance", state.oandaSummary, "balance")
                    JsonValueRow("NAV", state.oandaSummary, "NAV")
                    JsonValueRow("Margin available", state.oandaSummary, "marginAvailable")
                    JsonValueRow("Unrealized P/L", state.oandaSummary, "unrealizedPL")
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("XAU_USD eligibility", fontWeight = FontWeight.Bold)
                    Text(state.oandaEligibility ?: "Checking account instruments…")
                    Text("OANDA gold CFD only. This is not GC futures.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Latest price response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.oandaQuote?.toString() ?: "No XAU_USD price response", style = MaterialTheme.typography.bodySmall)
            Text("Trade XAU_USD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = units, onValueChange = { units = it }, label = { Text("Units (negative = sell)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = stop, onValueChange = { stop = it }, label = { Text("Stop-loss price (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("Take-profit price (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.tradeOandaXau(units, stop, target) }, enabled = units.toDoubleOrNull() != null && units.toDouble() != 0.0, modifier = Modifier.fillMaxWidth()) { Text("Submit market order", maxLines = 1, softWrap = false) }
            state.orderStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            TerminalPanel("OANDA terminal", state.terminal, "OANDA connection and order events will appear here.", "Copy OANDA terminal", onCopy = {})
            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect OANDA") }
        }
    }
}

@Composable
private fun WorkspaceHeader(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    val live = state.environment == DirectEnvironment.LIVE
    ElevatedCard(modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, if (live) DangerRed.copy(alpha = 0.65f) else LiquidGold.copy(alpha = 0.22f)), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("FIN KILLER PRO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = if (live) LiveContainer else PaperContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
                        Text(if (live) " LIVE ACCOUNT " else " PAPER ACCOUNT ", modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = if (live) LiveRed else PaperBlue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(7.dp))
                    Icon(Icons.Outlined.Circle, contentDescription = "Connected · auto-sync every 5 seconds", tint = DeepTeal, modifier = Modifier.size(10.dp))
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LiquidGoldLight)
            } else {
                IconButton(onClick = viewModel::refresh, enabled = !state.isScanning) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh account")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBottomNavigation(
    selectedTab: Int,
    tradeCount: Int,
    journalErrorCount: Int,
    onSelect: (Int) -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    Surface(
        modifier = Modifier.width(screenWidth).offset(x = (-12).dp).background(Brush.verticalGradient(listOf(Onyx, Obsidian))).border(BorderStroke(1.dp, LiquidGold.copy(alpha = 0.35f))),
        color = Color.Transparent,
        tonalElevation = 8.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            BottomTab("Dashboard", Icons.Outlined.Dashboard, selectedTab == 0, null) { onSelect(0) }
            BottomTab("Trades", Icons.Outlined.SwapHoriz, selectedTab == 1, tradeCount.takeIf { it > 0 }) { onSelect(1) }
            BottomTab("Scanner", Icons.Outlined.AutoGraph, selectedTab == 2, null) { onSelect(2) }
            BottomTab("Journal", Icons.Outlined.Book, selectedTab == 3, journalErrorCount.takeIf { it > 0 }) { onSelect(3) }
        }
    }
}

@Composable
private fun RowScope.BottomTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    badge: Int?,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val indicatorHeight by animateDpAsState(if (selected) 3.dp else 0.dp, animationSpec = tween(150), label = "tab-indicator")
    Column(
        modifier = Modifier.weight(1f).fillMaxSize().background(if (selected) LiquidGold.copy(alpha = 0.08f) else Color.Transparent).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(indicatorHeight).background(LiquidGold))
        Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.padding(top = 7.dp).size(25.dp)) {
            Icon(icon, contentDescription = label, tint = if (selected) LiquidGoldLight else Ivory.copy(alpha = 0.60f), modifier = Modifier.size(24.dp))
            if (badge != null) {
                Surface(color = LiquidGold, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.offset(x = 6.dp, y = (-4).dp).size(18.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(if (badge > 99) "99+" else badge.toString(), color = Obsidian, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
                }
            }
        }
        Text(label, color = if (selected) LiquidGoldLight else Ivory.copy(alpha = 0.60f), fontSize = 11.sp, letterSpacing = 0.3.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun NavigationPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, fontSize = 11.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, positive: Boolean? = null) {
    val equityHero = label.startsWith("Equity")
    ElevatedCard(modifier = modifier.border(BorderStroke(1.dp, LiquidGold.copy(alpha = 0.20f)), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = if (equityHero) TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, fontWeight = FontWeight.Bold, brush = Brush.linearGradient(listOf(LiquidGoldLight, LiquidGold))) else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = if (equityHero) FontFamily.Serif else FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                color = when (positive) {
                    true -> ProfitGreen
                    false -> MaterialTheme.colorScheme.error
                    null -> if (equityHero) LiquidGoldLight else MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun DirectDashboard(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val groups = ScannerBaskets.baskets
    val allBasketSymbols = ScannerBaskets.allSymbols
    var selectedGroup by remember { mutableStateOf(groups.first().first) }
    var symbols by remember { mutableStateOf(groups.first().second) }
    var liveConfirmation by remember { mutableStateOf(false) }
    var autoConfirmLiveConfirmation by rememberSaveable { mutableStateOf(false) }
    var copyNotice by remember { mutableStateOf<String?>(null) }
    var showAccountDetails by rememberSaveable { mutableStateOf(false) }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var showManualConfirmOrders by rememberSaveable { mutableStateOf(false) }
    var showAutoScanResults by rememberSaveable { mutableStateOf(false) }
    var showAutoConfirmOrders by rememberSaveable { mutableStateOf(false) }
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationPermissionGranted by remember(appContext) { mutableStateOf(ScanAlertNotifications.hasPermission(appContext)) }
    var notificationSettingsEnabled by remember(appContext) { mutableStateOf(ScanAlertNotifications.notificationsEnabled(appContext)) }
    var notificationPermissionNotice by remember { mutableStateOf<String?>(null) }
    DisposableEffect(appContext, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermissionGranted = ScanAlertNotifications.hasPermission(appContext)
                notificationSettingsEnabled = ScanAlertNotifications.notificationsEnabled(appContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val clipboardManager = LocalClipboardManager.current
    val best = state.snapshots.maxByOrNull { it.score }
    val copyTerminal = { title: String, lines: List<String> ->
        clipboardManager.setText(AnnotatedString("$title\n${lines.joinToString("\n")}"))
        copyNotice = "$title copied (${lines.size} line${if (lines.size == 1) "" else "s"})."
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkspaceHeader(state, viewModel)
            val notice = state.error ?: state.permissionWarning
            notice?.let {
                val isError = state.error != null || state.permissionWarning != null
                Card(colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            if (selectedTab == 0) {
                Text("Trading cockpit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("A live view of account health, exposure, and opportunities.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Equity (USD)", value(state.account, "equity"), Icons.Outlined.AccountBalanceWallet, Modifier.weight(1f))
                    MetricCard("Today (USD)", dailyPnl(state.account), Icons.Outlined.AutoGraph, Modifier.weight(1f), positive = number(state.account, "equity")?.let { equity -> number(state.account, "last_equity")?.let { equity >= it } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Open trades", state.positions.size.toString(), Icons.Outlined.SwapHoriz, Modifier.weight(1f))
                    MetricCard("Pending", state.pendingOrders.size.toString(), Icons.Outlined.PendingActions, Modifier.weight(1f))
                }
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Account overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Buying power: USD ${moneyValue(state.account, "buying_power")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("Cash: USD ${moneyValue(state.account, "cash")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(onClick = { showAccountDetails = !showAccountDetails }) { Text(if (showAccountDetails) "Hide" else "Details") }
                        }
                        Text("Auto-sync: every 10 seconds · use Refresh for an immediate update", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (showAccountDetails) {
                            JsonValueRow("Account ID", state.account, "id")
                            JsonValueRow("Status", state.account, "status")
                        }
                    }
                }
            }
            if (selectedTab == 0) {
            if (state.positions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Positions dashboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { selectedTab = 1 }) { Text("View all", maxLines = 1, softWrap = false) }
                        }
                        state.positions.forEach { position ->
                            val positionPnl = number(position, "unrealized_pl") ?: 0.0
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(value(position, "symbol"), fontWeight = FontWeight.Bold)
                                    Text("Qty ${value(position, "qty")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                    Text("USD ${moneyValue(position, "market_value")}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Surface(
                                        color = if (positionPnl >= 0) ProfitGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.errorContainer,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                    ) {
                                        Text("P/L ${signedNumber(position, "unrealized_pl")} (${signedPercent(position, "unrealized_plpc")})", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (positionPnl >= 0) ProfitGreen else MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                if (state.positions.size > 5) Text("+ ${state.positions.size - 5} more position(s)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            } else if (selectedTab == 2) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val scannerTabIndex = when {
                showAutoConfirmOrders -> 3
                showAutoScanResults -> 2
                showManualConfirmOrders -> 1
                else -> 0
            }
            TabRow(selectedTabIndex = scannerTabIndex) {
                Tab(selected = scannerTabIndex == 0, onClick = { showManualConfirmOrders = false; showAutoScanResults = false; showAutoConfirmOrders = false }, text = { Text("Manual scan") })
                Tab(selected = scannerTabIndex == 1, onClick = { showManualConfirmOrders = true; showAutoScanResults = false; showAutoConfirmOrders = false }, text = { Text("Manual confirm (${state.selectedTradeSymbols.size})") })
                Tab(selected = scannerTabIndex == 2, onClick = { showManualConfirmOrders = false; showAutoScanResults = true; showAutoConfirmOrders = false }, text = { Text("Auto-scan findings") })
                Tab(selected = scannerTabIndex == 3, onClick = { showManualConfirmOrders = false; showAutoScanResults = false; showAutoConfirmOrders = true }, text = { Text("Auto confirm (${state.autoScanConfirmQueue.size})") })
            }
            Text(
                "Auto scan · ${groups.size} baskets · ${allBasketSymbols.size} symbols · up to ${ScannerBaskets.AUTO_SCAN_MAX_CONCURRENT_BATCHES} parallel requests",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = { viewModel.toggleAutoTrading() },
                enabled = state.autoTrading || (!state.isScanning && !state.isLoading && state.environment != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.autoTrading) "Stop background scan monitor" else "Start background scan monitor", maxLines = 1, softWrap = false)
            }
            state.autoScanProgress?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            state.autoScanNotificationStatus?.let {
                Text("Alerts: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Setup definitions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("SCALP · mean 1m/15m component scores; candidate needs both timeframe alignments, a breakout/sweep, volume expansion, and R:R ≥ 1.5.", style = MaterialTheme.typography.bodySmall)
                    Text("SWING · mean 1h/1d component scores; candidate needs an aligned timeframe, breakout/sweep, and base R:R ≥ 2.0; actionable results require R:R ≥ 1.5.", style = MaterialTheme.typography.bodySmall)
                    Text("Protective bracket sides: BUY/LONG stop-loss below entry, target above; SELL/SHORT stop-loss above entry, target below. The app checks both against the fresh order-side quote before submitting.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("Scanner score is a rule score, not a win probability: trend/EMA 25, VWAP 15, breakout/sweep 20, volume 15, R:R 15, history 10. Group means are compared; the higher is shown.", style = MaterialTheme.typography.bodySmall)
                    Text("App rating = rounded 70% scanner score + 30% eligibility-memory rating. Before 20 closed samples, the memory component cannot exceed neutral 50 (so it may penalize, never boost); memory directives adjust the scanner score separately.", style = MaterialTheme.typography.bodySmall)
                    Text("Paper test mode can admit a high-scoring developing idea without the full scalp/swing candidate rule; it still requires verified data, a clear side, adjusted score, and app rating. That is a test exception, not a confirmed edge.", style = MaterialTheme.typography.bodySmall)
                    Text("Memory is deterministic replay of stored closed trades; it is not a backtest, calibrated probability, or out-of-sample validation. Confirmed status means rules passed, not that the setup is profitable.", style = MaterialTheme.typography.bodySmall)
                }
            }
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Opportunity scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (showScanner) "Scan, score, and select setups" else "Scanner collapsed · your selections are preserved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showScanner = !showScanner }) { Text(if (showScanner) "Collapse" else "Open") }
                }
            }
            if (showScanner) {
            Text("Asset groups", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose a basket, scan it, then select qualifying symbols.", style = MaterialTheme.typography.bodySmall)
            groups.forEach { (name, groupSymbols) ->
                OutlinedButton(onClick = { selectedGroup = name; symbols = groupSymbols }, enabled = !state.autoTrading && !state.isScanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (name == selectedGroup) "✓ $name" else name)
                }
            }
            Text("Selected group: $selectedGroup", fontWeight = FontWeight.Bold)
            Text("Market scanner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = symbols, onValueChange = { symbols = it }, enabled = !state.autoTrading && !state.isScanning, label = { Text("Up to 10 markets") }, supportingText = { Text("Comma-separated symbols") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.scanMarkets(symbols) }, enabled = !state.isScanning && !state.isLoading && !state.autoTrading, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isScanning) "Scanning…" else "Scan symbols", maxLines = 1, softWrap = false)
            }
            if (showManualConfirmOrders) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Manual confirmation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("This tab is independent from auto-scan confirmation. Review only the setups selected from Manual scan, then submit them explicitly.", style = MaterialTheme.typography.bodySmall)
                        Text("Selected setups: ${state.selectedTradeSymbols.joinToString().ifBlank { "none" }}", fontWeight = FontWeight.Bold)
                        Button(
                            onClick = viewModel::executeSelectedSet,
                            enabled = state.environment == DirectEnvironment.PAPER && state.selectedTradeSymbols.isNotEmpty() && !state.isLoading && !state.isScanning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.environment == DirectEnvironment.LIVE) "Live execution disabled"
                                else "Confirm and submit ${state.selectedTradeSymbols.size} selected order(s) · PAPER",
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            Text("Execution is Paper-only in this build. Manual and auto confirmation use the same guarded execution path; selecting a tab never submits an order. Bracket orders are regular-session only, Monday–Friday 09:30–16:00 New York time. Extended-hours findings remain scan-only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(
                    onClick = {
                        notificationPermissionNotice = null
                        val activity = context.findFragmentActivity()
                        if (activity == null) {
                            notificationPermissionNotice = "Could not find the active Android screen to request permission. Reopen the scanner and try again."
                        } else runCatching {
                            ActivityCompat.requestPermissions(
                                activity,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST_CODE,
                            )
                        }
                            .onFailure { error -> notificationPermissionNotice = "Could not open Android's notification permission prompt: ${error.message ?: "try again after reopening the scanner"}" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow setup notifications") }
            } else if (!notificationSettingsEnabled) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, appContext.packageName),
                            )
                        }.onFailure { error -> notificationPermissionNotice = "Could not open Android notification settings: ${error.message ?: "open app settings manually"}" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open notification settings") }
            }
            notificationPermissionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val qualified = state.environment?.let { environment ->
                state.snapshots.filter { setup ->
                    state.hasFreshScanResult(setup.symbol) &&
                        TradeEligibilityRules.evaluate(setup, environment, state.journal, state.minimumSignalScore).eligible
                }.sortedByDescending { it.score }
            }.orEmpty()
            if (state.snapshots.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Select setups for manual execution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Select setups that pass the ${state.minimumSignalScore}–100 score/rating, memory, strategy, and minimum 1:1.5 reward-risk gates. Scan alerts never submit orders; findings below 1:1.5 are excluded.", style = MaterialTheme.typography.bodySmall)
                        Text("App rating = rounded 70% scanner score + 30% eligibility-memory rating. Before 20 closed samples the memory component cannot exceed neutral 50, though negative memory can still penalize. Memory directives also adjust scanner score separately; both scores must be at least ${state.minimumSignalScore}, and live trades require a confirmed setup.", style = MaterialTheme.typography.bodySmall)
                        if (qualified.isEmpty()) {
                            Text("No setups currently pass every execution gate. Scan another group or wait for better market conditions.")
                            state.snapshots.sortedByDescending { it.score }.take(10).forEach { snapshot ->
                                val memory = TradeJournalEngine.decision(state.journal, snapshot.symbol, snapshot.setup)
                                val assessment = state.environment?.let { TradeEligibilityRules.evaluate(snapshot, it, state.journal, state.minimumSignalScore) }
                                val quoteAge = snapshot.quoteTimestampEpochMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) / 1000 }?.let { "${it}s" } ?: "unknown"
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("${snapshot.symbol} · rule-qualified candidate assessment", fontWeight = FontWeight.Bold)
                                    Text("Signal quality: checklist ${snapshot.score}/100 · ${snapshot.setup}; memory ${memory.rating}/100 · app rating ${assessment?.appRating ?: 0}/100 · adjusted ${assessment?.adjustedScore ?: snapshot.score}/100.", style = MaterialTheme.typography.bodySmall)
                                    Text("Evidence confidence: UNVALIDATED · out-of-sample result unavailable · ${snapshot.timeframesCovered}/4 timeframes · quote age ${quoteAge} · symbol/family samples ${memory.symbolRecord?.trades ?: 0}/${memory.familyRecord?.trades ?: 0} · ${snapshot.historyBars} max bars · ${snapshot.dataQualityReason}", style = MaterialTheme.typography.bodySmall)
                                    Text("Execution eligibility: BLOCK · ${assessment?.blockReasons?.joinToString()?.ifBlank { "strategy candidate qualification not met" } ?: "broker mode is not connected"}", style = MaterialTheme.typography.bodySmall)
                                    snapshot.scoreBreakdown.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        } else {
                            qualified.forEach { snapshot ->
                                val memory = TradeJournalEngine.decision(state.journal, snapshot.symbol, snapshot.setup)
                                val appRating = TradeJournalEngine.appRatingForTrade(snapshot.score, memory)
                                val adjustedScore = (snapshot.score + memory.scoreAdjustment).coerceIn(0, 100)
                                val symbolMemory = memory.symbolRecord
                                val familyMemory = memory.familyRecord
                                val eligibility = state.environment?.let { TradeEligibilityRules.evaluate(snapshot, it, state.journal, state.minimumSignalScore) }
                                val quoteAge = snapshot.quoteTimestampEpochMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) / 1000 }?.let { "${it}s" } ?: "unknown"
                                Column {
                                    OutlinedButton(onClick = { viewModel.selectTradeSymbol(snapshot.symbol) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (snapshot.symbol in state.selectedTradeSymbols) "☑ ${snapshot.symbol} · scan ${snapshot.score}/100 · app ${appRating}/100 · selected" else "☐ ${snapshot.symbol} · scan ${snapshot.score}/100 · app ${appRating}/100")
                                    }
                                    Text("Signal quality: checklist ${snapshot.score}/100 · ${snapshot.setup} · adjusted score $adjustedScore/100 · setup memory ${memory.rating}/100.", style = MaterialTheme.typography.bodySmall)
                                    Text("Evidence confidence: UNVALIDATED · out-of-sample result unavailable · ${snapshot.timeframesCovered}/4 timeframes · quote age ${quoteAge} · symbol/family samples ${symbolMemory?.trades ?: 0}/${familyMemory?.trades ?: 0} · ${snapshot.historyBars} max bars · spread ${snapshot.spreadPct?.let { "%.3f%%".format(java.util.Locale.US, it) } ?: "unknown"}.", style = MaterialTheme.typography.bodySmall)
                                    val bracketSideCheck = if (snapshot.direction == com.alpacatalk.trading.data.TradeDirection.LONG) "LONG: stop < entry < target" else "SHORT: target < entry < stop"
                                    val executionLabel = if (snapshot.isExecutableSetup) "PASS" else "BLOCKED"
                                    Text("Execution eligibility: $executionLabel — ${if (snapshot.isExecutableSetup) "rule-qualified candidate" else "not executable until all directional and data gates pass"}; ${eligibility?.blockReasons?.joinToString()?.ifBlank { "all scan/rating gates passed" } ?: "eligibility unavailable"} · ${snapshot.executionGate} · ${snapshot.dataQualityReason} · $bracketSideCheck · entry ${snapshot.lastPrice.money()} · stop ${snapshot.scannerStopPrice?.money() ?: "unknown"} · target ${snapshot.scannerTargetPrice?.money() ?: "unknown"} · auto R:R ${snapshot.rewardRisk?.let { "1:%.2f".format(java.util.Locale.US, it) } ?: "unknown"} (minimum 1:1.5).", style = MaterialTheme.typography.bodySmall)
                                    Text("Memory evidence: symbol ${symbolMemory?.trades ?: 0} trade(s), ${symbolMemory?.wins ?: 0}W/${symbolMemory?.losses ?: 0}L; family ${familyMemory?.trades ?: 0} trade(s), ${familyMemory?.wins ?: 0}W/${familyMemory?.losses ?: 0}L. Directive ${memory.directive}; ${memory.reason}. This is a small-sample rule heuristic, not statistical validation.", style = MaterialTheme.typography.bodySmall)
                                    snapshot.scoreBreakdown.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                        Text("Selected setups (${state.selectedTradeSymbols.size}): ${state.selectedTradeSymbols.joinToString().ifBlank { "none" }}", fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (state.environment == DirectEnvironment.LIVE) {
                Text("LIVE ACCOUNT — read-only in this build; order placement and order/position mutations are disabled.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Text(
                when {
                    !state.autoTrading -> "Background scan monitor stopped — scanning and selecting setups do not submit an order."
                    state.scanMonitorPaused -> "Scan alerts paused — ${state.scanMonitorStatus ?: "waiting for the risk snapshot"}"
                    else -> "Background scan alerts active — continues after this screen is closed; scanning never submits or modifies orders."
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            val stopRisk = state.openStopRisk
            Text(
                if (stopRisk.isFresh()) {
                    "Modeled downside risk to verified market stops: USD ${stopRisk.downsideRiskDollars!!.money()} / USD ${stopRisk.riskCapDollars!!.money()} cap (${"%.1f".format(java.util.Locale.US, stopRisk.riskPercent ?: 0.0)}% of equity); risk-free positions: ${stopRisk.riskFreePositionCount}. Above-entry gains are not credited; gaps/slippage can exceed this estimate."
                } else {
                    "Open stop risk is unverified or stale; the scan monitor pauses until Alpaca positions and protective stops can be verified."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Setup alerts require Android notifications to be allowed for Fin Killer Pro. Recent scan results expire after three minutes and must be rescanned before execution.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("The scanner evaluates both LONG and SHORT setups. Conflicting directions are blocked; short order routing is for U.S. equities only and requires Alpaca to verify borrowability at submission.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Orders now require fresh broker equity/positions/open-order and protective-stop risk verification, per-trade risk sizing, 2% batch / 3% strategy limits, an 80% gross-notional ceiling, and a 10% absolute stop-risk ceiling. Data gaps, stale quotes, off-session times, or any ambiguous exposure block execution.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Per-trade stop-risk budget: ${"%.2f".format(java.util.Locale.US, state.perTradeRiskBudgetPct * 100.0)}% of verified equity (hard maximum 1.00%). This setting persists on this device.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(0.0025, 0.005, 0.0075, 0.01).forEach { riskPct ->
                    val selected = kotlin.math.abs(state.perTradeRiskBudgetPct - riskPct) < 0.00001
                    if (selected) Button(onClick = { }, enabled = false, modifier = Modifier.weight(1f)) {
                        Text("${"%.2f".format(java.util.Locale.US, riskPct * 100.0)}%", maxLines = 1, softWrap = false)
                    } else OutlinedButton(onClick = { viewModel.setPerTradeRiskBudgetPct(riskPct) }, modifier = Modifier.weight(1f)) {
                        Text("${"%.2f".format(java.util.Locale.US, riskPct * 100.0)}%", maxLines = 1, softWrap = false)
                    }
                }
            }
            if (!showManualConfirmOrders && !showAutoScanResults && !showAutoConfirmOrders) {
            if (state.snapshots.isNotEmpty()) {
                Text("Findings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                state.snapshots.sortedByDescending { it.score }.forEach { snapshot ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            val memory = TradeJournalEngine.decision(state.journal, snapshot.symbol, snapshot.setup)
                            val appRating = TradeJournalEngine.appRatingForTrade(snapshot.score, memory)
                            val adjustedScore = (snapshot.score + memory.scoreAdjustment).coerceIn(0, 100)
                            Text("${snapshot.symbol} · ${snapshot.direction?.label ?: "NO CLEAR SIDE"}  scanner ${snapshot.score}/100 · app ${appRating}/100 · adjusted $adjustedScore/100  ${snapshot.setup}${if (!state.hasFreshScanResult(snapshot.symbol)) " · STALE / LAST KNOWN" else ""}${if (snapshot.symbol in state.selectedTradeSymbols) " · SELECTED" else ""}", fontWeight = FontWeight.Bold)
                            Text(snapshot.rationale, style = MaterialTheme.typography.bodySmall)
                            Text("Scalp: ${snapshot.scalpRationale}", style = MaterialTheme.typography.bodySmall)
                            Text("Swing: ${snapshot.swingRationale}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            best?.let { setup ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Highest-scoring finding", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${setup.symbol} · ${setup.direction?.label ?: "NO CLEAR SIDE"} — ${setup.setup} — ${setup.score}/100")
                        Text(setup.rationale, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            } else if (showAutoScanResults) {
                Text("Auto-scan findings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Results from the all-basket monitor are shown here separately from one-time manual scans. These are findings only; this monitor never submits or manages orders.", style = MaterialTheme.typography.bodySmall)
                if (state.autoScanLastCompletedAtEpochMs > 0L) {
                    Text("Last complete sweep: ${java.time.Instant.ofEpochMilli(state.autoScanLastCompletedAtEpochMs)} · ${state.autoScanResults.size} usable finding(s)", style = MaterialTheme.typography.bodySmall)
                } else if (state.autoScanResults.isEmpty()) {
                    Text("No auto-scan findings yet. Start the monitor and wait for a complete sweep.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.autoScanResults.isNotEmpty()) {
                    state.autoScanResults.forEach { snapshot ->
                        val memory = TradeJournalEngine.decision(state.journal, snapshot.symbol, snapshot.setup)
                        val appRating = TradeJournalEngine.appRatingForTrade(snapshot.score, memory)
                        val assessment = state.environment?.let { TradeEligibilityRules.evaluate(snapshot, it, state.journal, state.minimumSignalScore) }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${snapshot.symbol} · ${snapshot.direction?.label ?: "NO CLEAR SIDE"} · ${if (assessment?.eligible == true) "QUALIFIES" else "does not qualify"}", fontWeight = FontWeight.Bold, color = if (assessment?.eligible == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text("${snapshot.setup} · scan ${snapshot.score}/100 · app $appRating/100 · adjusted ${assessment?.adjustedScore ?: (snapshot.score + memory.scoreAdjustment).coerceIn(0, 100)}/100 · auto R:R ${snapshot.rewardRisk?.let { "1:%.2f".format(java.util.Locale.US, it) } ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                                Text(snapshot.rationale, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                Text("Confirm orders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Each completed batch adds setups that pass the current execution gates. Nothing is submitted until you select setups and tap Confirm; live orders require another confirmation. SHORT candidates are checked against Alpaca’s active/tradable/shortable/easy-to-borrow flags at submission and are blocked if that data is missing or false. Candidates expire three minutes after their scan and are rechecked before submission.", style = MaterialTheme.typography.bodySmall)
                if (state.autoScanConfirmQueue.isEmpty()) {
                    Text("No qualifying auto-scan setups are waiting for confirmation.", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.autoScanConfirmQueue.forEach { candidate ->
                        val setup = candidate.setup
                        val ageMs = System.currentTimeMillis() - candidate.capturedAtEpochMs
                        val fresh = ageMs in 0..180_000L
                        val memory = TradeJournalEngine.decision(state.journal, setup.symbol, setup.setup)
                        val appRating = TradeJournalEngine.appRatingForTrade(setup.score, memory)
                        val selected = setup.symbol in state.autoScanConfirmSelectedSymbols
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${setup.symbol} · ${setup.direction?.label ?: "NO CLEAR SIDE"} · ${setup.setup}", fontWeight = FontWeight.Bold)
                                Text("Scan ${setup.score}/100 · app ${appRating}/100 · adjusted ${(setup.score + memory.scoreAdjustment).coerceIn(0, 100)}/100 · ${if (fresh) "fresh" else "EXPIRED — rescan required"}", style = MaterialTheme.typography.bodySmall)
                                Text(setup.rationale, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { viewModel.toggleAutoScanCandidateSelection(setup.symbol) },
                                        enabled = fresh && !state.isLoading,
                                        modifier = Modifier.weight(1f),
                                    ) { Text(if (selected) "Selected" else "Select for confirmation") }
                                    TextButton(onClick = { viewModel.dismissAutoScanCandidate(setup.symbol) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (state.environment == DirectEnvironment.LIVE) autoConfirmLiveConfirmation = true
                            else viewModel.executeAutoScanSelected()
                        },
                        enabled = state.autoScanConfirmSelectedSymbols.isNotEmpty() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Confirm and submit ${state.autoScanConfirmSelectedSymbols.size} selected order(s)", maxLines = 1, softWrap = false) }
                }
            }
            }
            state.orderStatus?.let {
                Text(it, color = if (it.startsWith("Order submitted") || it.startsWith("Paper order submitted")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            copyNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Developer diagnostics", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { showDeveloperTools = !showDeveloperTools }) { Text(if (showDeveloperTools) "Hide" else "Open") }
            }
            if (showDeveloperTools) {
            TerminalPanel(
                title = "Live terminal",
                lines = state.terminal,
                emptyMessage = "Connection, scan, guard, order, and refresh events will appear here.",
                copyLabel = "Copy live terminal",
                onCopy = { copyTerminal("Live terminal", state.terminal) },
            )
            TerminalPanel(
                title = "Debugger terminal (no credentials logged)",
                lines = state.debuggerTerminal,
                emptyMessage = "Run a scan to capture request ranges, response metadata, retries, per-symbol parser counts, setup checks, timing, and HTTP failures.",
                copyLabel = "Copy debugger terminal",
                onCopy = { copyTerminal("Debugger terminal", state.debuggerTerminal) },
            )
            }
            Button(onClick = viewModel::refresh, enabled = !state.isLoading && !state.isScanning, modifier = Modifier.fillMaxWidth()) { Text("Refresh account") }
            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect", maxLines = 1, softWrap = false) }
            Text("Scans OHLCV, trend, volume, liquidity, and risk/reward. Review every setup before execution.", style = MaterialTheme.typography.bodySmall)
            }
            }
            } else if (selectedTab == 1) {
                ManageTradesAndOrdersTab(state, viewModel)
            } else {
                TradeJournalScreen(state, viewModel) { selectedTab = 0 }
            }
                }
            }
            WorkspaceBottomNavigation(
                selectedTab = selectedTab,
                tradeCount = state.positions.size,
                journalErrorCount = if (state.error != null) 1 else 0,
                onSelect = { selectedTab = it },
            )
        }
    }
    if (liveConfirmation) {
        val selectedSetups = state.snapshots.filter { it.symbol in state.selectedTradeSymbols }
        AlertDialog(
            onDismissRequest = { liveConfirmation = false },
            title = { Text("Live execution unavailable") },
            text = { Text("LIVE execution is disabled in this build. No order will be sent to a live endpoint. Selected setups: ${selectedSetups.joinToString { "${it.symbol} ${it.direction?.label ?: "NO SIDE"} · score ${it.score}/100 · stop ${it.scannerStopPrice?.money() ?: "missing"} · target ${it.scannerTargetPrice?.money() ?: "missing"}" }}. Paper orders use a ${"%.2f".format(java.util.Locale.US, state.perTradeRiskBudgetPct * 100.0)}%-of-equity stop-risk budget, capped at 1%; each next order is revalidated against fresh account/stop risk and exposure.") },
            dismissButton = { TextButton(onClick = { liveConfirmation = false }) { Text("Cancel") } },
            confirmButton = { Button(onClick = { liveConfirmation = false }) { Text("Close") } },
        )
    }
    if (autoConfirmLiveConfirmation) {
        val selectedAutoCandidates = state.autoScanConfirmQueue.filter { it.setup.symbol in state.autoScanConfirmSelectedSymbols }
        AlertDialog(
            onDismissRequest = { autoConfirmLiveConfirmation = false },
            title = { Text("Live execution unavailable") },
            text = {
                Text(
                    "LIVE execution is disabled in this build. No order will be sent to a live endpoint. Selected setups: ${selectedAutoCandidates.joinToString { "${it.setup.symbol} ${it.setup.direction?.label ?: "NO CLEAR SIDE"} · score ${it.setup.score}/100 · stop ${it.setup.scannerStopPrice?.money() ?: "missing"} · target ${it.setup.scannerTargetPrice?.money() ?: "missing"}" }}. Paper orders use a ${"%.2f".format(java.util.Locale.US, state.perTradeRiskBudgetPct * 100.0)}%-of-equity stop-risk budget, capped at 1%; each order is revalidated against fresh broker state, stops and exposure.",
                )
            },
            dismissButton = { TextButton(onClick = { autoConfirmLiveConfirmation = false }) { Text("Cancel") } },
            confirmButton = { Button(onClick = { autoConfirmLiveConfirmation = false }) { Text("Close") } },
        )
    }
}

private sealed interface ManagementAction {
    data class CancelOrder(val orderId: String, val symbol: String) : ManagementAction
    data class ClosePosition(val symbol: String) : ManagementAction
    data class CloseAllPositions(val symbols: List<String>) : ManagementAction
}

@Composable
private fun ManageTradesAndOrdersTab(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    var pendingAction by remember { mutableStateOf<ManagementAction?>(null) }
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    val isLive = state.environment == DirectEnvironment.LIVE
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard("Open trades", state.positions.size.toString(), Icons.Outlined.SwapHoriz, Modifier.weight(1f))
        MetricCard("Pending orders", state.pendingOrders.size.toString(), Icons.Outlined.PendingActions, Modifier.weight(1f))
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Manage trades & orders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${state.positions.size} open position(s) · ${state.pendingOrders.size} pending order(s)")
            Text(
                if (isLive) "Live account: closing a position or cancelling an order requires a final confirmation."
                else "Paper account: account actions are sent only after a final confirmation.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = viewModel::refresh,
                enabled = !state.isLoading && !state.isScanning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isLoading) "Refreshing account..." else "Refresh trades & orders") }
        }
    }

    TabRow(selectedTabIndex = selectedSection) {
        listOf("Open", "Pending", "Closed").forEachIndexed { index, label ->
            Tab(selected = selectedSection == index, onClick = { selectedSection = index }, text = { Text(label, maxLines = 1, softWrap = false) })
        }
    }
    Box(
        modifier = Modifier.weight(1f).fillMaxWidth().border(BorderStroke(1.dp, LiquidGold.copy(alpha = 0.35f)), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(8.dp),
    ) {
        if (selectedSection == 2) {
            ClosedTradesSection(state.closedTrades.filterNot { it.symbol.equals("NDAQ", ignoreCase = true) })
        } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (selectedSection) {
                0 -> "Open trades · ${state.positions.size}"
                1 -> "Pending orders · ${state.pendingOrders.size}"
                else -> "Closed trades · ${state.closedTrades.count { !it.symbol.equals("NDAQ", ignoreCase = true) }}"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (selectedSection == 0) {
    Text("Open trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (state.positions.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                val symbols = state.positions.map { value(it, "symbol") }.filter { it != "Not available" }.distinct()
                pendingAction = ManagementAction.CloseAllPositions(symbols)
            },
            enabled = !state.isLoading && !state.isScanning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Close all ${state.positions.size} open position(s)", maxLines = 1, softWrap = false) }
            Text("Attached bracket stop/target legs and working same-symbol exit orders are cleared first to release reserved position quantity. Unrelated pending orders are left unchanged; same-symbol entry orders still block the close.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.positions.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text("No open positions in the latest broker snapshot.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        state.positions.forEach { position ->
            val symbol = value(position, "symbol")
            val pnl = number(position, "unrealized_pl")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$symbol · ${positionSide(position)}", fontWeight = FontWeight.Bold)
                    Text("Qty: ${value(position, "qty")} · Value: USD ${moneyValue(position, "market_value")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Entry: USD ${moneyValue(position, "avg_entry_price")} · Current: USD ${moneyValue(position, "current_price")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        "Unrealized P/L: USD ${signedNumber(position, "unrealized_pl")} (${signedPercent(position, "unrealized_plpc")})",
                        color = when {
                            pnl == null -> MaterialTheme.colorScheme.onSurface
                            pnl >= 0.0 -> ProfitGreen
                            else -> MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = { pendingAction = ManagementAction.ClosePosition(symbol) },
                        enabled = symbol != "Not available" && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Close position", maxLines = 1, softWrap = false) }
                }
            }
        }
    }

    } else if (selectedSection == 1) {
    Text("Pending orders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (state.pendingOrders.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text("No pending broker orders in the latest snapshot.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        state.pendingOrders.forEach { order ->
            val id = value(order, "id")
            val symbol = value(order, "symbol")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$symbol · ${value(order, "side")} ${value(order, "type")}", fontWeight = FontWeight.Bold)
                    Text("Qty: ${value(order, "qty")} · Filled: ${value(order, "filled_qty")} · Status: ${value(order, "status")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Limit USD ${orderPrice(order, "limit_price")} · Stop USD ${orderPrice(order, "stop_price")} · Target USD ${orderPrice(order, "take_profit")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Time in force: ${value(order, "time_in_force")}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { pendingAction = ManagementAction.CancelOrder(id, symbol) },
                        enabled = id != "Not available" && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel order", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
    } else {
        Text("Historical NDAQ rows are hidden here at your request; your broker account history is unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ClosedTradesSection(state.closedTrades.filterNot { it.symbol.equals("NDAQ", ignoreCase = true) })
    }
    state.orderStatus?.let { status ->
        Text(
            status,
            color = if (status.contains("failed", ignoreCase = true) || status.contains("rejected", ignoreCase = true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
        }
    }
        }
    }

    pendingAction?.let { action ->
        val actionLabel = when (action) {
            is ManagementAction.CancelOrder -> "Cancel ${action.symbol} order"
            is ManagementAction.ClosePosition -> "Close ${action.symbol} position"
            is ManagementAction.CloseAllPositions -> "Close all ${action.symbols.size} positions"
        }
        val explanation = when (action) {
            is ManagementAction.CancelOrder -> "This sends a cancellation request for the selected broker order. It will not close an existing position."
            is ManagementAction.ClosePosition -> "This cancels attached protective legs and working same-symbol exit orders that can reserve the position quantity, then submits the broker close request. Same-symbol entry orders still block the close; unrelated pending orders remain. The fill price may differ from the displayed price."
            is ManagementAction.CloseAllPositions -> "This cancels attached protective legs and working same-symbol exit orders that can reserve quantity, then submits separate broker market-close requests for: ${action.symbols.joinToString()}. Same-symbol entry orders block their closes; unrelated pending orders are left unchanged (${state.pendingOrders.size} currently shown). Actual exit prices are stored only after Alpaca confirms the filled entry and completed exit quantity in its order/fill records."
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Confirm: $actionLabel") },
            text = {
                Text(
                    "$explanation ${if (isLive) "This is a LIVE account and may affect real money." else "This is a PAPER account."}",
                )
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Keep unchanged") } },
            confirmButton = {
                Button(onClick = {
                    when (action) {
                        is ManagementAction.CancelOrder -> viewModel.cancelPendingOrder(action.orderId, confirmed = true)
                        is ManagementAction.ClosePosition -> viewModel.closeOpenPosition(action.symbol, confirmed = true)
                        is ManagementAction.CloseAllPositions -> viewModel.closeAllOpenPositions(confirmed = true)
                    }
                    pendingAction = null
                }) { Text(actionLabel) }
            },
        )
    }
    }
}

@Composable
private fun ClosedTradesSection(trades: List<ClosedTrade>) {
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    val filters = listOf("All", "Profit", "Loss", "Flat")
    val filtered = when (selectedFilter) {
        1 -> trades.filter { it.pnl > 0.005 }
        2 -> trades.filter { it.pnl < -0.005 }
        3 -> trades.filter { kotlin.math.abs(it.pnl) <= 0.005 }
        else -> trades
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Closed trades · ${filtered.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TabRow(selectedTabIndex = selectedFilter) {
            filters.forEachIndexed { index, label ->
                Tab(selected = selectedFilter == index, onClick = { selectedFilter = index }, text = { Text(label, maxLines = 1, softWrap = false) })
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(if (trades.isEmpty()) "No completed round trips in the latest fill history." else "No closed trades match this filter.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                filtered.forEach { trade ->
                    val resultColor = when {
                        trade.pnl > 0.005 -> ProfitGreen
                        trade.pnl < -0.005 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("${trade.symbol} · ${trade.side}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("USD ${trade.pnl.signedMoney()}", color = resultColor, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                            }
                            Text("Qty ${trade.quantity.cleanNumber()} · Entry USD ${trade.entryPrice.money()} · Exit USD ${trade.exitPrice.money()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("Alpaca filled order: ${trade.exitOrderId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text(trade.closedAt.replace("T", " ").removeSuffix("Z"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun Double.money(): String = "%.2f".format(java.util.Locale.US, this)
private fun Double.signedMoney(): String = "%+.2f".format(java.util.Locale.US, this)
private fun Double.cleanNumber(): String = "%.4f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')

@Composable
private fun JournalTab(state: DirectTradingState, viewModel: DirectTradingViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Automatic trade journal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("This journal records connection, scan, order, refresh, cancellation, position-close, and error events automatically. It is kept for the current session and cleared on disconnect.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Journal history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().border(BorderStroke(1.dp, LiquidGold.copy(alpha = 0.35f)), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(8.dp),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.journalEntries.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("No automatic events yet.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    state.journalEntries.forEach { entry -> JournalEntryCard(entry) }
                }
            }
        }
    }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${entry.symbol} · ${entry.setup}", fontWeight = FontWeight.Bold)
            Text(entry.createdAt.replace("T", " ").removeSuffix("Z"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TerminalPanel(
    title: String,
    lines: List<String>,
    emptyMessage: String,
    copyLabel: String,
    onCopy: () -> Unit,
) {
    val visibleLines = lines.takeLast(100)
    val terminalScrollState = rememberScrollState()
    LaunchedEffect(visibleLines.lastOrNull()) {
        terminalScrollState.scrollTo(terminalScrollState.maxValue)
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (lines.isEmpty()) "No output yet." else "Showing latest ${visibleLines.size} of ${lines.size}; copy exports the full retained log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCopy, enabled = lines.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(copyLabel)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(10.dp)
                        .height(140.dp)
                        .verticalScroll(terminalScrollState),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (visibleLines.isEmpty()) {
                        Text(emptyMessage, style = MaterialTheme.typography.bodySmall)
                    } else {
                        visibleLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonValueRow(label: String, objectValue: JsonObject?, key: String) {
    Text("$label: ${value(objectValue, key)}")
}

private fun value(objectValue: JsonObject?, key: String): String =
    (objectValue?.get(key) as? JsonPrimitive)?.contentOrNull ?: "Not available"

private fun moneyValue(objectValue: JsonObject?, key: String): String =
    number(objectValue, key)?.let { "%.2f".format(java.util.Locale.US, it) } ?: value(objectValue, key)

private fun orderPrice(order: JsonObject, key: String): String {
    val direct = (order[key] as? JsonPrimitive)?.contentOrNull
    if (!direct.isNullOrBlank() && direct != "null") return direct.toDoubleOrNull()?.let { "%.2f".format(java.util.Locale.US, it) } ?: direct
    val legs = (order["legs"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val leg = legs.firstOrNull { child ->
        when (key) {
            "stop_price" -> (child["stop_price"] as? JsonPrimitive)?.contentOrNull != null || (child["type"] as? JsonPrimitive)?.contentOrNull == "stop"
            "take_profit" -> (child["limit_price"] as? JsonPrimitive)?.contentOrNull != null || (child["type"] as? JsonPrimitive)?.contentOrNull == "limit"
            else -> (child["limit_price"] as? JsonPrimitive)?.contentOrNull != null
        }
    }
    return when (key) {
        "stop_price" -> (leg?.get("stop_price") as? JsonPrimitive)?.contentOrNull ?: "Not available"
        "take_profit" -> (leg?.get("limit_price") as? JsonPrimitive)?.contentOrNull ?: "Not available"
        else -> (leg?.get(key) as? JsonPrimitive)?.contentOrNull ?: "Not available"
    }
}

private fun number(objectValue: JsonObject?, key: String): Double? =
    (objectValue?.get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

private fun signedNumber(objectValue: JsonObject?, key: String): String =
    number(objectValue, key)?.let { "%+.2f".format(java.util.Locale.US, it) } ?: "Not available"

private fun signedPercent(objectValue: JsonObject?, key: String): String =
    number(objectValue, key)?.let { "%+.2f%%".format(java.util.Locale.US, it * 100.0) } ?: "Not available"

private fun positionSide(position: JsonObject): String =
    number(position, "qty")?.let { if (it < 0.0) "SHORT" else "LONG" } ?: "POSITION"

private fun dailyPnl(objectValue: JsonObject?): String {
    val equity = number(objectValue, "equity")
    val lastEquity = number(objectValue, "last_equity")
    return if (equity != null && lastEquity != null) "%+.2f".format(java.util.Locale.US, equity - lastEquity) else "Not available"
}
