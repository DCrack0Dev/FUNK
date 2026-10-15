package com.alpacatalk.trading

import com.alpacatalk.trading.data.AlpacaBarsParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpacaBarsParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesSymbolKeyedBatchBarsForMultipleSymbols() {
        val payload = json.parseToJsonElement(
            """
            {
              "bars": {
                "AAPL": [
                  {"t":"2026-10-12T13:30:00Z","o":175.0,"h":176.0,"l":174.5,"c":175.8,"v":120000},
                  {"t":"2026-10-12T13:31:00Z","o":175.8,"h":176.2,"l":175.5,"c":176.1,"v":95000}
                ],
                "MSFT": [
                  {"t":"2026-10-12T13:30:00Z","o":500.0,"h":501.0,"l":499.5,"c":500.5,"v":70000}
                ]
              },
              "next_page_token": null
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals(2, AlpacaBarsParser.parse(payload, "aapl").size)
        assertEquals(1, AlpacaBarsParser.parse(payload, "MSFT").size)
        assertEquals(120000, AlpacaBarsParser.parse(payload, "AAPL").first().volume.toInt())
    }

    @Test
    fun emptyMissingAndMalformedCandlesAreExcluded() {
        val payload = json.parseToJsonElement(
            """
            {
              "bars": {
                "EMPTY": [],
                "MISSING": [{"t":"2026-10-12T13:30:00Z","o":1,"h":2,"l":1,"v":10}],
                "BAD": [
                  {"t":"not-a-timestamp","o":1,"h":2,"l":1,"c":1.5,"v":10},
                  {"t":"2026-10-12T13:31:00Z","o":1,"h":2,"l":1,"c":1.5,"v":-1},
                  {"t":"2026-10-12T13:32:00Z","o":1,"h":2,"l":1,"c":1.5}
                ]
              }
            }
            """.trimIndent(),
        ).jsonObject

        assertTrue(AlpacaBarsParser.parse(payload, "EMPTY").isEmpty())
        assertTrue(AlpacaBarsParser.parse(payload, "MISSING").isEmpty())
        assertTrue(AlpacaBarsParser.parse(payload, "BAD").isEmpty())
        assertTrue(AlpacaBarsParser.parse(payload, "UNKNOWN").isEmpty())
    }

    @Test
    fun supportsExpandedCandleFieldNamesAndEmptyResponse() {
        val expanded = json.parseToJsonElement(
            """
            {"data":{"EUR/USD":[{"t":"2026-10-12T13:30:00Z","open":"1.1000","high":"1.1010","low":"1.0990","close":"1.1005","volume":"25"}]}}
            """.trimIndent(),
        ).jsonObject
        val empty = json.parseToJsonElement("{}").jsonObject

        assertEquals(1, AlpacaBarsParser.parse(expanded, "EURUSD").size)
        assertTrue(AlpacaBarsParser.parse(empty, "AAPL").isEmpty())
    }
    @Test
    fun parsesForexRatesWithNormalizedPairAndMidpointFallback() {
        val payload = json.parseToJsonElement(
            """
            {"rates":{"EURUSD":[{"t":"2026-10-12T01:00:00Z","ap":1.1012,"bp":1.1008,"mp":1.1010},{"t":"2026-10-12T01:01:00Z","ap":1.1014,"bp":1.1010}]}}
            """.trimIndent(),
        ).jsonObject

        val parsed = com.alpacatalk.trading.data.AlpacaForexRatesParser.parse(payload, "EUR/USD")
        assertEquals(2, parsed.size)
        assertEquals(1.1010, parsed.first().close, 0.000001)
        assertEquals(1.1012, parsed.last().close, 0.000001)
        assertEquals(0.0, parsed.first().volume, 0.0)
    }

}
