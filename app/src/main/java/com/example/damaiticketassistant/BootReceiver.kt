package com.example.damaiticketassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("damai_assistant", Context.MODE_PRIVATE)
        val raw = prefs.getString("events", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val time = e.optLong("saleTime", 0L)
                val id = e.optInt("id", i + 1)
                if (time > System.currentTimeMillis()) {
                    val title = e.optString("title", "演出")
                    val earlyTime = time - 10 * 60 * 1000L
                    if (earlyTime > System.currentTimeMillis()) {
                        AlarmScheduler.schedule(context, id, title, earlyTime)
                    }
                    AlarmScheduler.schedule(context, id, title, time, true)
                }
            }
            prefs.edit().putString("log_last", "手机重启后已检查并恢复提醒").apply()
        } catch (_: Exception) {
            prefs.edit().putString("log_last", "重启恢复失败：演出数据格式异常").apply()
        }
    }
}