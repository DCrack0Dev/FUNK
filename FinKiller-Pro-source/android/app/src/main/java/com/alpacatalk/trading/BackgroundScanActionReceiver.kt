package com.alpacatalk.trading

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Handles the explicit notification action; the service performs fresh validation before any order. */
class BackgroundScanActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BackgroundScanService.ACTION_CONFIRM_ALL) return
        val serviceIntent = Intent(context, BackgroundScanService::class.java).apply {
            action = BackgroundScanService.ACTION_CONFIRM_ALL
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
