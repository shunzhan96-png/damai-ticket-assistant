package com.example.damaiticketassistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lightweight, read-only monitor for the visible Damai UI.
 * Event-driven: no periodic full-window polling, no clicks, no order submission.
 */
class DamaiPageMonitorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastState: String = ""
    private var pendingInspection: Runnable? = null
    private var periodicInspection: Runnable? = null
    private var lastInspectionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val enabled = getSharedPreferences("damai_assistant", MODE_PRIVATE)
            .getBoolean("monitor_enabled", false)
        saveStatusIfChanged(if (enabled) "辅助任务已启用；等待大麦页面变化" else "页面识别服务已连接；辅助任务尚未启动")
        if (enabled) {
            scheduleInspection()
            schedulePeriodicInspection()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!getSharedPreferences("damai_assistant", MODE_PRIVATE).getBoolean("monitor_enabled", false)) return
        if (event.packageName?.toString() != DAMAI_PACKAGE) return

        // Coalesce bursts of content events. Inspect once after the UI settles.
        scheduleInspection()
    }

    override fun onInterrupt() {
        saveStatusIfChanged("页面识别服务被系统中断")
    }

    override fun onDestroy() {
        pendingInspection?.let(handler::removeCallbacks)
        pendingInspection = null
        periodicInspection?.let(handler::removeCallbacks)
        periodicInspection = null
        super.onDestroy()
    }

    private fun scheduleInspection() {
        pendingInspection?.let(handler::removeCallbacks)
        val prefs = getSharedPreferences("damai_assistant", MODE_PRIVATE)
        val eventId = prefs.getInt("active_event_id", -1)
        val intervalSeconds = prefs.getInt("check_interval_$eventId", DEFAULT_CHECK_INTERVAL_SECONDS)
            .coerceIn(MIN_CHECK_INTERVAL_SECONDS, MAX_CHECK_INTERVAL_SECONDS)
        val elapsed = System.currentTimeMillis() - lastInspectionAt
        val delay = maxOf(INSPECTION_DEBOUNCE_MS, intervalSeconds * 1000L - elapsed)
        val task = Runnable {
            pendingInspection = null
            inspectCurrentWindow()
        }
        pendingInspection = task
        handler.postDelayed(task, delay)
    }

    private fun schedulePeriodicInspection() {
        periodicInspection?.let(handler::removeCallbacks)
        val task = Runnable {
            periodicInspection = null
            if (!getSharedPreferences("damai_assistant", MODE_PRIVATE)
                    .getBoolean("monitor_enabled", false)) {
                saveStatusIfChanged("辅助任务已停止；页面识别暂不执行")
                return@Runnable
            }
            val root = rootInActiveWindow
            val isDamaiVisible = root?.packageName?.toString() == DAMAI_PACKAGE
            root?.recycle()
            if (isDamaiVisible) scheduleInspection()
            schedulePeriodicInspection()
        }
        periodicInspection = task
        val prefs = getSharedPreferences("damai_assistant", MODE_PRIVATE)
        val eventId = prefs.getInt("active_event_id", -1)
        val intervalSeconds = prefs.getInt("check_interval_$eventId", DEFAULT_CHECK_INTERVAL_SECONDS)
            .coerceIn(MIN_CHECK_INTERVAL_SECONDS, MAX_CHECK_INTERVAL_SECONDS)
        handler.postDelayed(task, intervalSeconds * 1000L)
    }

    private fun inspectCurrentWindow() {
        lastInspectionAt = System.currentTimeMillis()
        getSharedPreferences("damai_assistant", MODE_PRIVATE).edit()
            .putLong("page_monitor_updated_at", lastInspectionAt)
            .apply()
        val root = rootInActiveWindow
        if (root == null) {
            saveStatusIfChanged("等待大麦窗口；请先打开大麦App并保持在前台")
            return
        }
        if (root.packageName?.toString() != DAMAI_PACKAGE) {
            val currentPackage = root.packageName?.toString().orEmpty()
            root.recycle()
            saveStatusIfChanged(
                if (currentPackage.isBlank()) "当前没有可读取的活动窗口"
                else "当前前台不是大麦页面（$currentPackage）；请打开目标演出页面"
            )
            return
        }

        val text = StringBuilder()
        collectVisibleText(root, text, 0, intArrayOf(0))
        root.recycle()
        val pageText = text.toString()
        if (pageText.isBlank()) {
            saveStatusIfChanged("已打开大麦页面，但当前页面没有可读取的文字")
            return
        }

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
        saveStatusIfChanged(state)
    }

    private fun saveStatusIfChanged(status: String) {
        if (status != lastState) {
            lastState = status
            getSharedPreferences("damai_assistant", MODE_PRIVATE).edit()
                .putString("page_monitor_status", status)
                .apply()
        }
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        output: StringBuilder,
        depth: Int,
        count: IntArray
    ) {
        if (depth > MAX_DEPTH || count[0] >= MAX_NODES || output.length >= MAX_TEXT_CHARS) return
        count[0]++
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            if (output.length < MAX_TEXT_CHARS) output.append(it).append(' ')
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            if (output.length < MAX_TEXT_CHARS) output.append(it).append(' ')
        }
        for (i in 0 until node.childCount) {
            if (count[0] >= MAX_NODES || output.length >= MAX_TEXT_CHARS) break
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, output, depth + 1, count)
            child.recycle()
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean =
        terms.any { text.contains(it, ignoreCase = true) }

    companion object {
        private const val DAMAI_PACKAGE = "cn.damai"
        private const val INSPECTION_DEBOUNCE_MS = 450L
        private const val DEFAULT_CHECK_INTERVAL_SECONDS = 2
        private const val MIN_CHECK_INTERVAL_SECONDS = 1
        private const val MAX_CHECK_INTERVAL_SECONDS = 5
        private const val MAX_NODES = 100
        private const val MAX_DEPTH = 12
        private const val MAX_TEXT_CHARS = 3000
    }
}
