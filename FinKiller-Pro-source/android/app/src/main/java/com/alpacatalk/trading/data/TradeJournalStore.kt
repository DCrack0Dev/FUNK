package com.alpacatalk.trading.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** Durable, mode-scoped journal. The legacy unsuffixed journal is migrated into Paper only. */
class TradeJournalStore(context: Context, accountMode: String = "paper") {
    private val mode = accountMode.lowercase(Locale.US).let { if (it.contains("live")) "live" else "paper" }
    private val journalFile = File(context.filesDir, "fin_killer_trade_journal_$mode.json")
    private val legacyFile = File(context.filesDir, LEGACY_FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var lastError: String? = null
        private set

    val path: String get() = journalFile.absolutePath

    fun load(): JournalState {
        if (journalFile.exists()) return decode(journalFile)
        if (mode == "paper" && legacyFile.exists()) {
            val old = decode(legacyFile)
            val paperOnly = TradeJournalMigration.legacyToPaper(old)
            save(paperOnly)
            return paperOnly
        }
        return JournalState(version = CURRENT_VERSION)
    }

    fun save(state: JournalState): JournalState {
        return runCatching {
            val modeScoped = state.copy(version = CURRENT_VERSION)
            val temporary = File(journalFile.parentFile, "${journalFile.name}.tmp")
            temporary.writeText(json.encodeToString(JournalState.serializer(), modeScoped))
            if (journalFile.exists() && !journalFile.delete()) throw IllegalStateException("previous journal file could not be replaced")
            if (!temporary.renameTo(journalFile)) throw IllegalStateException("journal file could not be renamed into place")
            lastError = null
            modeScoped
        }.getOrElse { error ->
            lastError = "Journal could not be saved (${error.message ?: "unknown error"})."
            state
        }
    }

    fun snapshot(state: JournalState): JournalSnapshot =
        TradeJournalEngine.rebuild(state, excludeManuallyEdited = mode == "live")

    fun exportCsv(state: JournalState): String = TradeJournalEngine.csv(state.trades)

    fun clear(): JournalState = save(JournalState(version = CURRENT_VERSION))

    private fun decode(file: File): JournalState = runCatching {
        json.decodeFromString(JournalState.serializer(), file.readText())
    }.onFailure {
        lastError = "Journal could not be read (${it.message ?: "unknown error"}); starting a fresh $mode journal."
    }.getOrElse { JournalState(version = CURRENT_VERSION) }

    private companion object {
        const val LEGACY_FILE_NAME = "fin_killer_trade_journal.json"
        const val CURRENT_VERSION = 2
    }
}

/** Pure migration reducer shared by migration tests and storage behavior. */
internal object TradeJournalMigration {
    fun legacyToPaper(state: JournalState): JournalState = state.copy(
        version = 2,
        trades = state.trades.filter { it.accountMode.contains("paper", ignoreCase = true) }
            .map { trade -> trade.copy(manuallyEdited = trade.manuallyEdited || trade.source == TradeJournalSource.MANUAL) },
        openTickets = state.openTickets.filter { it.accountMode.contains("paper", ignoreCase = true) },
    )
}
