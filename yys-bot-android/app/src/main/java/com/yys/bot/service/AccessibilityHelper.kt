package com.yys.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay

/**
 * 无障碍服务：提供全局点击/滑动（dispatchGesture）。
 * 注意：AccessibilityService 只能注入 BACK/HOME/RECENTS，普通按键（Q/W/数字）
 * 需改用「点击屏幕虚拟按键模板」的方式实现。
 */
class AccessibilityHelper : AccessibilityService() {

    companion object {
        private const val TAG = "YysBot/A11y"

        @Volatile
        var instance: AccessibilityHelper? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 点击 (x, y)，坐标基于屏幕像素。 */
    suspend fun tap(x: Int, y: Int, holdMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
        delay(holdMs + 60)
        return true
    }

    /** 从 (x1,y1) 滑到 (x2,y2)。 */
    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = 400L
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
        delay(durationMs + 60)
        return true
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * 找出「非本应用」的顶层窗口所在屏幕 id。
     * MediaProjection 只能镜像默认屏幕（0），而 MuMu 等模拟器的分身/多屏
     * 会把游戏放到 display 10/45 之类的独立屏幕，导致截图与游戏不同屏。
     */
    fun foreignWindowDisplayId(): Int? {
        val list = runCatching { windows }.getOrNull() ?: return null
        for (w in list) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            root.recycle()
            if (pkg != null && pkg != packageName) return w.displayId
        }
        return null
    }

    /** 当前前台应用包名（用于确认游戏是否已切到前台）。 */
    fun foregroundPackage(): String? {
        val list = runCatching { windows }.getOrNull() ?: return null
        for (w in list) {
            if (!w.isActive) continue
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            root.recycle()
            if (pkg != null) return pkg
        }
        return null
    }
}
