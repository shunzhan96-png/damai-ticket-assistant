package com.example.damaiticketassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "演出即将开售"
        val eventId = intent.getIntExtra("eventId", 0)
        val atSaleTime = intent.getBooleanExtra("atSaleTime", false)
        val atSaleTime = intent.getBooleanExtra("atSaleTime", false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("sale_reminders", "开售提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, eventId, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, "sale_reminders") else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        notification.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(if (atSaleTime) "现在开售" else "开售前10分钟提醒").setContentText(if (atSaleTime) "$title 已到开售时间，请进入官方页面" else "$title 即将开售，请提前进入官方页面准备").setContentIntent(open).setAutoCancel(true)
        nm.notify(eventId, notification.build())
        context.getSharedPreferences("damai_assistant", Context.MODE_PRIVATE).edit().putString("log_last", "通知已触发：$title").apply()
    }
}