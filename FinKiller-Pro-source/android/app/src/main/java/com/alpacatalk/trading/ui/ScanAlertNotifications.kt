package com.alpacatalk.trading.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alpacatalk.trading.MainActivity

/** Local, read-only scan alerts; this class never invokes a broker order endpoint. */
internal object ScanAlertNotifications {
    private const val CHANNEL_ID = "qualifying_setups"
    private const val NOTIFICATION_ID = 7201
    const val MONITOR_CHANNEL_ID = "background_scan_monitor"
    const val MONITOR_NOTIFICATION_ID = 7202

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean {
        if (!hasPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun createChannel(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching false
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Qualifying trade setups",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts after scans when one or more setups pass the configured execution gates."
            }
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL_ID, "Background scan monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent status for the read-only background setup monitor."
            })
        }
        true
    }.getOrDefault(false)

    fun monitorNotification(context: Context, text: String, ongoing: Boolean = true): android.app.Notification? = runCatching {
        if (!createChannel(context)) return@runCatching null
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(context, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Fin Killer Pro background monitor")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }.getOrNull()

    fun showQualifyingSetups(
        context: Context,
        details: List<String>,
        scannedCount: Int,
        totalQualifyingCount: Int = details.size,
        includeSubmitAllAction: Boolean = false,
    ): Boolean = runCatching {
        if (details.isEmpty() || !notificationsEnabled(context)) return@runCatching false
        if (!createChannel(context)) return@runCatching false
        val content = details.joinToString("\n")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("$totalQualifyingCount qualifying setup${if (totalQualifyingCount == 1) "" else "s"} found")
            .setContentText(details.take(2).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSubText("$scannedCount markets scanned · review in Fin Killer Pro")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .apply {
                if (includeSubmitAllAction) {
                    val actionIntent = Intent(context, com.alpacatalk.trading.BackgroundScanActionReceiver::class.java).apply { action = com.alpacatalk.trading.BackgroundScanService.ACTION_CONFIRM_ALL }
                    val actionPendingIntent = PendingIntent.getBroadcast(context, 2, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(NotificationCompat.Action.Builder(0, "Submit all Paper orders", actionPendingIntent).build())
                }
            }
            .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    fun showActionResult(context: Context, title: String, details: List<String>): Boolean = runCatching {
        if (!notificationsEnabled(context) || !createChannel(context)) return@runCatching false
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title)
            .setContentText(details.take(2).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(details.joinToString("\n")))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 1, notification)
        true
    }.getOrDefault(false)

}
