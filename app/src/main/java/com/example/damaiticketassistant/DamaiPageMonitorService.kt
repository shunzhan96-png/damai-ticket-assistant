package com.example.damaiticketassistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Read-only monitor for the visible Damai UI.
 * It deliberately does not click controls, submit orders, or interact with captcha/queue/payment.
 */
class DamaiPageMonitorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastState: String = ""
    private var monitorRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduleMonitor()
        saveStatus("页面识别服务已启用；等待大麦页面显示")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == DAMAI_PACKAGE) {
            inspectCurrentWindow()
        }
    }

    override fun onInterrupt() {
        saveStatus("页面识别服务被系统中断")
    }

    override fun onDestroy() {
        monitorRunnable?.let(handler::removeCallbacks)
        monitorRunnable = null
        super.onDestroy()
    }

    private fun scheduleMonitor() {
        monitorRunnable?.let(handler::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                inspectCurrentWindow()
                val prefs = getSharedPreferences("damai_assistant", MODE_PRIVATE)
                val eventId = prefs.getInt("active_event_id", -1)
                val seconds = if (eventId > 0) prefs.getInt("check_interval_$eventId", 2) else 2
                handler.postDelayed(this, seconds.coerceIn(1, 5) * 1000L)
            }
        }
        monitorRunnable = task
        handler.post(task)
    }

    private fun inspectCurrentWindow() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DAMAI_PACKAGE) {
            root.recycle()
            return
        }
        val text = StringBuilder()
        collectVisibleText(root, text, 0, intArrayOf(0))
        root.recycle()
        val pageText = text.toString()
        if (pageText.isBlank()) return

        val prefs = getSharedPreferences("damai_assistant", MODE_PRIVATE)
        val targetTitle = prefs.getString("active_event_title", "").orEmpty().trim()
        val titleMatched = targetTitle.isNotBlank() && pageText.contains(targetTitle, ignoreCase = true)

        val state = when {
            containsAny(pageText, "验证码", "安全验证", "滑动验证", "人机验证") ->
                "检测到验证码/安全验证，请本人完成；自动操作已保持停止"
            containsAny(pageText, "排队中", "排队等待", "前方还有", "排队人数") ->
                "检测到官方排队页面，请按大麦页面提示等待"
            containsAny(pageText, "确认支付", "立即支付", "支付订单", "收银台") ->
                "检测到支付相关页面，请本人核对订单并完成支付"
            containsAny(pageText, "登录", "手机号登录", "验证码登录", "立即登录") ->
                "检测到登录相关页面，请本人登录"
            titleMatched && containsAny(pageText, "选座", "选择票档", "立即购买", "立即预订", "开售提醒", "缺货登记") ->
                "已匹配目标演出名称，且识别到演出操作控件：$targetTitle"
            titleMatched ->
                "已在大麦页面文本中匹配到目标演出名称：$targetTitle；请确认场次与票档"
            targetTitle.isNotBlank() ->
                "当前大麦页面未匹配目标演出名称“$targetTitle”；可能仍在首页或其他页面"
            containsAny(pageText, "选座", "选择票档", "立即购买", "确认订单", "提交订单") ->
                "检测到购票相关控件；当前版本仅识别，不会自动提交订单"
            else -> "已读取大麦当前页面；尚未匹配到明确状态"
        }
        if (state != lastState) {
            lastState = state
            saveStatus(state)
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, output: StringBuilder, depth: Int, count: IntArray) {
        if (depth > 20 || count[0] >= 500) return
        count[0]++
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            if (output.length < 12000) output.append(it).append(' ')
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            if (output.length < 12000) output.append(it).append(' ')
        }
        for (i in 0 until node.childCount) {
            if (count[0] >= 500 || output.length >= 12000) break
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, output, depth + 1, count)
            child.recycle()
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean =
        terms.any { text.contains(it, ignoreCase = true) }

    private fun saveStatus(status: String) {
        getSharedPreferences("damai_assistant", MODE_PRIVATE).edit()
            .putString("page_monitor_status", status)
            .putLong("page_monitor_updated_at", System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val DAMAI_PACKAGE = "cn.damai"
    }
}
