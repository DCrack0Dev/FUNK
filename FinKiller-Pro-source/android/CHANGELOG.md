# Fin Killer Pro source audit — implementation changelog

## Journal export request-code workaround (16 October 2026)

- Replaced the Compose `rememberLauncherForActivityResult(CreateDocument)` path with a direct Android document intent handled by `MainActivity`.
- CSV and PDF exports now use fixed request code `0x4A31`, explicitly masked to the lower 16 bits, and write the prepared bytes after the save dialog returns.
- This bypasses the AndroidX ActivityResultRegistry path that continued to trigger the device error about request codes being limited to the lower 16 bits.

## Stricter swing setup confirmation (16 October 2026)

- Swing candidates now require aligned direction on both the 1-hour and 1-day analyses, plus a breakout/liquidity event and valid R:R. A single higher-timeframe signal can no longer qualify an execution setup by itself.
- This intentionally reduces signal count to favor precision and avoid tuning the strategy to the small, low-quality 11-trade sample.

## Journal document export crash fix (16 October 2026)

- Upgraded `androidx.activity:activity-compose` from `1.10.0` to `1.10.1` to fix the ActivityResult request-code crash raised when opening the CSV/PDF document picker (`only the lower 16 bits ... requestCode`).

## Journal data-quality and learning fix (16 October 2026)

- CSV exports now include explicit price-distance, signed price-move, instrument-unit, directional-risk-validity, and learning-eligibility fields. The legacy `*_pips` columns remain for compatibility, but stock values are explicitly labeled as ticks.
- Neutral broker-only records (`BROKER_SYNC` + `OTHER` + score/rating 50) remain visible in the journal but no longer train setup memory as if they were identified strategies.
- Win and loss rating weights are now symmetric, preventing a neutral 50/50 result from drifting downward solely because losses previously carried more weight than wins.
- Scanner candidates with reward/risk above 1:10 are rejected as likely malformed/stale imports, preventing implausible brackets such as 1:59 from entering the learning or execution queue.

## Fresh-quote short-bracket and quantity handling (15 October 2026)

- Valid scanner stop/target distances are now re-anchored to the fresh broker entry quote before submission. This prevents a stale SHORT stop below the new entry, such as `stop=155.51` with `entry=159.61`, while preserving the original directional geometry and R:R.
- Invalid scanner levels are no longer exposed as executable stop/target fields when no quote-valid candidate exists.
- Automatic protective-stop repair now re-reads broker position availability after canceling reservations and refuses to submit a stop if the full position quantity is not available, avoiding misleading insufficient-quantity requests.

## Automatic Paper protective-stop repair (15 October 2026)

- Added a guarded Paper-only repair before manual confirmation, background scans, and Submit-all processing. When an open position has no single quantity-covering working stop, the app cancels affected stale stop legs, verifies cancellation, and submits one full-quantity GTC stop at the position's average entry price (break-even).
- Broker state is refreshed after repair; execution continues only if the new stop is accepted and the position can be freshly verified. Live accounts remain non-mutating in this build.
- Repair failures remain fail-closed and are surfaced in the developer diagnostics with the repaired symbol, quantity, and stop price.

## Zero-filled pending-order risk preflight fix (15 October 2026)

- An unfilled legacy/simple limit order such as COPX no longer makes every unrelated symbol fail the current-position stop-risk check. Orders with an explicit `filled_qty=0` have no current position stop risk; their remaining notional is still checked by the separate exposure and duplicate-order guards.
- The same rule applies when a broker returns an incomplete `bracket`/`OTO` response for an order that is still completely unfilled; the app no longer relies on missing child metadata to block unrelated symbols.
- Partially filled orders, orders with ambiguous fill state, and unprotected positions remain fail-closed until broker protection can be verified.
- Added regression coverage for zero-filled simple/bracket entries and partially filled unprotected entries.

## 1:1 reward-risk acceptance and quote-valid setup selection (15 October 2026)

- Lowered the shared scanner and opening-bracket minimum from 1.5R to **1.0R**. A 1:1 setup can now qualify when its directional entry, stop, target, market-data, score, memory, Paper-only, and portfolio-risk gates pass.
- The scanner now prefers the highest-scoring candidate whose preserved stop-loss and take-profit remain valid against the latest quote, reducing false stop-side errors when a different timeframe's stale bracket is selected.
- Live execution restrictions, fresh-quote validation, risk sizing, exposure limits, and fail-closed stop/target checks remain unchanged.
- Updated UI diagnostics and regression tests to report and verify the 1:1 boundary.

**Date:** 13 October 2026  
**Scope:** source-level remediation of the supplied Android project. No broker credentials were used, and no order was submitted.

## What changed

### Tasks 1–3: geometry, portfolio risk, and market-data quality

- `app/src/main/java/com/alpacatalk/trading/data/TradeGeometry.kt` — new directional geometry validator and whole-share sizing. Uses exact scanner stop/target, current entry quote, default 0.5% equity stop-risk budget, 1% hard maximum, minimum 1.5R, whole-share floor, and wrong-side validation.
- `app/src/main/java/com/alpacatalk/trading/data/MarketDataQuality.kt` — new 4-timeframe coverage, 20 completed bars per timeframe, quote age/spread, data-error/halt result, forming-bar exclusion, and U.S. regular-session policy.
- `app/src/main/java/com/alpacatalk/trading/data/PortfolioRiskPolicy.kt` — new separate percent-of-equity stop-risk and gross-notional checks, freshness validation, and exposure models.
- `app/src/main/java/com/alpacatalk/trading/data/DirectAlpacaClient.kt` — scanner now excludes forming bars, computes SMA-seeded EMA, does not award missing-volume points, describes lookback VWAP, retains stop/target and score-component provenance, validates active/tradable assets, and submits scanner geometry using fresh two-sided quotes. Live mutation calls are rejected.
- `app/src/main/java/com/alpacatalk/trading/DirectTradingViewModel.kt` — one shared manual/auto execution route; fresh clock/account/position/order reads before Paper orders; rechecked stop risk and notional; same-symbol exposure guard; regular-session/live restriction; persisted client-order intent lookup; exact scan bracket levels and dynamic sizing. Device-persisted per-trade risk choices (0.25/0.50/0.75/1.00% equity) default to 0.50% and enforce a 1% hard ceiling.
- `app/src/main/java/com/alpacatalk/trading/data/TradeEligibilityRules.kt` — fail-closed data quality and explicit Paper-only eligibility.
- `app/src/main/java/com/alpacatalk/trading/data/DuplicateExposurePolicy.kt` and `app/src/test/java/com/alpacatalk/trading/data/DuplicateExposurePolicyTest.kt` — extracted same-symbol position/working-order blocks with focused tests.
- `app/src/test/java/com/alpacatalk/trading/data/MarketDataQualityTest.kt`, `TradeRiskPolicyTest.kt`, and `ClientOrderRetryPolicyTest.kt` — new test coverage for quality, boundaries, sizing, exposure, snapshots, and retry-state decisions.

### Task 4: journal separation and learning sample control

- `app/src/main/java/com/alpacatalk/trading/data/TradeJournalModels.kt`, `ClosedTradeReconciler.kt`, `TradeJournalStore.kt`, and `TradeJournalEngine.kt` — account-mode journal files; migrate legacy Paper rows to Paper only; record manual-edit provenance; exclude manual records from live replay; require 20 outcomes before positive memory can add a rating bonus or `PREFER` directive.
- `app/src/test/java/com/alpacatalk/trading/TradeJournalEngineTest.kt` — migration, manual exclusion, and low-sample/20-sample behavior tests.

### Task 5 and 5B: explanation cards and scrolling

- `app/src/main/java/com/alpacatalk/trading/ui/DirectTradingApp.kt` — detailed score inputs; distinct Signal quality, Evidence confidence (`UNVALIDATED`), and Execution eligibility text; sample counts, timeframe coverage, quote recency/spread, stop/target, and explicit Paper-test-only caveat. Fixed scanner navigation, section heading and filters above independently scrolling results/trades/orders lists. Corrected order-confirmation and security language; live order messaging is no longer advertised as eligible.
- `app/src/test/java/com/alpacatalk/trading/TradeEligibilityRulesTest.kt` — updated Paper and hard-Live-block eligibility tests.

### Task 5C–5E: path parity, dynamic risk, exposure

- Both manual and auto scan selections call `executeBestSetupNow`; the candidate source does not fork order construction or risk logic.
- `OpenStopRiskCalculatorTest.kt` — added changed-equity, multiple-position, and tighter/trailing-stop risk recalculation test. The execution path requests new broker stop/position state per order.
- `TradeRiskPolicyTest.kt` — coverage for $100k equity and two $5k notionals plus a $5k proposal; over-limit notionals remain blocked independently of loss-at-stop risk.
- `DirectTradingViewModel.kt` + `DirectAlpacaClient.kt` — persistent client-order IDs and broker lookup are checked before resubmission; uncertain order lookup blocks retry.

### Paper-only safety, credentials, and docs

- Alpaca Live order placement/cancel/close and OANDA Live order/cancel routes now reject mutation calls in this build; the ViewModel and eligibility layer also block Live orders. Read-only account/environment probing remains possible.
- `app/src/main/java/com/alpacatalk/trading/data/CredentialVault.kt` — AES-GCM Keystore key now requires recent device authentication; failed authenticated decrypt/key invalidation removes the unreadable saved payload and returns a recovery message.
- `DirectTradingApp.kt` — device authentication is required to save/unlock remembered credentials; screenshot/recording disclosure now matches actual behavior (allowed, not blocked).
- `README.md` — rewritten to accurately describe direct broker routing, session-memory and optional encrypted credential persistence, screenshot policy, Paper-only build restriction, and non-probabilistic scanner scores.
- `../DOW-NDAQ-NOTES.md` — appended a notice clarifying that older one-share/fixed-bracket notes are historical and superseded.

## Verification

Command:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/home/ubuntu/android-sdk \
ANDROID_SDK_ROOT=/home/ubuntu/android-sdk \
./gradlew testDebugUnitTest assembleDebug --no-daemon
```

**Result:** BUILD SUCCESSFUL; **98 tests, 0 failures, 0 errors, 0 skipped**; debug APK assembled. `git diff --check` passed. The compiler reports a deprecation warning for Android's `setUserAuthenticationValidityDurationSeconds` API.

## Known incomplete requirements / remaining risks

- Task 5B code is implemented: scanner nested tabs and trades/orders filters/section titles are pinned above scroll-only content. Physical-device long-list testing was not possible in the sandbox.
- Task 5C lacks an end-to-end mock asserting exact manual/auto broker request equality, although both paths share the same function.
- No injected broker API-failure test verifies all order preflight failures; the policies fail closed in code and unit tests cover the deterministic checks.
- The per-trade risk budget has a user-facing persistent selector. Spread and gross-notional limits remain fixed policy defaults, not preferences. Halt state is not sourced into the scanner card; broker asset tradability is checked at submission.
- No trailing-stop modify feature exists. External stop edits are reflected in a subsequent fresh account/order refresh or submission risk recalculation, not an event-driven immediate push.
- The per-account/symbol idempotency record uses local preferences; cross-device/reinstall coordination is not solved.
- Task 6 is incomplete: no time-decay and no normalized net R-multiple-after-costs learning layer.
- Task 7 has credential/documentation changes, but no biometric/device-auth instrumentation test.
- Task 8 backtester and score-bucket report were not implemented. Therefore the strategy remains **unvalidated**, not an asserted profitable edge.
- No Android device, biometric hardware, live data, or broker endpoint was used. The debug APK is signed with the standard Android debug certificate and is not a production release.


## Screenshot follow-up (13 October 2026)

- `DirectTradingViewModel.kt` now evaluates Alpaca clock age at response receipt, not before the request. This avoids rejecting a fresh broker timestamp merely because it was produced after the request began. It logs the broker open flag/timestamp/age, distinguishes closed/missing/stale reasons, and presents local preflight failures as **Execution blocked** rather than **Order rejected**.
- New `BrokerClockPolicy.kt` and `BrokerClockPolicyTest.kt` cover fresh response timing, closed/missing/stale clocks, and future device-clock skew.
- `DirectTradingApp.kt` moves the Paper Execute button directly below scan options, shows selected count, and removes the distant duplicate action. The action is disabled for Live accounts and when nothing is selected.
- **Re-verified:** `testDebugUnitTest assembleDebug` succeeds; **100 tests, 0 failures**; `git diff --check` passes. Screenshot local time 15:55:23 at UTC+02 converts to 09:55:23 New York, within regular hours that day. No broker request was made to the user's account; an independent account/API availability issue could still block execution.


## Auto-scan performance and alert consolidation (13 October 2026)

The auto monitor regressed to 16 sequential ten-symbol calls for the 156-symbol universe. Per call it also issued three extra controlled diagnostic probes and produced a separate notification. The monitor now launches the same request-limited chunks with a maximum of four concurrent workers, runs parsing off the UI dispatcher, skips repeated controlled probes only for auto scans, combines all results and queue updates, then sends one consolidated notification after the full sweep. Manual scans retain their diagnostic probes. Partial failed chunks are reported and retried; order execution remains separate and manual-confirmation-only.

`ScannerBasketsTest` verifies exact unique-universe chunk coverage and the concurrency setting. **Final check:** `testDebugUnitTest assembleDebug` succeeds; **100 tests, zero failures**; `git diff --check` passes. No live API timing benchmark was run; real completion time depends on device connectivity and broker latency. The scan log records total and per-chunk elapsed time so the user can verify their Paper-account run.


## Follow-up: stop sides, notional sizing, and scanner UI (13 October 2026)
- Protective bracket directions are now explicit: BUY/LONG stop below entry and target above; SELL/SHORT protective stop above entry and target below. Directional scanner tests cover both; error messages identify the correct side.
- Gross exposure no longer double-counts filled shares from partially filled orders or normal opposite-side closing orders. It counts the remaining top-level order quantity, excluding linked bracket legs, and adds only reversal excess for oversized opposite orders.
- Final quantity is capped by remaining 80%-of-equity gross-notional capacity using the fresh entry-side quote, as well as the existing stop-risk budget. If exposure headroom is too small for one share, execution is blocked. Diagnostics include equity, current gross exposure, headroom, risk-sized vs final quantity, and proposed notional.
- Moved the all-basket scan monitor start/stop action below the pinned scanner tabs. Terminal panels show the latest 100 lines in a 140dp scrollable area; individual rows are capped at four lines and Copy preserves the full retained log.
- Added unit coverage for exposure-limited sizing, long/short stop geometry, closing orders, and oversized reverse orders. **Final verification:** `testDebugUnitTest assembleDebug` succeeds; **103 tests, zero failures/errors/skips**; `git diff --check` passes. No live account request was made, so the exact prior exposure snapshot was not independently retraced.

## Interrupted-build follow-up (13 October 2026)
- Added a separate **Manual confirm** tab on the scanner screen. It retains manual selections independently from Auto-scan findings and Auto confirm; switching tabs never submits an order.
- Manual and auto confirmation continue to call the same guarded `executeBestSetupNow` route.
- Added a final direction-specific guard immediately before the Alpaca payload: BUY/LONG requires `stop < entry < take-profit`; SELL/SHORT requires `take-profit < entry < protective stop-loss`. Invalid payloads are blocked locally with the exact levels and **no order is sent**.
- **Verification:** `testDebugUnitTest assembleDebug` succeeds with the supplied Android project; debug APK assembled. No broker credentials or broker requests were used.

## Paper extended-hours support (13 October 2026)
The approved all-extended-hours option is implemented for Paper execution. Paper orders may now be submitted Monday through Friday from **04:00 through 20:00 America/New_York**, covering pre-market, regular session, and after-hours. Outside that window, weekends, and holidays not represented by the broker clock, execution remains blocked. During the extended portion, a fresh Alpaca clock timestamp is still required, but `is_open=false` is accepted because Alpaca's clock reports regular-session status; the order payload includes `extended_hours=true`. Live order mutations remain disabled.

**Verification:** `testDebugUnitTest assembleDebug` succeeded and the debug APK was assembled. No broker credentials or broker requests were used.

## Correction: bracket extended-hours compatibility (13 October 2026)
The earlier Paper extended-hours change was reverted for bracket execution. Alpaca does not support `extended_hours=true` on bracket orders, so the app no longer sends that flag and now blocks Paper bracket confirmation outside the regular 09:30–16:00 America/New_York weekday session before making broker account or order requests. Extended-hours scan findings remain visible but are explicitly scan-only. A separate limit-entry plus post-fill protective-exit workflow would be required to support extended-hours execution safely.

**Verification:** `testDebugUnitTest assembleDebug` succeeded; no broker credentials or broker requests were used.

## Short bracket geometry investigation and fix (14 October 2026)
The short/SELL message came from `TradeGeometry.plan`, which was validating the preserved scanner stop and target against the scan-time entry. The scanner generated directional prices correctly, but the UI marked a candidate executable based only on strategy and market-data checks. At execution, a newer broker quote can move below the preserved short take-profit, making that target invalid for the new entry; the app then failed with a generic directional error. A SELL that reaches this opening-bracket path is treated as an opening short because existing same-symbol exposure is blocked before order construction; closing orders are not routed through this path.

The authoritative `TradeGeometry.validationError` function now validates the direction and all three numeric prices with detailed diagnostics. Scanner snapshots use it before marking a candidate executable, and the execution path logs the source, direction, scan entry, stop, target, and validation result before risk sizing. The scanner card now shows **BLOCKED** instead of **PASS** for invalid geometry. No prices are silently rebased or modified. The 34.46 short fixture, valid long/short geometry, invalid sides, non-positive prices, and a scan-to-new-quote movement case are covered by regression tests.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 110 tests and zero failures. The exact screenshot payload could not be replayed because the attached note contained the error text but not the numeric stop/target values; the new diagnostics will capture those values on the next reproduction. No broker credentials or broker requests were used.

## Automatic reward-risk scan filter (14 October 2026)
Scanner reward-risk is calculated from the selected entry, stop-loss, and take-profit geometry. The scanner now requires a minimum **1:5 reward-risk ratio (5.0R)** for a finding to be retained. Candidates below 5.0R are excluded before manual scan results, auto-scan findings, alerts, or confirmation queues. Retained findings expose the computed value as `auto R:R 1:x.xx`, and diagnostics record the raw-versus-retained count and threshold. Existing execution risk controls remain active; prices are not altered to force a pass.

The minimum is intentionally applied at scan retention, not by changing stop distances or target prices. A 3.2R setup is calculated and can appear in internal diagnostics as `rawSnapshot`, but it is excluded from the returned scan findings and cannot enter the alert or confirmation queue. The attached open-stop-risk issue remains a separate fail-closed broker-state investigation; no risk verification was bypassed for this reward-risk change.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 110 tests and zero failures. No broker credentials or broker requests were used.

## Open stop-risk reconciliation fix (14 October 2026)
The attached MDY failure came from the top-level working-order guard in `OpenStopRiskCalculator`: every active entry or unclassified order caused the entire account risk snapshot to become unverified, so after one successful bracket created a working parent, later unrelated symbols were blocked. A broker-confirmed working `bracket` or `oto` parent is now accepted only when its response contains a positive protective stop in `stop_loss` or a stop leg. Its pending entry is not counted as a filled-position stop-risk line. Standalone working entries, unknown order classes, missing/invalid stops, and ambiguous records remain fail-closed.

Execution now records non-sensitive reconciliation diagnostics before the risk gate: selected Paper environment, position/order counts, symbol, broker order ID or client ID, status, side, type, quantity, filled quantity, remaining quantity, stop price, calculated downside risk, verification state, and exact issue. The successful-first-order scenario is covered by protected-bracket and unprotected-bracket regression tests. No broker account was accessed, so the actual MDY order ID/state from the user’s account could not be independently confirmed in this environment.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 112 tests, zero failures/errors/skips. No credentials or broker requests were used.

## Scanner results pipeline correction (14 October 2026)
The first 1:5 implementation incorrectly removed sub-5R raw snapshots from `BatchScanResult`, which made both manual and automatic scan screens appear empty. The pipeline now returns raw scanner findings for display and diagnostics. The 1:5 rule still controls `isExecutableSetup`, shared eligibility, notifications, auto-confirm queues, and order execution: below-5R setups can be visible as blocked findings but cannot become actionable results or orders.

## Background scan monitor with notifications (14 October 2026)
The approved background-monitor feature is implemented as an Android foreground service. Starting the monitor now persists an enabled flag, launches `BackgroundScanService`, and does not start the old ViewModel-bound scan coroutine, preventing duplicate scans. The service keeps a low-priority ongoing status notification, refreshes broker account/positions/open orders, fails closed when stop-risk cannot be verified, scans the full basket every 60 seconds, and sends a setup notification when the qualifying setup signature changes. It never submits, replaces, cancels, or manages broker orders. Stopping the monitor or disconnecting disables the flag and stops the service.

Credential storage now uses a device-protected Android Keystore AES-GCM key that does not require a fresh unlock while the phone is locked. Existing credentials encrypted by the former foreground-only key are migrated to the background-capable key when the user unlocks them in the UI; if migration cannot be authenticated, the service fails closed and asks the user to reconnect/save credentials. The UI requires Remember credentials to be enabled before starting the background monitor. Android 13+ notification permission and system notification settings must remain enabled.

The manifest declares the Android foreground service and `dataSync` service type. The background service is read-only and independent of the screen/task lifecycle; Android device/OEM battery restrictions may still pause or terminate background work, so the ongoing notification is the visible monitor status. It does not automatically restart after a device reboot in this build.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 112 tests, zero failures/errors/skips.

## Notification Submit all Paper orders (14 October 2026)
Qualifying setup notifications now include an explicit **Submit all qualifying Paper orders** action. The action launches the foreground service without opening the app. It never trusts the earlier notification snapshot: it checks the regular U.S. market clock, reads fresh Paper account/position/open-order state, verifies stop risk and notional capacity, rescans the full universe, and re-evaluates the 1:5/actionability gates. It then processes qualifying setups sequentially, refreshing broker state before each order and reporting submitted and skipped symbols in a follow-up notification. Live order submission remains disabled, and the action cannot cancel or replace existing orders. Orders may still be skipped if quotes, shortability, geometry, exposure, risk, market session, or any other gate changes.

The button is a direct confirmation action as explicitly approved by the user; tapping it is the user's order-confirmation event. Android notification permission and the persistent monitor must remain enabled. The result notification reports the exact submitted/skipped outcome.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 112 tests, zero failures/errors/skips.

## Scanner reward-risk threshold changed to 1:2.25 (14 October 2026)
The active scanner/actionability threshold is now **1:2.25** (`2.25R`). Automatically calculated reward-risk values below 2.25 are excluded from actionable eligibility, notifications, auto-confirm queues, and notification-based Submit all processing. Values at or above 2.25 pass this gate. Existing bracket geometry validation remains separate, with its general 1.5R lower bound; the scanner threshold is the stricter user-configured filter.

UI text, diagnostics, shared eligibility messaging, and regression tests now use the 1:2.25 threshold. The scanner still reports raw findings for diagnostics while preventing below-threshold setups from becoming actionable.

## Auto scanner and notification correction (14 October 2026)
Compared the manual and auto pipelines and corrected two regressions. The ViewModel auto-scan loop had been left disabled after the foreground service was introduced, so the Auto-scan findings and Auto confirm tabs could not receive results while the app was open. It is active again while the app is visible. The foreground service now yields while the app is visible and takes over after the app is closed, preventing duplicate scans. Its full-universe service scan now uses the same four-way parallel basket batching as the manual/in-app auto pipeline instead of scanning all 16 baskets serially.

Both in-app and background qualifying setup notifications now include the explicit **Submit all Paper orders** action. The action label was shortened for compact Android notification layouts. The reward-risk eligibility error now formats its threshold from the single active constant, preventing contradictory messages such as a 2.00R setup being described as below a different threshold. Base scalp/swing strategy thresholds are labeled separately from the active actionable 2.25R filter.

The active threshold remains 1:2.25 pending clarification of the user's later “1:50” wording.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 113 tests, zero failures/errors/skips.

## Scanner reward-risk threshold set to 1:1.5 (14 October 2026)
Per the user's clarification, the active scanner/actionable minimum is now **1:1.5** (`1.50R`). The scanner, shared eligibility gate, notification filtering, Submit all revalidation, UI text, and diagnostics all use the same single threshold constant. The eligibility error formats the threshold numerically from that constant, so a setup at 2.00R cannot be reported as below 1.25R or any other unrelated value. Regression coverage verifies that 1.50R passes and 1.49R is blocked.

## Final scanner threshold clarification: 1:1.5 (14 October 2026)
The user clarified that the intended active reward-risk threshold is **1:1.5**. The scanner computes R:R as reward distance divided by stop-risk distance, using directional geometry: LONG `(target - entry) / (entry - stop)` and SHORT `(entry - target) / (stop - entry)`. The same calculated value is compared to the single 1.50 threshold everywhere. Therefore 2.00R is correctly greater than 1.50R and cannot be reported as below the threshold. Boundary tests verify 1.50 passes and 1.49 fails; directional fixture tests were updated because their calculated values now correctly qualify.

**Verification:** `testDebugUnitTest assembleDebug` succeeded with 113 tests, zero failures/errors/skips.
