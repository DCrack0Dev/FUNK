package com.alpacatalk.trading

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.alpacatalk.trading.ui.AlpacaTradingTheme
import com.alpacatalk.trading.ui.DirectTradingApp

class MainActivity : FragmentActivity() {
    private val tradingViewModel: DirectTradingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshots are intentionally allowed so the user can review and share the UI.
        enableEdgeToEdge()
        setContent {
            AlpacaTradingTheme {
                DirectTradingApp(tradingViewModel)
            }
        }
        handleCtraderCallback(intent)
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(BackgroundScanService.PREFS, MODE_PRIVATE).edit().putBoolean(BackgroundScanService.KEY_APP_VISIBLE, true).apply()
    }

    override fun onStop() {
        getSharedPreferences(BackgroundScanService.PREFS, MODE_PRIVATE).edit().putBoolean(BackgroundScanService.KEY_APP_VISIBLE, false).apply()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCtraderCallback(intent)
    }

    private fun handleCtraderCallback(intent: Intent?) {
        if (intent?.data?.scheme == "ctrader" && intent.data?.host == "oauth") {
            intent.data?.getQueryParameter("code")?.let(tradingViewModel::completeCtraderAuthorization)
        }
    }
}
