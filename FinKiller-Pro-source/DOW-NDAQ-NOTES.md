# DOW and NDAQ

- Added `DOW` and `NDAQ` to the explicit `US stocks 7` Alpaca scanner basket.
- These are equity tickers: DOW is Dow Inc.; NDAQ is Nasdaq Inc.
- This is separate from the non-tradable Dow Jones and Nasdaq index names. The existing ETF proxies remain `DIA` and `QQQ`.
- The closed-trade NDAQ record was not created by this basket; it came from the account fill-history reconstruction, which is now verified against filled Alpaca orders.

Validation: `testDebugUnitTest` and `assembleDebug` passed.

## Basket execution audit (2026-10-12)

- Audited all 16 configured baskets: 160 ticker slots, 10 per basket, no malformed or repeated ticker within a basket. Four tickers appear across two different categories (`ARKK`, `EWT`, `GDX`, `XLE`); they are still de-duplicated within each scan input.
- The new `US stocks 7` names (`DOW`, `NDAQ`, `LIN`, `APD`, `NUE`, `FCX`, `EMR`, `ETN`, `PH`, `UPS`) follow the same standard path as the earlier standard US-stock baskets: one-share market entry, initial 2% stop, and 4% target. DOW/NDAQ are not incorrectly routed as index symbols.
- Existing designated second-batch symbols retain their limit-entry / wider 8% initial-stop treatment. The global stop-price retry remains available if the quote moves during submission.
- Corrected target geometry so the take-profit remains twice the stop distance: 2% stop → 4% target; 8% stop/retry → 16% target. This removes the previous 8%-risk/4%-reward mismatch.
- This was a source-level audit only; no broker order was submitted and no account-specific asset eligibility was checked.

## Close-all and broker-confirmed journal update (2026-10-12)

The open-positions view now provides a confirmed Close All action. Before a per-symbol close request, the app inspects Alpaca order history, cancels only active stop/target legs belonging to already-filled bracket orders for the selected symbols, and verifies they are no longer active. Unrelated pending orders remain untouched. If another active order for the same symbol remains, or the protective-order state cannot be verified, the app skips that symbol rather than risking an order conflict or a newly opened position.

Account refresh now reconciles filled bracket legs and recent Alpaca fill activity automatically. It stores the actual quantity-weighted exit price only after a filled broker entry and full matching exit quantity are verified. Partial exits remain open in the journal until the full tracked quantity has exited. Recent closed Alpaca round trips without app tickets can also be imported, with unavailable setup/stop data left explicitly unknown rather than inferred.

Regression coverage checks: (1) actual weighted exit pricing after verified fills, (2) mapping a recent exit to its verified app entry order even when the older entry activity is absent from the latest fill page, and (3) refusing to close on an unverified exit or incomplete quantity.


## Execution diagnostics and journal export update (2026-10-12)

- Close preflight now checks the live position side/quantity, cancels active protective bracket/OTO legs, OCO exits, and working same-symbol opposite-side stop/limit exits that can reserve the shares/contracts needed for liquidation. It re-reads order history and position quantity before submitting. Same-symbol entry orders still block the close; unrelated symbols are untouched. Debugger output now records position quantity, relevant order IDs, cancel outcomes, remaining conflicts, and the broker response. An insufficient-available-quantity response is not blindly retried.
- Clarified scanner score versus app rating. The scanner's 0–100 score is a live signal; the trade app rating is `round(70% × scanner score + 30% × remembered setup rating)`. Memory separately adjusts the execution score, and both adjusted score and app rating must meet 75. Rounded rather than truncated, so an 85 signal against neutral 50 memory is app-rated 75, not 74. The scanner now previews all three numbers; debugger entries include the formula and threshold.
- The journal can save a complete CSV or paginated PDF through Android's Save As picker. CSV includes IDs, entry/stop/target/exit, quantity, pips, realized P/L, signal/app/setup-memory ratings, directives, lessons, and notes. PDF includes summary stats, trade detail, rating movement, notes, and any open tickets.
- Account/position/order auto-refresh interval is now 10 seconds. Auto-trading scan/execution cadence remains 60 seconds.
- Added regression checks for the 85/neutral-memory rounding boundary and CSV completeness/escaping.


## Execution rating floor update (2026-10-12)

The scanner qualification floor and both the adjusted-score and blended app-rating execution gates are now 72/100 (permitted range 72–100). Setup confirmation and setup-memory restrictions remain in effect. Scanner explanations, execution-block messages, and debugger diagnostics report the active 72-point threshold.


## Scanner/auto-bot state fix (2026-10-12)

Automatic bot scans now refresh selected symbols without clearing the full candidate list or selected-symbol set. Only symbols returned by the latest successful scan are eligible for that iteration; an empty/failed refresh does not execute from stale candidates. The Stop bot action takes priority over symbol-selection validation and remains enabled while scans or order checks are active. Cancellation clears the scan-busy state and is not logged as a market-data failure; if stopped during an order request, the app labels the broker outcome unverified and does not retry automatically.


## Scan alerts and manual bulk review (2026-10-12)
- Replaced the scanner's background auto-submit loop with a scan-only monitor of the complete entered watchlist, checking every 60 seconds. The monitor never calls the broker order path.
- After each completed manual or monitored scan, the app posts a local Android notification only when one or more fresh setups pass the same app-rating, memory-adjusted-score, and strategy gates used by execution. The notification includes symbol and rating details. Android notification permission is requested on supported versions.
- Users can select multiple currently qualified symbols and use the existing explicit **Bulk execute** action; LIVE mode presents a confirmation naming the selected symbols before sequential submissions. Scanner alerts and scanning alone never submit orders.
- Selections and manual execution require a symbol returned by a completed scan within three minutes. Preserved older findings are labelled stale and cannot be selected or executed until rescanned.
- Validation: Android unit tests and debug APK build passed (51 tests, 0 failures).
- Position sizing was not changed in this update: manual submissions still use the existing one-share sizing. The previously discussed 10% total stop-loss risk cap is not implemented and is not enforced by bulk execution.


## Startup-permission mitigation (2026-10-12)
The Android 13+ notification permission prompt was removed from cold app startup and moved behind an explicit **Allow setup notifications** action on the scanner screen. The notification channel is created safely when an alert is posted. The dashboard refresh label now matches the 10-second polling loop. This sandbox has no attached Android device/emulator, so device-side cold launch could not be reproduced.


## Read-only 10% stop-risk scan gate (2026-10-12)
The scan-alert monitor now checks the latest account equity, positions, and nested Alpaca order history before each scan. It pauses at modeled downside-to-stop risk greater than or equal to 10% of current equity, as well as whenever risk cannot be verified (stale data, a missing/undersized market stop, a stop-limit, unsupported asset class, or a potentially exposure-increasing working entry order). While paused it checks refreshed account/stop data every 10 seconds and resumes 60-second scans once verified risk is below the cap. A position with a verified stop at breakeven or better contributes zero downside risk; profit above entry is not credited as extra budget. This is a read-only observer: the user must move stops manually in Alpaca; the app does not trail, replace, cancel, or submit orders. The estimate does not account for gaps or slippage and is not a guaranteed maximum-loss limit. Existing manual order submission/one-share sizing is unchanged, so this scanner pause is not an order-level risk cap.

The cold-start Android 13+ notification prompt was moved to an explicit scanner-screen **Allow setup notifications** action. No Android device/emulator was attached in the sandbox, so startup could not be reproduced; the replacement APK is build/test verified, but a device crash trace is still needed if it continues.

## Notification-permission crash hardening (2026-10-12)
Audited all runtime permission and Activity Result entry points. The Compose permission launcher raised `Can only use lower 16 bits for requestCode`; it has been removed and replaced with `ActivityCompat.requestPermissions` using fixed request code `0x4F21` (within Android's 16-bit range). Permission state refreshes on activity resume, and channel creation/posting are exception-guarded. CSV/PDF document-picker launches also catch lifecycle/launch failures and show feedback. No other runtime permission requests were found. Build and all 64 JVM tests pass. No Android device/emulator was attached, so the permission dialog still needs on-device confirmation.

## Full-position close availability fix (2026-10-12)
The screenshot's Alpaca 403 (`requested: 3, available: 0`) exposed a missing precondition: the close routine inspected open orders and position quantity but did not check Alpaca's `qty_available` before sending a full-position DELETE. After canceling verified protective/exit orders, it now polls the live position for up to about 4.8 seconds for the full quantity to become available. It sends no close request if the field is missing or if any part of the quantity remains reserved, and reports the exact `qty`/`qty_available` values in the UI/debugger. It does not blindly retry a rejected close. Six focused tests cover zero, partial, full, short, missing, and malformed quantities. Full Android build passed; all 64 JVM tests passed.


## All-basket auto-scan findings and notification reporting (2026-10-12)
The read-only scan monitor now uses the same shared basket source as the visible picker and cycles every configured basket, not only the currently selected/manual text list. The 16 original baskets contain 160 listed entries and 156 unique symbols after de-duplicating repeated symbols (including the preserved DOW and NDAQ group); symbols are scanned in 16 Alpaca-sized batches of at most 10. A full sweep is followed by a 60-second delay, with account/risk refresh permitted during scanning and risk rechecked before each batch. Auto-scan data is separate from manual scanner snapshots and selections. **Auto-scan findings** displays monitor results; a separate **Confirm orders** queue receives setups that pass current eligibility after each batch. A distinct notification is posted after every completed batch (including a no-match summary), and eligible symbols/scores are included in its text. Notifications are checked against runtime permission, app-level enablement, and channel state; the scanner surfaces delivery status and provides an Android settings route. No orders are submitted by scanning or notification; candidates must be selected and explicitly confirmed, expire three minutes after their scan, and are rechecked against the normal execution gates before submission. Regression tests verify all 16 baskets, 156 unique symbols, DOW/NDAQ preservation, and complete non-duplicated batches. Full Android unit suite: 67 tests passed; debug APK build succeeded. On-device notification delivery remains subject to Android OS settings and could not be physically confirmed in this sandbox.


## Directional short setup support (2026-10-12)
The scanner now evaluates LONG and SHORT strategies independently on 1-minute, 15-minute, 1-hour, and daily bars. The short model mirrors the existing long checks: downside EMA alignment and price below VWAP, range breakdowns or an upside liquidity sweep rejected below the prior high, volume expansion, and the same scalp/swing risk-reward thresholds. Every result carries an explicit LONG/SHORT direction through manual results, auto-scan findings, batch notification text, and the Confirm orders queue. If valid long and short candidates conflict across timeframes, the setup is marked mixed and is not eligible; no side is guessed.

After explicit user confirmation, SHORT candidates use sell-side Alpaca bracket orders with stop above entry and take-profit below entry. Before attempting a short, the app fetches `/v2/assets/{symbol}` and fails closed unless Alpaca reports `status=active`, `tradable=true`, `shortable=true`, and `easy_to_borrow=true`; it also blocks if Alpaca reports an existing same-symbol position or working order. The broker still enforces account margin and short-sale rules. Order fills are journalled as `sell`/SHORT, and the existing pip/P&L math handles direction. Short setup memories are separated from legacy long families, including re-keying older short-side journal records during replay, so long history is retained without mixing opposite-direction results. The 10% monitor remains scan-only, and the existing one-share order size and manual-confirm workflow are unchanged. Regression coverage includes rising/falling directional signals, bearish sweep rejection, mixed-direction blocking, borrowability fail-closed behavior, side-separated memory, and legacy short-memory migration. Full Android unit suite: 76 tests passed; physical-device verification is still unavailable in this sandbox.


## Source audit update — 13 October 2026

The notes above describe earlier revisions and are historical. They are superseded by the current source-level remediation in `android/README.md` and the delivered `FinKiller_Deep_Dive.md` / `CHANGELOG.md`. In particular, the current Android order path no longer uses one-share sizing or the fixed 2%/4% brackets; it uses scanner stop/target geometry, fresh account-risk and notional gates, completed-bar/quote quality checks, and hard Paper-only order mutation guards. The current unit suite and APK build were re-run after those changes. The strategy still has no historical walk-forward backtest and its score is not a win probability.
