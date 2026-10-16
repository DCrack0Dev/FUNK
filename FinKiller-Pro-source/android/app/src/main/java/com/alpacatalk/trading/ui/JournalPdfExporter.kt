package com.alpacatalk.trading.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.alpacatalk.trading.data.JournalSnapshot
import com.alpacatalk.trading.data.TradeMath
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Locale

/** Creates a compact, readable PDF with only the fields needed to review a trade. */
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
            textSize = 10.5f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            canvas!!.drawText("Fin Killer Pro — Trade Journal", LEFT, 34f, titlePaint)
            canvas!!.drawText("Generated ${Instant.now()} · UTC", LEFT, 51f, mutedPaint)
            y = 70f
        }
        reserve = { height -> if (page == null || y + height > BOTTOM) startPage() }

        fun drawLine(text: String, paint: Paint = bodyPaint, lineHeight: Float = 14f) {
            wrap(text, paint, RIGHT - LEFT).forEach { line ->
                reserve(lineHeight)
                canvas!!.drawText(line, LEFT, y, paint)
                y += lineHeight
            }
        }

        try {
            startPage()
            drawLine("${snapshot.stats.totalTrades} closed · ${snapshot.stats.wins} wins · ${snapshot.stats.losses} losses · ${snapshot.stats.breakevens} breakeven", headingPaint, 16f)
            drawLine("Win rate ${snapshot.stats.winRatePct}% · Net ${TradeMath.formatPips(snapshot.stats.netPips)} pips · Expectancy ${TradeMath.formatPips(snapshot.stats.expectancyPips)} pips/trade", bodyPaint)
            canvas!!.drawLine(LEFT, y + 2f, RIGHT, y + 2f, rulePaint)
            y += 16f

            if (snapshot.trades.isEmpty()) {
                drawLine("No closed trades are stored in this journal.")
            } else {
                snapshot.trades.sortedByDescending { it.closedAtEpochMs }.forEach { trade ->
                    reserve(62f)
                    val pnlPct = if (trade.entryPrice > 0.0 && trade.quantity > 0.0) {
                        trade.realizedPnl / (trade.entryPrice * trade.quantity) * 100.0
                    } else 0.0
                    val date = runCatching { Instant.ofEpochMilli(trade.closedAtEpochMs).toString().replace("T", " ").take(19) }
                        .getOrDefault("Unknown time")
                    drawLine("${trade.symbol} · ${TradeMath.sideLabel(trade.side)} · ${trade.outcome.name} · $date", headingPaint, 15f)
                    drawLine("Price ${TradeMath.formatPrice(trade.entryPrice)} → ${TradeMath.formatPrice(trade.exitPrice)} · Lots ${TradeMath.formatPrice(trade.quantity)} · SL ${TradeMath.formatPrice(trade.stopPrice)} · TP ${TradeMath.formatPrice(trade.targetPrice)}")
                    drawLine("P/L ${String.format(Locale.US, "%+.2f", trade.realizedPnl)} USD · PnL ${String.format(Locale.US, "%+.2f", pnlPct)}% · ${TradeMath.formatPips(trade.resultPips)} ${TradeMath.pipLabel(trade.symbol)}", bodyPaint)
                    canvas!!.drawLine(LEFT, y + 2f, RIGHT, y + 2f, rulePaint)
                    y += 10f
                }
            }

            if (snapshot.openTickets.isNotEmpty()) {
                reserve(36f)
                drawLine("Open tickets", headingPaint, 16f)
                snapshot.openTickets.forEach { ticket ->
                    drawLine("${ticket.symbol} · Price ${TradeMath.formatPrice(ticket.entryPrice)} · Lots ${TradeMath.formatPrice(ticket.quantity)} · SL ${TradeMath.formatPrice(ticket.stopPrice)} · TP ${TradeMath.formatPrice(ticket.targetPrice)}")
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
