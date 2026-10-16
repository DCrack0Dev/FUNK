package com.alpacatalk.trading.data

import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The memory of the app.
 *
 * Every closed trade is stored, and the rating of a setup is then *replayed* from the stored trades in
 * chronological order. Replay means the rating is always a pure function of the journal: it cannot
 * drift, it survives restarts, and removing a trade honestly rewinds the memory instead of leaving
 * stale state behind.
 *
 * Rating rules (all deterministic):
 *  - A cold setup starts at the neutral 50/100.
 *  - A win adds `0.5 + 0.5 * rating/100` of weight: a win the app expected counts fully, a low-rated
 *    fluke win counts only half as much.
 *  - A loss adds the same conviction-scaled weight as a win: a result cannot drift downward merely
 *    because the old implementation penalised losses more heavily than it rewarded wins.
 *  - The rating is `100 * (weightedWins + 1) / (weightedWins + weightedLosses + 2)`, i.e. Laplace
 *    smoothed so a single result can never produce a 0 or 100 rating.
 *  - Losses on setups rated [HIGH_RATING_THRESHOLD] or better are additionally counted, and repeated
 *    high-rated failures escalate the directive to probation and then to a block.
 */
object TradeJournalEngine {
    const val NEUTRAL_RATING = 50
    const val HIGH_RATING_THRESHOLD = 65
    const val MIN_MEMORY_SAMPLE_FOR_BONUS = 20
    const val HIGH_RATED_LOSS_PROBATION = 2
    const val HIGH_RATED_LOSS_BLOCK = 3
    const val CONSECUTIVE_LOSS_CAUTION = 2
    const val CONSECUTIVE_LOSS_BLOCK = 3
    const val MULTI_MARKET_PREFIX = "*"

    private const val WIN_BASE_WEIGHT = 0.5
    private const val WIN_CONVICTION_WEIGHT = 0.5
    private const val LOSS_BASE_WEIGHT = 0.5
    private const val LOSS_CONVICTION_WEIGHT = 0.5
    private const val SMOOTHING_PRIOR = 1.0

    const val SCORE_ADJUSTMENT_PREFER = 6
    const val SCORE_ADJUSTMENT_CAUTION = -12
    const val SCORE_ADJUSTMENT_PROBATION = -25
    const val SCORE_ADJUSTMENT_BLOCK = -100

    /** Weight of the live signal conviction and of the remembered setup record in the app's own rating. */
    const val SIGNAL_RATING_WEIGHT = 0.7
    const val MEMORY_RATING_WEIGHT = 0.3

    /**
     * The rating the app gives the trade it is about to take: mostly the live signal score, blended
     * with what it remembers about this setup. This value is stored with the trade, so a high-rated
     * failure can later be recognised as such and punished at full weight.
     */
    fun appRatingForTrade(signalScore: Int, decision: TradeMemoryDecision): Int =
        (signalScore.coerceIn(0, 100) * SIGNAL_RATING_WEIGHT + decision.eligibilityRating.coerceIn(0, 100) * MEMORY_RATING_WEIGHT)
            .roundToInt()
            .coerceIn(0, 100)

    /** Removes the trades behind one memory key so a setup can be re-learned from scratch. */
    fun forget(state: JournalState, key: String): JournalState =
        if (isFamilyKey(key)) {
            state.copy(trades = state.trades.filterNot { familyKeyOf(familyForTrade(it.setupLabel.ifBlank { it.setupFamily }, it.side)) == key })
        } else {
            state.copy(trades = state.trades.filterNot { keyOf(it.symbol, familyForTrade(it.setupLabel.ifBlank { it.setupFamily }, it.side)) == key })
        }

    /** Collapse a verbose setup label into a stable family, so learning survives label wording. */
    fun familyOf(setupLabel: String): String {
        val label = setupLabel.uppercase(Locale.US)
        val scalp = label.contains("SCALP")
        val swing = label.contains("SWING")
        val family = when {
            scalp && swing -> "SCALP+SWING"
            scalp -> "SCALP"
            swing -> "SWING"
            label.contains("DEVELOPING") -> "DEVELOPING"
            label.contains("DATA INCOMPLETE") -> "DATA INCOMPLETE"
            label.contains("NO TRADE") -> "NO TRADE"
            else -> "OTHER"
        }
        val directionLabel = label.replace(Regex("SHORT[- ]TERM"), "")
        val shortSide = Regex("\\bSHORT\\b").containsMatchIn(directionLabel) || directionLabel.contains("BEARISH") || directionLabel.contains("BREAKDOWN")
        return if (shortSide && family !in setOf("NO TRADE", "DATA INCOMPLETE", "DEVELOPING", "OTHER")) "$family SHORT" else family
    }

    /** Side-aware family for records whose human setup label does not itself state a direction. */
    fun familyForTrade(setupLabel: String, side: String): String {
        val family = familyOf(setupLabel)
        return if (TradeMath.isShort(side) && !family.endsWith(" SHORT")) "$family SHORT" else family
    }

    fun keyOf(symbol: String, setupFamily: String): String =
        "${TradeMath.normalizeSymbol(symbol)}|$setupFamily"

    fun familyKeyOf(setupFamily: String): String = "$MULTI_MARKET_PREFIX|$setupFamily"

    fun isFamilyKey(key: String): Boolean = key.startsWith("$MULTI_MARKET_PREFIX|")

    fun symbolOf(key: String): String = key.substringBefore('|')

    fun familyOfKey(key: String): String = key.substringAfter('|', "OTHER")

    fun displayKey(key: String): String =
        if (isFamilyKey(key)) "ALL MARKETS · ${familyOfKey(key)}" else "${symbolOf(key)} · ${familyOfKey(key)}"

    /** Weight applied to one result, derived from the rating the app gave at entry. */
    fun winWeight(appRatingAtEntry: Int): Double =
        WIN_BASE_WEIGHT + WIN_CONVICTION_WEIGHT * (appRatingAtEntry.coerceIn(0, 100) / 100.0)

    fun lossWeight(appRatingAtEntry: Int): Double =
        LOSS_BASE_WEIGHT + LOSS_CONVICTION_WEIGHT * (appRatingAtEntry.coerceIn(0, 100) / 100.0)

    fun ratingFrom(weightedWins: Double, weightedLosses: Double): Int {
        val total = weightedWins + weightedLosses + 2.0 * SMOOTHING_PRIOR
        if (total <= 0.0) return NEUTRAL_RATING
        val ratio = (weightedWins + SMOOTHING_PRIOR) / total
        return (ratio * 100.0).toInt().coerceIn(0, 100)
    }

    /**
     * Escalation ladder. The first matching rule wins, so it is intentionally ordered.
     *
     * The heaviest sanctions also depend on the current rating, so a setup that keeps winning
     * afterwards climbs back out of probation and block: the memory punishes failures but still
     * rewards a setup that proves itself again.
     */
    fun directiveFor(
        rating: Int,
        wins: Int,
        losses: Int,
        highRatedLosses: Int,
        consecutiveLosses: Int,
        expectancyPips: Double,
    ): TradeDirective {
        val decided = wins + losses
        return when {
            decided >= 2 && highRatedLosses >= HIGH_RATED_LOSS_BLOCK && rating < 50 -> TradeDirective.BLOCK
            decided >= 2 && consecutiveLosses >= CONSECUTIVE_LOSS_BLOCK -> TradeDirective.BLOCK
            decided >= 3 && rating < 20 -> TradeDirective.BLOCK
            decided >= 2 && highRatedLosses >= HIGH_RATED_LOSS_PROBATION && rating < 55 -> TradeDirective.PROBATION
            decided >= 2 && rating < 30 -> TradeDirective.PROBATION
            rating < 25 -> TradeDirective.PROBATION
            consecutiveLosses >= CONSECUTIVE_LOSS_CAUTION -> TradeDirective.CAUTION
            rating < 45 -> TradeDirective.CAUTION
            decided >= 3 && expectancyPips <= 0.0 -> TradeDirective.CAUTION
            decided >= MIN_MEMORY_SAMPLE_FOR_BONUS && rating >= HIGH_RATING_THRESHOLD -> TradeDirective.PREFER
            else -> TradeDirective.ALLOW
        }
    }

    fun scoreAdjustmentFor(directive: TradeDirective): Int = when (directive) {
        TradeDirective.PREFER -> SCORE_ADJUSTMENT_PREFER
        TradeDirective.ALLOW -> 0
        TradeDirective.CAUTION -> SCORE_ADJUSTMENT_CAUTION
        TradeDirective.PROBATION -> SCORE_ADJUSTMENT_PROBATION
        TradeDirective.BLOCK -> SCORE_ADJUSTMENT_BLOCK
    }

    /** Rebuilds every memory from the stored trades. This is the single source of journal truth. */
    fun rebuild(state: JournalState, excludeManuallyEdited: Boolean = false): JournalSnapshot {
        val trades = state.trades.sortedBy { it.closedAtEpochMs }
        val grouped = LinkedHashMap<String, MutableList<ClosedTrade>>()
        trades.filterNot { excludeManuallyEdited && it.manuallyEdited }
            .filter { it.source != TradeJournalSource.BROKER_SYNC || it.setupFamily.uppercase(Locale.US) != "OTHER" || it.signalScoreAtEntry != 50 || it.appRatingAtEntry != 50 }
            .forEach { trade ->
            // Recompute side-aware keys on replay to migrate older short records away from long memory.
            val family = familyForTrade(trade.setupLabel.ifBlank { trade.setupFamily }, trade.side)
            val symbolKey = keyOf(trade.symbol, family)
            grouped.getOrPut(symbolKey) { mutableListOf() } += trade
            grouped.getOrPut(familyKeyOf(family)) { mutableListOf() } += trade
        }
        val memories = mutableListOf<SetupMemoryRecord>()
        val ratingPoints = mutableListOf<TradeRatingPoint>()
        grouped.forEach { (key, records) ->
            val (memory, points) = replay(key, records)
            memories += memory
            if (!isFamilyKey(key)) ratingPoints += points
        }
        memories.sortWith(compareByDescending<SetupMemoryRecord> { it.rating }.thenBy { it.key })
        val closedAtByTradeId = trades.associate { it.tradeId to it.closedAtEpochMs }
        return JournalSnapshot(
            trades = trades.sortedByDescending { it.closedAtEpochMs },
            openTickets = state.openTickets.sortedByDescending { it.openedAtEpochMs },
            memories = memories,
            stats = statsOf(trades, memories),
            ratingPoints = ratingPoints.sortedBy { closedAtByTradeId[it.tradeId] ?: 0L },
            generatedAtEpochMs = Instant.now().toEpochMilli(),
        )
    }

    private fun replay(key: String, records: List<ClosedTrade>): Pair<SetupMemoryRecord, List<TradeRatingPoint>> {
        val family = familyOfKey(key)
        val symbol = symbolOf(key)
        var weightedWins = 0.0
        var weightedLosses = 0.0
        var wins = 0
        var losses = 0
        var breakevens = 0
        var ungraded = 0
        var highRatedWins = 0
        var highRatedLosses = 0
        var consecutiveWins = 0
        var consecutiveLosses = 0
        var grossWinPips = 0.0
        var grossLossPips = 0.0
        var rating = NEUTRAL_RATING
        var directive = TradeDirective.ALLOW
        val points = mutableListOf<TradeRatingPoint>()

        records.forEach { trade ->
            val ratingBefore = rating
            when (trade.outcome) {
                TradeOutcome.WIN -> {
                    weightedWins += winWeight(trade.appRatingAtEntry)
                    wins++
                    consecutiveWins++
                    consecutiveLosses = 0
                    grossWinPips += abs(trade.resultPips)
                    if (trade.appRatingAtEntry >= HIGH_RATING_THRESHOLD) highRatedWins++
                }

                TradeOutcome.LOSS -> {
                    weightedLosses += lossWeight(trade.appRatingAtEntry)
                    losses++
                    consecutiveLosses++
                    consecutiveWins = 0
                    grossLossPips += abs(trade.resultPips)
                    if (trade.appRatingAtEntry >= HIGH_RATING_THRESHOLD) highRatedLosses++
                }

                TradeOutcome.BREAKEVEN -> {
                    breakevens++
                    consecutiveWins = 0
                    consecutiveLosses = 0
                }

                TradeOutcome.UNKNOWN -> ungraded++
            }
            rating = ratingFrom(weightedWins, weightedLosses)
            val decided = wins + losses
            val expectancy = if (decided == 0) 0.0 else TradeMath.roundTo((grossWinPips - grossLossPips) / decided, 1)
            directive = directiveFor(rating, wins, losses, highRatedLosses, consecutiveLosses, expectancy)
            points += TradeRatingPoint(
                tradeId = trade.tradeId,
                memoryKey = key,
                ratingBefore = ratingBefore,
                ratingAfter = rating,
                directiveAfter = directive,
                delta = rating - ratingBefore,
            )
        }

        val decided = wins + losses
        val netPips = TradeMath.roundTo(grossWinPips - grossLossPips, 1)
        val expectancy = if (decided == 0) 0.0 else TradeMath.roundTo(netPips / decided, 1)
        val averageWin = if (wins == 0) 0.0 else TradeMath.roundTo(grossWinPips / wins, 1)
        val averageLoss = if (losses == 0) 0.0 else TradeMath.roundTo(grossLossPips / losses, 1)
        val record = SetupMemoryRecord(
            key = key,
            symbol = symbol,
            setupFamily = family,
            trades = wins + losses + breakevens + ungraded,
            wins = wins,
            losses = losses,
            breakevens = breakevens,
            ungraded = ungraded,
            highRatedWins = highRatedWins,
            highRatedLosses = highRatedLosses,
            weightedWins = TradeMath.roundTo(weightedWins, 3),
            weightedLosses = TradeMath.roundTo(weightedLosses, 3),
            rating = rating,
            previousRating = points.dropLast(1).lastOrNull()?.ratingAfter ?: NEUTRAL_RATING,
            winRatePct = if (decided == 0) 0 else (wins * 100.0 / decided).toInt(),
            averageWinPips = averageWin,
            averageLossPips = averageLoss,
            netPips = netPips,
            expectancyPips = expectancy,
            consecutiveWins = consecutiveWins,
            consecutiveLosses = consecutiveLosses,
            lastOutcome = records.lastOrNull()?.outcome,
            lastClosedAtEpochMs = records.lastOrNull()?.closedAtEpochMs ?: 0L,
            directive = directive,
            reason = reasonFor(symbol, family, wins, losses, breakevens, rating, highRatedLosses, consecutiveLosses, expectancy, directive),
            scoreAdjustment = scoreAdjustmentFor(directive),
        )
        return record to points
    }

    private fun reasonFor(
        symbol: String,
        family: String,
        wins: Int,
        losses: Int,
        breakevens: Int,
        rating: Int,
        highRatedLosses: Int,
        consecutiveLosses: Int,
        expectancyPips: Double,
        directive: TradeDirective,
    ): String {
        val scope = if (symbol == MULTI_MARKET_PREFIX) "all markets" else symbol
        val unit = if (symbol == MULTI_MARKET_PREFIX) "units" else TradeMath.pipLabel(symbol)
        val parts = mutableListOf<String>()
        parts += "$scope $family record: ${wins}W/${losses}L/${breakevens}BE, rating $rating/100"
        parts += "expectancy ${TradeMath.formatPips(expectancyPips)} $unit per trade"
        if (highRatedLosses > 0) {
            parts += "$highRatedLosses high-rated loss${if (highRatedLosses == 1) "" else "es"} (the app rated this setup $HIGH_RATING_THRESHOLD+ and it still lost)"
        }
        if (consecutiveLosses > 0) parts += "$consecutiveLosses loss${if (consecutiveLosses == 1) "" else "es"} in a row"
        parts += "directive=${directive.name}"
        return parts.joinToString("; ")
    }

    fun statsOf(trades: List<ClosedTrade>, memories: List<SetupMemoryRecord>): JournalStats {
        val wins = trades.count { it.outcome == TradeOutcome.WIN }
        val losses = trades.count { it.outcome == TradeOutcome.LOSS }
        val breakevens = trades.count { it.outcome == TradeOutcome.BREAKEVEN }
        val grossWin = TradeMath.roundTo(trades.filter { it.outcome == TradeOutcome.WIN }.sumOf { abs(it.resultPips) }, 1)
        val grossLoss = TradeMath.roundTo(trades.filter { it.outcome == TradeOutcome.LOSS }.sumOf { abs(it.resultPips) }, 1)
        val decided = wins + losses
        val symbolMemories = memories.filterNot { isFamilyKey(it.key) }
        return JournalStats(
            totalTrades = trades.size,
            wins = wins,
            losses = losses,
            breakevens = breakevens,
            winRatePct = if (decided == 0) 0 else (wins * 100.0 / decided).toInt(),
            grossWinPips = grossWin,
            grossLossPips = grossLoss,
            netPips = TradeMath.roundTo(grossWin - grossLoss, 1),
            averageWinPips = if (wins == 0) 0.0 else TradeMath.roundTo(grossWin / wins, 1),
            averageLossPips = if (losses == 0) 0.0 else TradeMath.roundTo(grossLoss / losses, 1),
            expectancyPips = if (decided == 0) 0.0 else TradeMath.roundTo((grossWin - grossLoss) / decided, 1),
            profitFactor = if (grossLoss <= 0.0) 0.0 else TradeMath.roundTo(grossWin / grossLoss, 2),
            highRatedLosses = symbolMemories.sumOf { it.highRatedLosses },
            blockedSetups = symbolMemories.count { it.directive == TradeDirective.BLOCK },
            cautionedSetups = symbolMemories.count { it.directive == TradeDirective.CAUTION || it.directive == TradeDirective.PROBATION },
            preferredSetups = symbolMemories.count { it.directive == TradeDirective.PREFER },
            bestSetupKey = symbolMemories.filter { it.trades > 0 }.maxByOrNull { it.rating }?.key,
            worstSetupKey = symbolMemories.filter { it.trades > 0 }.minByOrNull { it.rating }?.key,
            bestSymbol = symbolTotals(trades).maxByOrNull { it.second }?.first,
            worstSymbol = symbolTotals(trades).minByOrNull { it.second }?.first,
        )
    }

    private fun symbolTotals(trades: List<ClosedTrade>): List<Pair<String, Double>> =
        trades.groupBy { TradeMath.normalizeSymbol(it.symbol) }
            .map { (symbol, list) -> symbol to TradeMath.roundTo(list.sumOf { it.resultPips }, 1) }
            .filter { it.second != 0.0 }

    /** Merged decision for a candidate: symbol-specific memory and cross-market family memory. */
    fun decision(snapshot: JournalSnapshot, symbol: String, setupLabel: String): TradeMemoryDecision {
        val family = familyOf(setupLabel)
        val key = keyOf(symbol, family)
        val symbolRecord = snapshot.memories.firstOrNull { it.key == key }
        val familyRecord = snapshot.memories.firstOrNull { it.key == familyKeyOf(family) }
        if (symbolRecord == null && familyRecord == null) return TradeMemoryDecision.neutral(key)
        val directive = listOfNotNull(symbolRecord?.directive, familyRecord?.directive).maxBy { it.strictness }
        val adjustment = listOfNotNull(symbolRecord?.scoreAdjustment, familyRecord?.scoreAdjustment).min()
        val rating = symbolRecord?.rating ?: familyRecord!!.rating
        val ratingSampleCount = symbolRecord?.trades ?: familyRecord!!.trades
        val eligibilityRating = if (ratingSampleCount >= MIN_MEMORY_SAMPLE_FOR_BONUS) rating else minOf(NEUTRAL_RATING, rating)
        val driver = when {
            symbolRecord != null && familyRecord != null && familyRecord.directive.strictness > symbolRecord.directive.strictness -> familyRecord
            symbolRecord != null -> symbolRecord
            else -> familyRecord!!
        }
        val reason = buildString {
            append(driver.reason)
            if (ratingSampleCount < MIN_MEMORY_SAMPLE_FOR_BONUS) append(" · memory cannot raise eligibility before $MIN_MEMORY_SAMPLE_FOR_BONUS closed trades; current sample=$ratingSampleCount")
            if (symbolRecord != null && familyRecord != null && driver.key != symbolRecord.key) {
                append(" · ${TradeMath.normalizeSymbol(symbol)} history is ${symbolRecord.wins}W/${symbolRecord.losses}L at ${symbolRecord.rating}/100")
            } else if (symbolRecord != null && familyRecord != null) {
                append(" · family history is ${familyRecord.wins}W/${familyRecord.losses}L at ${familyRecord.rating}/100")
            }
        }
        return TradeMemoryDecision(
            memoryKey = key,
            symbolRecord = symbolRecord,
            familyRecord = familyRecord,
            rating = rating,
            eligibilityRating = eligibilityRating,
            directive = directive,
            scoreAdjustment = adjustment,
            reason = reason,
        )
    }

    /** The human-readable lesson recorded with a closed trade. */
    fun lessonFor(trade: ClosedTrade): String {
        val unit = TradeMath.pipLabel(trade.symbol)
        val rating = trade.appRatingAtEntry
        val conviction = when {
            rating >= HIGH_RATING_THRESHOLD -> "high conviction"
            rating >= 40 -> "medium conviction"
            else -> "low conviction"
        }
        return when (trade.outcome) {
            TradeOutcome.WIN -> "Target reached on ${trade.symbol} for ${TradeMath.formatPips(trade.resultPips)} $unit (stop was ${trade.stopPips} $unit, target ${trade.targetPips} $unit). " +
                "The app rated this setup $rating/100 ($conviction) and was right, so this winning setup is remembered and preferred next time."

            TradeOutcome.LOSS -> if (rating >= HIGH_RATING_THRESHOLD) {
                "HIGH-RATED LOSS: the app rated this setup $rating/100 ($conviction) and it still lost ${TradeMath.formatPips(trade.resultPips)} $unit. " +
                    "The setup is stored with its rating reduced, which forces caution on the next identical signal."
            } else {
                "Stop reached on ${trade.symbol} for ${TradeMath.formatPips(trade.resultPips)} $unit. " +
                    "The app had already rated this setup only $rating/100, so the memory penalty is applied at a lower weight."
            }

            TradeOutcome.BREAKEVEN -> "Closed at breakeven on ${trade.symbol}; the rating is unchanged because there is no edge to learn from."
            TradeOutcome.UNKNOWN -> "Result could not be graded for ${trade.symbol}; stored for the record but excluded from the rating."
        }
    }

    fun recordTrade(state: JournalState, trade: ClosedTrade): JournalState {
        val deduplicated = state.trades.filterNot { existing ->
            existing.tradeId == trade.tradeId ||
                (trade.ticketId != null && existing.ticketId == trade.ticketId)
        }
        val remainingTickets = state.openTickets.filterNot { ticket ->
            ticket.ticketId == trade.ticketId || ticket.clientOrderId == trade.brokerOrderId
        }
        return state.copy(trades = (deduplicated + trade).sortedByDescending { it.closedAtEpochMs }, openTickets = remainingTickets)
    }

    fun recordTickets(state: JournalState, tickets: List<OpenTradeTicket>): JournalState {
        val merged = state.openTickets.toMutableList()
        tickets.forEach { ticket ->
            merged.removeAll { it.ticketId == ticket.ticketId || it.clientOrderId == ticket.clientOrderId }
            merged += ticket
        }
        return state.copy(openTickets = merged.sortedByDescending { it.openedAtEpochMs })
    }

    fun removeTrade(state: JournalState, tradeId: String): JournalState =
        state.copy(trades = state.trades.filterNot { it.tradeId == tradeId })

    fun removeTicket(state: JournalState, ticketId: String): JournalState =
        state.copy(openTickets = state.openTickets.filterNot { it.ticketId == ticketId })

    /** CSV export of the full journal, for spreadsheets and for the user's own record keeping. */
    fun csv(trades: List<ClosedTrade>): String {
        val ratingPoints = rebuild(JournalState(trades = trades)).ratingPoints.associateBy { it.tradeId }
        val header = listOf(
            "trade_id", "ticket_id", "closed_at_utc", "symbol", "side", "account", "setup_family", "setup_label", "signal_score",
            "app_rating_at_entry", "memory_rating_before", "memory_rating_after", "directive_after", "directive_at_entry", "entry_price", "stop_price", "target_price", "exit_price",
            "quantity", "stop_pips", "target_pips", "risk_reward", "result_pips", "realized_pnl", "outcome",
            "stop_distance_price", "target_distance_price", "result_price_move", "result_ticks", "result_unit", "risk_data_valid", "learning_eligible",
            "source", "lesson", "notes",
        ).joinToString(",")
        val rows = trades.sortedByDescending { it.closedAtEpochMs }.map { trade ->
            val rating = ratingPoints[trade.tradeId]
            listOf(
                escapeCsv(trade.tradeId),
                escapeCsv(trade.ticketId.orEmpty()),
                Instant.ofEpochMilli(trade.closedAtEpochMs).toString(),
                trade.symbol,
                TradeMath.sideLabel(trade.side),
                trade.accountMode,
                trade.setupFamily,
                trade.setupLabel,
                trade.signalScoreAtEntry.toString(),
                trade.appRatingAtEntry.toString(),
                rating?.ratingBefore?.toString().orEmpty(),
                rating?.ratingAfter?.toString().orEmpty(),
                rating?.directiveAfter?.name.orEmpty(),
                trade.directiveAtEntry.name,
                TradeMath.formatPrice(trade.entryPrice),
                TradeMath.formatPrice(trade.stopPrice),
                TradeMath.formatPrice(trade.targetPrice),
                TradeMath.formatPrice(trade.exitPrice),
                trade.quantity.toString(),
                trade.stopPips.toString(),
                trade.targetPips.toString(),
                trade.riskReward.toString(),
                trade.resultPips.toString(),
                trade.realizedPnl.toString(),
                trade.outcome.name,
                TradeMath.formatPrice(kotlin.math.abs(trade.entryPrice - trade.stopPrice)),
                TradeMath.formatPrice(kotlin.math.abs(trade.targetPrice - trade.entryPrice)),
                TradeMath.formatPrice(kotlin.math.abs(trade.exitPrice - trade.entryPrice) * if (TradeMath.isShort(trade.side)) -1 else 1),
                trade.resultPips.toString(),
                TradeMath.pipLabel(trade.symbol),
                (TradeMath.validationError(trade.symbol, trade.side, trade.entryPrice, trade.stopPrice, trade.targetPrice) == null).toString(),
                (trade.source != TradeJournalSource.BROKER_SYNC || trade.setupFamily.uppercase(Locale.US) != "OTHER" || trade.signalScoreAtEntry != 50 || trade.appRatingAtEntry != 50).toString(),
                trade.source.name,
                escapeCsv(trade.lesson),
                escapeCsv(trade.notes),
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
}
