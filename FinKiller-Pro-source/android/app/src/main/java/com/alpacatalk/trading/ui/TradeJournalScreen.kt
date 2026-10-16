package com.alpacatalk.trading.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alpacatalk.trading.DirectTradingState
import com.alpacatalk.trading.DirectTradingViewModel
import com.alpacatalk.trading.MainActivity
import com.alpacatalk.trading.data.ClosedTrade
import com.alpacatalk.trading.data.JournalSnapshot
import com.alpacatalk.trading.data.ManualJournalEntry
import com.alpacatalk.trading.data.OpenTradeTicket
import com.alpacatalk.trading.data.SetupMemoryRecord
import com.alpacatalk.trading.data.TradeDirective
import com.alpacatalk.trading.data.TradeJournalEngine
import com.alpacatalk.trading.data.TradeMath
import com.alpacatalk.trading.data.TradeOutcome
import com.alpacatalk.trading.data.TradeRatingPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SETUP_LABELS = listOf(
    "SCALP / MOMENTUM BREAKOUT OR LIQUIDITY SWEEP",
    "SWING / LIQUIDITY-TO-LIQUIDITY SETUP",
    "SCALP + SWING / ALIGNED LONG SETUP",
    "DEVELOPING SETUP",
)

private data class ManualForm(
    val symbol: String = "",
    val short: Boolean = false,
    val setupLabel: String = SETUP_LABELS.first(),
    val entry: String = "",
    val stop: String = "",
    val target: String = "",
    val exit: String = "",
    val quantity: String = "1",
    val signalScore: String = "70",
    val appRating: String = "",
    val openedHoursAgo: String = "1",
    val closedHoursAgo: String = "0",
    val manualOutcome: Int = 0,
    val notes: String = "",
)

/**
 * The journal and memory screen. It shows what the app has stored, what it has learned from each
 * result, and exactly how it will treat every setup the next time it appears.
 */
@Composable
fun TradeJournalScreen(state: DirectTradingState, viewModel: DirectTradingViewModel, onClose: () -> Unit) {
    val journal = state.journal
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showForm by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(ManualForm()) }
    val exitInputs = remember { mutableStateMapOf<String, String>() }
    val ratingsByTrade = remember(journal.ratingPoints) { journal.ratingPoints.associateBy { it.tradeId } }
    fun launchExport(mimeType: String, filename: String, bytes: ByteArray) {
        val activity = context as? MainActivity
        if (activity == null) {
            Toast.makeText(context, "Journal export is unavailable outside the main activity.", Toast.LENGTH_LONG).show()
        } else {
            runCatching { activity.launchJournalExport(mimeType, filename, bytes) }
                .onFailure { error -> Toast.makeText(context, "Could not open the save dialog: ${error.message ?: "try again"}", Toast.LENGTH_LONG).show() }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Trade journal & setup memory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text("Every closed trade is stored with its setup, the rating the app gave it, its stop distance in pips, its target, and the realised result. The app then re-rates the setup and treats it accordingly next time: repeated winners are preferred, repeated losers are blocked.", style = MaterialTheme.typography.bodySmall)
                    Text("Alpaca-linked trades are journalled automatically only after a filled entry and the completed exit quantity are verified from Alpaca's order and fill records. The stored close price is the quantity-weighted actual fill price—not the last quote or a submitted close request. Partial exits remain open until the full filled quantity is confirmed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back to the trading workspace") }
            state.journalNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            JournalStatsCard(journal)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.syncClosedTrades() },
                    enabled = state.environment != null && !state.journalBusy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.journalBusy) "Syncing…" else "Sync broker trades") }
                OutlinedButton(onClick = { viewModel.refreshJournal() }, modifier = Modifier.weight(1f)) { Text("Reload from disk") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        launchExport("text/csv", "fin-killer-trade-journal.csv", viewModel.journalCsv().toByteArray(Charsets.UTF_8))
                    },
                    enabled = journal.trades.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save CSV") }
                OutlinedButton(
                    onClick = {
                        launchExport("application/pdf", "fin-killer-trade-journal.pdf", JournalPdfExporter.render(journal))
                    },
                    enabled = journal.trades.isNotEmpty() || journal.openTickets.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save PDF") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(viewModel.journalCsv()))
                    },
                    enabled = journal.trades.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Copy journal as CSV") }
                OutlinedButton(onClick = { showForm = !showForm }, modifier = Modifier.weight(1f)) {
                    Text(if (showForm) "Hide manual entry" else "Record a closed trade")
                }
            }
            Text("Sync reads closed brackets and completed Alpaca fill history, including trades from before this journal was installed. If a historical setup rating or stop/target level is unavailable, it is marked unknown or neutral rather than invented.", style = MaterialTheme.typography.bodySmall)

            if (showForm) {
                ManualEntryCard(
                    state = state,
                    form = form,
                    onFormChange = { form = it },
                    onReset = { form = ManualForm() },
                    onSubmit = {
                        val entry = form.toJournalEntry(state)
                        if (entry != null) {
                            viewModel.recordManualTrade(entry)
                            form = ManualForm()
                            showForm = false
                        }
                    },
                )
            }

            OpenTicketsCard(
                tickets = journal.openTickets,
                exitInputs = exitInputs,
                onClose = { ticketId, exitPrice, outcome -> viewModel.closeOpenTicket(ticketId, exitPrice, outcome) },
                onDiscard = { ticketId -> viewModel.discardOpenTicket(ticketId) },
            )

            SetupMemoryCard(memories = journal.memories.filter { it.trades > 0 }, onForget = { key -> viewModel.forgetSetup(key) })

            ClosedTradesCard(
                trades = journal.trades,
                ratingsByTrade = ratingsByTrade,
                onDelete = { tradeId -> viewModel.deleteJournalTrade(tradeId) },
            )

            Text("Ratings are replayed from the stored trades every time the journal loads, so the memory can never drift from the record and deleting a trade honestly rewinds the learning.", style = MaterialTheme.typography.bodySmall)
            if (state.journalEntries.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Recent session events", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Connection, scan, order, refresh, cancellation, and error events from this session.", style = MaterialTheme.typography.bodySmall)
                        state.journalEntries.take(20).forEach { entry ->
                            Text("${entry.createdAt.replace("T", " ").removeSuffix("Z")} · ${entry.symbol} · ${entry.setup}: ${entry.notes}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalStatsCard(journal: JournalSnapshot) {
    val stats = journal.stats
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Record", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Closed trades: ${stats.totalTrades} · Wins: ${stats.wins} · Losses: ${stats.losses} · Breakeven: ${stats.breakevens}")
            Text("Win rate: ${stats.winRatePct}% · Net: ${TradeMath.formatPips(stats.netPips)} pips · Expectancy: ${TradeMath.formatPips(stats.expectancyPips)} pips per trade")
            Text("Average win: ${stats.averageWinPips} pips · Average loss: ${stats.averageLossPips} pips · Profit factor: ${stats.profitFactor}")
            Text("High-rated losses remembered: ${stats.highRatedLosses}", fontWeight = FontWeight.Bold)
            Text("Setups preferred: ${stats.preferredSetups} · cautioned or on probation: ${stats.cautionedSetups} · blocked: ${stats.blockedSetups}")
            stats.bestSetupKey?.let { Text("Best remembered setup: ${TradeJournalEngine.displayKey(it)}", style = MaterialTheme.typography.bodySmall) }
            stats.worstSetupKey?.let { Text("Worst remembered setup: ${TradeJournalEngine.displayKey(it)}", style = MaterialTheme.typography.bodySmall) }
            stats.bestSymbol?.let { Text("Strongest market: $it · Weakest market: ${stats.worstSymbol ?: "none"}", style = MaterialTheme.typography.bodySmall) }
            if (journal.openTickets.isNotEmpty()) Text("Open tickets waiting for a result: ${journal.openTickets.size}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OpenTicketsCard(
    tickets: List<OpenTradeTicket>,
    exitInputs: MutableMap<String, String>,
    onClose: (String, Double, TradeOutcome?) -> Unit,
    onDiscard: (String) -> Unit,
) {
    if (tickets.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open tickets awaiting a result", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            tickets.forEach { ticket ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${ticket.symbol} ${TradeMath.sideLabel(ticket.side)} · ${TradeJournalEngine.displayKey(ticket.memoryKey)}", fontWeight = FontWeight.Bold)
                    Text("Entry ${TradeMath.formatPrice(ticket.entryPrice)} · stop ${ticket.stopPips} ${TradeMath.pipLabel(ticket.symbol)} · target ${ticket.targetPips} ${TradeMath.pipLabel(ticket.symbol)} · R:R ${ticket.riskReward}", style = MaterialTheme.typography.bodySmall)
                    Text("App rating at entry: ${ticket.appRatingAtEntry}/100 · directive then: ${ticket.directiveAtEntry.name}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = exitInputs[ticket.ticketId] ?: "",
                        onValueChange = { exitInputs[ticket.ticketId] = it },
                        label = { Text("Exit price you saw at the broker") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val exit = exitInputs[ticket.ticketId]?.toDoubleOrNull()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { if (exit != null) onClose(ticket.ticketId, exit, null) },
                            enabled = exit != null && exit > 0.0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Close and grade") }
                        OutlinedButton(onClick = { onDiscard(ticket.ticketId) }, modifier = Modifier.weight(1f)) { Text("Discard") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupMemoryCard(memories: List<SetupMemoryRecord>, onForget: (String) -> Unit) {
    if (memories.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What the app remembers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("A setup rated high that still lost is penalised at full weight, which lowers the rating fastest and forces caution on the next identical signal.", style = MaterialTheme.typography.bodySmall)
            memories.forEach { memory ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = directiveContainer(memory.directive)),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${TradeJournalEngine.displayKey(memory.key)} · ${memory.rating}/100 · ${memory.directive.label}", fontWeight = FontWeight.Bold)
                        Text("Record ${memory.wins}W/${memory.losses}L/${memory.breakevens}BE · win rate ${memory.winRatePct}% · expectancy ${TradeMath.formatPips(memory.expectancyPips)} pips · net ${TradeMath.formatPips(memory.netPips)} pips", style = MaterialTheme.typography.bodySmall)
                        if (memory.highRatedLosses > 0) {
                            Text("High-rated losses: ${memory.highRatedLosses} — the app was confident and still lost here.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Text("Signal score is adjusted by ${if (memory.scoreAdjustment > 0) "+" else ""}${memory.scoreAdjustment} for this setup.", style = MaterialTheme.typography.bodySmall)
                        Text(memory.reason, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onForget(memory.key) }, modifier = Modifier.fillMaxWidth()) { Text("Forget this setup") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClosedTradesCard(
    trades: List<ClosedTrade>,
    ratingsByTrade: Map<String, TradeRatingPoint>,
    onDelete: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stored closed trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (trades.isEmpty()) {
                Text("No closed trades yet. Sync broker trades or record one by hand, and the memory starts learning immediately.", style = MaterialTheme.typography.bodySmall)
            }
            trades.forEach { trade ->
                val rating = ratingsByTrade[trade.tradeId]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${trade.symbol} ${TradeMath.sideLabel(trade.side)} · ${trade.outcome.name} · ${TradeMath.formatPips(trade.resultPips)} ${TradeMath.pipLabel(trade.symbol)}", fontWeight = FontWeight.Bold)
                        Text("${timestamp(trade.closedAtEpochMs)} · ${trade.accountMode} · ${trade.setupLabel}", style = MaterialTheme.typography.bodySmall)
                        Text("Entry ${TradeMath.formatPrice(trade.entryPrice)} · stop ${TradeMath.formatPrice(trade.stopPrice)} (${trade.stopPips} ${TradeMath.pipLabel(trade.symbol)}) · target ${TradeMath.formatPrice(trade.targetPrice)} (${trade.targetPips} ${TradeMath.pipLabel(trade.symbol)}) · exit ${TradeMath.formatPrice(trade.exitPrice)}", style = MaterialTheme.typography.bodySmall)
                        Text("R:R ${trade.riskReward} · size ${trade.quantity} · P/L ${TradeMath.formatPnl(trade.realizedPnl)} · source ${trade.source.name}", style = MaterialTheme.typography.bodySmall)
                        Text("App rating at entry: ${trade.appRatingAtEntry}/100 · signal ${trade.signalScoreAtEntry}/100 · directive then ${trade.directiveAtEntry.name}", style = MaterialTheme.typography.bodySmall)
                        rating?.let {
                            Text("Setup rating ${it.ratingBefore}/100 → ${it.ratingAfter}/100 (${if (it.delta > 0) "+" else ""}${it.delta}); now ${it.directiveAfter.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Text(trade.lesson, style = MaterialTheme.typography.bodySmall)
                        if (trade.notes.isNotBlank()) Text(trade.notes, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onDelete(trade.tradeId) }) { Text("Delete this trade and rewind the memory") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualEntryCard(
    state: DirectTradingState,
    form: ManualForm,
    onFormChange: (ManualForm) -> Unit,
    onReset: () -> Unit,
    onSubmit: () -> Unit,
) {
    val symbol = form.symbol.trim().uppercase(Locale.US)
    val entry = form.entry.toDoubleOrNull()
    val exit = form.exit.toDoubleOrNull()
    val stop = form.stop.toDoubleOrNull()
    val target = form.target.toDoubleOrNull()
    val previewValid = symbol.isNotBlank() && entry != null && exit != null && entry > 0.0 && exit > 0.0
    val previewOutcome = if (previewValid) {
        form.manualOutcome.toOutcome()
            ?: TradeMath.resolveOutcome(symbol, if (form.short) "sell" else "buy", entry!!, stop ?: 0.0, target ?: 0.0, exit!!)
    } else {
        null
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Record a closed trade", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Use this for trades taken outside the app or already closed. The rating you enter is the rating the app had given the setup; a high rating that lost will be penalised at full weight.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = form.symbol, onValueChange = { onFormChange(form.copy(symbol = it)) }, label = { Text("Symbol (e.g. AAPL, EUR/USD, XAU/USD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onFormChange(form.copy(short = false)) }, modifier = Modifier.weight(1f)) { Text(if (form.short) "Long" else "✓ Long") }
                OutlinedButton(onClick = { onFormChange(form.copy(short = true)) }, modifier = Modifier.weight(1f)) { Text(if (form.short) "✓ Short" else "Short") }
            }
            Text("Setup family", fontWeight = FontWeight.Bold)
            SETUP_LABELS.forEach { label ->
                OutlinedButton(onClick = { onFormChange(form.copy(setupLabel = label)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (form.setupLabel == label) "✓ $label" else label)
                }
            }
            OutlinedTextField(value = form.entry, onValueChange = { onFormChange(form.copy(entry = it)) }, label = { Text("Entry price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.stop, onValueChange = { onFormChange(form.copy(stop = it)) }, label = { Text("Stop-loss price (0 if none)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.target, onValueChange = { onFormChange(form.copy(target = it)) }, label = { Text("Target price (0 if none)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.exit, onValueChange = { onFormChange(form.copy(exit = it)) }, label = { Text("Exit price actually filled") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.quantity, onValueChange = { onFormChange(form.copy(quantity = it)) }, label = { Text("Quantity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.signalScore, onValueChange = { onFormChange(form.copy(signalScore = it)) }, label = { Text("Signal score at entry (0–100)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.appRating, onValueChange = { onFormChange(form.copy(appRating = it)) }, label = { Text("App rating at entry (blank = derive from signal and memory)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Result", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onFormChange(form.copy(manualOutcome = 0)) }, modifier = Modifier.weight(1f)) { Text(if (form.manualOutcome == 0) "✓ Auto" else "Auto") }
                OutlinedButton(onClick = { onFormChange(form.copy(manualOutcome = 1)) }, modifier = Modifier.weight(1f)) { Text(if (form.manualOutcome == 1) "✓ Win" else "Win") }
                OutlinedButton(onClick = { onFormChange(form.copy(manualOutcome = 2)) }, modifier = Modifier.weight(1f)) { Text(if (form.manualOutcome == 2) "✓ Loss" else "Loss") }
                OutlinedButton(onClick = { onFormChange(form.copy(manualOutcome = 3)) }, modifier = Modifier.weight(1f)) { Text(if (form.manualOutcome == 3) "✓ BE" else "BE") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = form.openedHoursAgo, onValueChange = { onFormChange(form.copy(openedHoursAgo = it)) }, label = { Text("Opened (hours ago)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = form.closedHoursAgo, onValueChange = { onFormChange(form.copy(closedHoursAgo = it)) }, label = { Text("Closed (hours ago)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = form.notes, onValueChange = { onFormChange(form.copy(notes = it)) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            if (previewValid) {
                val stopPips = stop?.let { if (it > 0.0) TradeMath.stopPips(symbol, entry!!, it) else 0.0 } ?: 0.0
                val targetPips = target?.let { if (it > 0.0) TradeMath.targetPips(symbol, entry!!, it) else 0.0 } ?: 0.0
                val resultPips = TradeMath.resultPips(symbol, if (form.short) "sell" else "buy", entry!!, exit!!)
                Text(
                    "Preview: stop $stopPips ${TradeMath.pipLabel(symbol)} · target $targetPips ${TradeMath.pipLabel(symbol)} · result ${TradeMath.formatPips(resultPips)} ${TradeMath.pipLabel(symbol)} · graded ${previewOutcome?.name ?: "UNKNOWN"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                if (stop != null && target != null && stop > 0.0 && target > 0.0) {
                    Text("Risk/reward at entry: ${TradeMath.riskReward(entry!!, stop, target)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSubmit, enabled = previewValid, modifier = Modifier.weight(1f)) { Text("Store and re-rate the setup") }
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Clear form") }
            }
            Text("Stored in this app only; no broker credentials are written to the journal.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun ManualForm.toJournalEntry(state: DirectTradingState): ManualJournalEntry? {
    val symbol = symbol.trim().uppercase(Locale.US)
    val entryPrice = entry.toDoubleOrNull() ?: return null
    val exitPrice = exit.toDoubleOrNull() ?: return null
    if (symbol.isBlank() || entryPrice <= 0.0 || exitPrice <= 0.0) return null
    val closedHours = closedHoursAgo.toDoubleOrNull() ?: 0.0
    val openedHours = (openedHoursAgo.toDoubleOrNull() ?: 1.0).coerceAtLeast(closedHours)
    val now = System.currentTimeMillis()
    val closedAt = now - (closedHours * 3_600_000.0).toLong()
    val openedAt = minOf(now - (openedHours * 3_600_000.0).toLong(), closedAt - 1_000L)
    return ManualJournalEntry(
        symbol = symbol,
        side = if (short) "sell" else "buy",
        accountMode = state.environment?.name ?: "MANUAL",
        setupLabel = setupLabel,
        entryPrice = entryPrice,
        stopPrice = stop.toDoubleOrNull() ?: 0.0,
        targetPrice = target.toDoubleOrNull() ?: 0.0,
        exitPrice = exitPrice,
        quantity = quantity.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0,
        signalScore = signalScore.toIntOrNull()?.coerceIn(0, 100) ?: 0,
        appRating = appRating.toIntOrNull()?.coerceIn(0, 100),
        directiveAtEntry = null,
        openedAtEpochMs = openedAt,
        closedAtEpochMs = closedAt,
        outcomeOverride = manualOutcome.toOutcome(),
        notes = notes,
    )
}

private fun Int.toOutcome(): TradeOutcome? = when (this) {
    1 -> TradeOutcome.WIN
    2 -> TradeOutcome.LOSS
    3 -> TradeOutcome.BREAKEVEN
    else -> null
}

private fun timestamp(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@Composable
private fun directiveContainer(directive: TradeDirective): Color = when (directive) {
    TradeDirective.BLOCK -> MaterialTheme.colorScheme.errorContainer
    TradeDirective.PROBATION -> MaterialTheme.colorScheme.errorContainer
    TradeDirective.CAUTION -> MaterialTheme.colorScheme.secondaryContainer
    TradeDirective.ALLOW -> MaterialTheme.colorScheme.surfaceVariant
    TradeDirective.PREFER -> MaterialTheme.colorScheme.primaryContainer
}
