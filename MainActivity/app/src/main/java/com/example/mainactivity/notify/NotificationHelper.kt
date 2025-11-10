package com.example.mainactivity.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mainactivity.R

object NotificationHelper {
    private const val CHANNEL_ID = "deepfake_alert_channel"

    fun notifyResult(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "딥페이크 경고", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "딥페이크/합성음 감지 알림" }
            nm.createNotificationChannel(ch)
        }

        val smallIcon = ctx.resources.getIdentifier("ic_warning", "drawable", ctx.packageName)
            .takeIf { it != 0 } ?: R.mipmap.ic_launcher

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), n)
    }
}
