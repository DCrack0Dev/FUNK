package com.alpacatalk.trading.data

import java.util.Locale

/** Original scanner baskets; used by both the visible picker and the read-only all-basket monitor. */
object ScannerBaskets {
    const val AUTO_SCAN_MAX_CONCURRENT_BATCHES = 4
    val baskets: List<Pair<String, String>> = listOf(
        "US stocks 1" to "AAPL, MSFT, NVDA, AMZN, META, TSLA, AMD, GOOGL, NFLX, JPM",
        "US stocks 2" to "AVGO, ORCL, COST, CRM, ADBE, INTC, QCOM, MU, BA, DIS",
        "US stocks 3" to "LLY, UNH, JNJ, ABBV, PFE, MRK, AMGN, GILD, XOM, CVX",
        "US stocks 4" to "WMT, HD, MCD, KO, PEP, NKE, LOW, SBUX, CAT, GE",
        "US stocks 5" to "PLTR, CRWD, PANW, SNOW, NET, DDOG, NOW, SHOP, UBER, COIN",
        "US stocks 6" to "T, VZ, CMCSA, CHTR, TMO, ISRG, DE, HON, LMT, RTX",
        "US stocks 7" to "DOW, NDAQ, LIN, APD, NUE, FCX, EMR, ETN, PH, UPS",
        "Index ETF proxies 1" to "SPY, QQQ, DIA, IWM, VTI, EFA, EEM, XLK, XLF, XLE",
        "Index ETF proxies 2" to "VOO, IVV, SCHD, VUG, VTV, RSP, MDY, VB, VXUS, ACWI",
        "Gold / oil / silver 1" to "GLD, IAU, SGOL, SLV, USO, BNO, XLE, UNG, PPLT, PALL",
        "Gold / oil / silver 2" to "GDX, GDXJ, SIL, SILJ, COPX, DBA, DBC, BOIL, KOLD, UUP",
        "Other ETFs 1" to "TLT, HYG, LQD, VNQ, XBI, SMH, TAN, ICLN, ARKK, GDX",
        "Other ETFs 2" to "XLV, XLP, XLY, XLC, XLB, XLI, XLU, XRT, KRE, EWT",
        "Income & defensive ETFs" to "BND, AGG, TIP, SHY, IEI, MUB, VCSH, VCIT, USMV, SPLV",
        "International ETFs" to "VEA, VWO, IEFA, IEMG, EWJ, EWZ, FXI, INDA, EWY, EWT",
        "Growth & innovation ETFs" to "ARKK, ARKG, ARKW, CLOU, BOTZ, ROBO, FINX, HACK, SKYY, IGV",
    )

    val allSymbols: List<String> = baskets
        .flatMap { (_, symbols) -> symbols.split(',').map { it.trim().uppercase(Locale.US) } }
        .filter { it.isNotBlank() }
        .distinct()

    /** Alpaca scanner requests accept at most ten symbols; chunks are combined into one sweep. */
    fun scanBatches(batchSize: Int = 10): List<List<String>> {
        require(batchSize > 0) { "Batch size must be positive." }
        return allSymbols.chunked(batchSize)
    }
}
