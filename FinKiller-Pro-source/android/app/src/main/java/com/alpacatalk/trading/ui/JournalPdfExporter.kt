package com.alpacatalk.trading.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.alpacatalk.trading.data.JournalSnapshot
import com.alpacatalk.trading.data.TradeMath
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Creates a self-contained, multi-page PDF report without adding storage permissions or dependencies. */
internal object JournalPdfExporter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val LEFT = 40f
    private const val RIGHT = 555f
    private const val BOTTOM = 798f

    fun render(snapshot: JournalSnapshot): ByteArray {
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 36, 54)
            textSize = 18f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(36, 48, 65)
            textSize = 12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(42, 48, 58)
            textSize = 9.5f
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(84, 92, 104)
            textSize = 8.5f
        }
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 210, 218); strokeWidth = 0.7f }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        lateinit var startPage: () -> Unit
        lateinit var reserve: (Float) -> Unit

        startPage = {
            page?.let(document::finishPage)
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page!!.canvas
            canvas!!.drawColor(Color.WHITE)
            canvas!!.drawText("Fin Killer Pro — Trade Journal${if (pageNumber > 1) " (continued)" else ""}", LEFT, 34f, titlePaint)
            canvas!!.drawText("Generated ${Instant.now()} · UTC", LEFT, 51f, smallPaint)
            y = 70f
        }
        reserve = { height ->
            if (page == null || y + height > BOTTOM) startPage()
        }

        fun drawLine(text: String, paint: Paint = bodyPaint, lineHeight: Float = 13f) {
            wrap(text, paint, RIGHT - LEFT).forEach { line ->
                reserve(lineHeight)
                canvas!!.drawText(line, LEFT, y, paint)
                y += lineHeight
            }
        }

        fun drawParagraph(text: String, paint: Paint = bodyPaint, lineHeight: Float = 12f) {
            wrap(text, paint, RIGHT - LEFT).forEach { line -> drawLine(line, paint, lineHeight) }
        }

        try {
            startPage()
            drawLine("Closed trades: ${snapshot.stats.totalTrades} · Wins: ${snapshot.stats.wins} · Losses: ${snapshot.stats.losses} · Breakeven: ${snapshot.stats.breakevens}", headingPaint, 16f)
            drawLine("Win rate ${snapshot.stats.winRatePct}% · Net ${TradeMath.formatPips(snapshot.stats.netPips)} pips · Expectancy ${TradeMath.formatPips(snapshot.stats.expectancyPips)} pips/trade · Profit factor ${snapshot.stats.profitFactor}", bodyPaint, 14f)
            drawLine("High-rated losses remembered: ${snapshot.stats.highRatedLosses} · Preferred ${snapshot.stats.preferredSetups} · Cautioned/probation ${snapshot.stats.cautionedSetups} · Blocked ${snapshot.stats.blockedSetups}", bodyPaint, 16f)
            canvas!!.drawLine(LEFT, y, RIGHT, y, rulePaint)
            y += 12f

            if (snapshot.trades.isEmpty()) {
                drawLine("No closed trades are stored in this journal.", bodyPaint)
            } else {
                snapshot.trades.sortedByDescending { it.closedAtEpochMs }.forEach { trade ->
                    reserve(78f)
                    val closedAt = runCatching { Instant.ofEpochMilli(trade.closedAtEpochMs).toString() }.getOrDefault("Unknown time")
                    drawLine("$closedAt · ${trade.symbol} · ${TradeMath.sideLabel(trade.side)} · ${trade.outcome.name}", headingPaint, 15f)
                    val entry = trade.entryPrice.takeIf { it > 0.0 }?.let { TradeMath.formatPrice(it) } ?: "unknown"
                    val stop = trade.stopPrice.takeIf { it > 0.0 }?.let { TradeMath.formatPrice(it) } ?: "unknown"
                    val target = trade.targetPrice.takeIf { it > 0.0 }?.let { TradeMath.formatPrice(it) } ?: "unknown"
                    val exit = trade.exitPrice.takeIf { it > 0.0 }?.let { TradeMath.formatPrice(it) } ?: "unknown"
                    drawLine("Qty ${trade.quantity} · Entry $entry · Exit $exit · Realized P/L ${"%.2f".format(java.util.Locale.US, trade.realizedPnl)}", bodyPaint)
                    drawLine("Stop $stop (${TradeMath.formatPips(trade.stopPips)} ${TradeMath.pipLabel(trade.symbol)}) · Target $target (${TradeMath.formatPips(trade.targetPips)} ${TradeMath.pipLabel(trade.symbol)}) · R:R ${trade.riskReward}", bodyPaint)
                    drawLine("Scanner ${trade.signalScoreAtEntry}/100 · App rating ${trade.appRatingAtEntry}/100 · ${trade.directiveAtEntry.name} · ${trade.accountMode} · ${trade.source.name}", bodyPaint)
                    snapshot.ratingPoints.firstOrNull { it.tradeId == trade.tradeId }?.let { point ->
                        drawLine("Setup memory ${point.ratingBefore}/100 → ${point.ratingAfter}/100 · ${point.directiveAfter.name}", bodyPaint)
                    }
                    drawParagraph("Setup: ${trade.setupLabel}", smallPaint, 11f)
                    drawParagraph("Result: ${TradeMath.formatPips(trade.resultPips)} ${TradeMath.pipLabel(trade.symbol)} · ${trade.lesson}", smallPaint, 11f)
                    if (trade.notes.isNotBlank()) drawParagraph("Notes: ${trade.notes}", smallPaint, 11f)
                    canvas!!.drawLine(LEFT, y + 2f, RIGHT, y + 2f, rulePaint)
                    y += 10f
                }
            }

            if (snapshot.openTickets.isNotEmpty()) {
                reserve(40f)
                drawLine("Open tickets — not included in closed-trade totals", headingPaint, 17f)
                snapshot.openTickets.forEach { ticket ->
                    drawLine("${ticket.symbol} · ${TradeMath.sideLabel(ticket.side)} · Qty ${ticket.quantity} · Entry ${TradeMath.formatPrice(ticket.entryPrice)} · App rating ${ticket.appRatingAtEntry}/100", bodyPaint)
                    drawParagraph("Stop ${TradeMath.formatPrice(ticket.stopPrice)} (${TradeMath.formatPips(ticket.stopPips)} ${TradeMath.pipLabel(ticket.symbol)}) · Target ${TradeMath.formatPrice(ticket.targetPrice)} (${TradeMath.formatPips(ticket.targetPips)} ${TradeMath.pipLabel(ticket.symbol)}) · ${ticket.setupLabel}", smallPaint, 11f)
                }
            }

            page?.let(document::finishPage)
            page = null
            val bytes = ByteArrayOutputStream()
            document.writeTo(bytes)
            return bytes.toByteArray()
        } finally {
            page?.let { runCatching { document.finishPage(it) } }
            document.close()
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val result = mutableListOf<String>()
        var line = StringBuilder()
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(candidate) > width) {
                result += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) result += line.toString()
        return result.ifEmpty { listOf("") }
    }

}
