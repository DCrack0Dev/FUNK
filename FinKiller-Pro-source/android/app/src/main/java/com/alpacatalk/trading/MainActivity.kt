package com.alpacatalk.trading

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.alpacatalk.trading.ui.AlpacaTradingTheme
import com.alpacatalk.trading.ui.DirectTradingApp

class MainActivity : FragmentActivity() {
    companion object {
        // Android startActivityForResult accepts only the lower 16 bits of requestCode.
        private const val JOURNAL_EXPORT_REQUEST_CODE = 0x4A31
    }

    private val tradingViewModel: DirectTradingViewModel by viewModels()
    private var pendingJournalExport: ByteArray? = null

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

    fun launchJournalExport(mimeType: String, filename: String, bytes: ByteArray) {
        pendingJournalExport = bytes
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        startActivityForResult(intent, JOURNAL_EXPORT_REQUEST_CODE and 0xFFFF)
    }

    @Deprecated("Uses the fixed Android document-picker request code for compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != (JOURNAL_EXPORT_REQUEST_CODE and 0xFFFF)) return
        val bytes = pendingJournalExport
        pendingJournalExport = null
        if (resultCode != Activity.RESULT_OK || data?.data == null || bytes == null) return
        runCatching {
            contentResolver.openOutputStream(data.data!!)?.use { it.write(bytes) }
                ?: error("Could not open the selected file.")
        }.onSuccess {
            Toast.makeText(this, "Journal file saved", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(this, "Journal export failed: ${error.message ?: "write error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleCtraderCallback(intent: Intent?) {
        if (intent?.data?.scheme == "ctrader" && intent.data?.host == "oauth") {
            intent.data?.getQueryParameter("code")?.let(tradingViewModel::completeCtraderAuthorization)
        }
    }
}
