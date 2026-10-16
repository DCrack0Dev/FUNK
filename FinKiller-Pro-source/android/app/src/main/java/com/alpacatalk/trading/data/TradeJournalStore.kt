package com.alpacatalk.trading.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Durable journal scoped to one broker mode and one broker account. */
class TradeJournalStore(context: Context, accountMode: String = "paper", accountKey: String = "unbound") {
    private val mode = accountMode.lowercase(Locale.US).let { if (it.contains("live")) "live" else "paper" }
    private val scope = accountKey.takeIf { it.isNotBlank() && it != "unbound" }?.let(::scopeHash)
    private val journalFile = scope?.let { File(context.filesDir, "fin_killer_trade_journal_${mode}_$it.json") }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var lastError: String? = null
        private set

    val path: String get() = journalFile?.absolutePath ?: "unbound account journal"

    fun load(): JournalState {
        if (journalFile?.exists() == true) return decode(journalFile!!)
        // Never expose an account's journal before an authenticated account identity is known.
        return JournalState(version = CURRENT_VERSION)
    }

    fun save(state: JournalState): JournalState {
        return runCatching {
            val modeScoped = state.copy(version = CURRENT_VERSION)
            val target = journalFile ?: return@runCatching state
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(json.encodeToString(JournalState.serializer(), modeScoped))
            if (target.exists() && !target.delete()) throw IllegalStateException("previous journal file could not be replaced")
            if (!temporary.renameTo(target)) throw IllegalStateException("journal file could not be renamed into place")
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
        const val CURRENT_VERSION = 2

        fun scopeHash(accountKey: String): String = MessageDigest.getInstance("SHA-256")
            .digest(accountKey.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
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
