# Fin Killer Pro — Android direct-broker client

Fin Killer Pro is a Kotlin/Jetpack Compose trading application that connects **directly from the Android device** to the selected broker APIs. The supplied Android source does not route orders through a private Fin Killer backend. Broker credentials are sent from the app to the relevant broker endpoints over HTTPS.

The app contains Alpaca, OANDA, and cTrader connection paths. Alpaca paper versus live environment is detected from credentials; OANDA and cTrader present practice/demo and live choices. **This source-level review did not connect to a broker or submit any order.** Do not use live credentials while validating changes; use the broker's paper/demo environment.

## Build and test

- Android Studio with JDK 17+
- Android SDK Platform 35
- Android device/emulator supported by `minSdk` in `app/build.gradle.kts`

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is unsigned unless release-signing settings are supplied in a secure build environment. No credentials belong in source control, test fixtures, build output, or logs.

## Credential handling

- Credential fields use password-style visual transformation. Values are sent to the selected broker over HTTPS; the current connection's credentials remain in the ViewModel's process memory until disconnect or connection failure.
- **Remember encrypted credentials** is optional. When selected, saving or unlocking requires Android biometric or device-credential authentication. The vault encrypts values with AES-GCM and an Android Keystore AES key that requires recent device authentication. If Android invalidates the key after a device-security change, the app removes the unreadable saved payload and asks the user to re-enter credentials; it does not print credential values.
- Uncheck the remember option to use credentials only for the current session. Disconnect to clear the active broker credential fields from the ViewModel.
- **Screenshots and screen recording are not blocked** by this app. Avoid capturing or sharing the credential-entry screen, account identifiers, or other sensitive account data.
- Cleartext network traffic is disabled in the Android network-security configuration. This does not replace broker-side key management or device security.

## Scanner and execution notes

- The scanner scores binary technical conditions. Its 0–100 score is a checklist score, **not** a win probability or a validated confidence estimate.
- The app rating blends the current scanner score with deterministic replay of journal history. Journal memory is not a backtest and is not out-of-sample validation.
- The read-only auto monitor scans all 156 unique basket symbols as one sweep. The broker's 10-symbol request limit is handled internally using up to four concurrent chunks, repeated controlled diagnostics are skipped during the sweep, and one consolidated notification is issued after all chunks finish. Actual wall time still depends on device connectivity and broker/API response time; the app logs elapsed time and failures.
- Account detection can identify Paper or Live credentials, but order placement/cancel/close mutations are deliberately blocked for Live in this build. Use a Paper account to validate the scanner workflow; source-level unit tests do not exercise broker endpoints.
- Forex/cTrader routes are separate broker paths and must not be assumed to share Alpaca's equity-order constraints.

For a detailed analysis of the scoring rules and implementation changes, see `FinKiller_Deep_Dive.md` and `CHANGELOG.md` in the delivered audit package.
