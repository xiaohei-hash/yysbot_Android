package com.yys.bot.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.yys.bot.R
import com.yys.bot.util.LogBus

/**
 * 悬浮窗日志：显示运行日志，支持拖拽、收起/展开、清空、停止、关闭。
 *
 * 尺寸用显式像素（360dp x 400dp），避免 WRAP_CONTENT 随文本无限变大；
 * 日志只保留最近 MAX_LINES 行，防止内存与测量开销持续增长。
 */
class LogOverlayService : Service() {

    companion object {
        private const val TAG = "YysBot/Overlay"
        private const val MAX_LINES = 300
        private const val WIDTH_DP = 360
        private const val HEIGHT_DP = 400
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var logView: TextView? = null
    private var scrollView: ScrollView? = null
    private var foldButton: Button? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false

    private val logListener: (String) -> Unit = { line -> append(line) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        LogBus.register(logListener)
        LogBus.log("悬浮窗日志已启动")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** 窗口宽度：360dp，但在窄屏上不超过屏幕宽度 88%。 */
    private fun windowWidth(): Int {
        val screen = resources.displayMetrics.widthPixels
        return minOf(dp(WIDTH_DP), (screen * 0.88f).toInt())
    }

    private fun showOverlay() {
        // Service 上下文不带应用主题，MaterialCardView 会因主题校验失败而崩溃，
        // 必须用 ContextThemeWrapper 显式套上 MaterialComponents 主题。
        val themed = ContextThemeWrapper(this, R.style.Theme_YysBot_Overlay)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_log, null)
        rootView = view
        logView = view.findViewById(R.id.tvLog)
        scrollView = view.findViewById(R.id.svLog)
        foldButton = view.findViewById(R.id.btnFold)

        val lp = WindowManager.LayoutParams(
            windowWidth(),
            dp(HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(96)
        }
        params = lp

        val header = view.findViewById<View>(R.id.headerBar)
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        downX = event.rawX
                        downY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = startX - (event.rawX - downX).toInt()
                        p.y = startY + (event.rawY - downY).toInt()
                        runCatching { windowManager.updateViewLayout(view, p) }
                        return true
                    }
                }
                return false
            }
        })

        foldButton?.setOnClickListener { setCollapsed(!collapsed) }
        view.findViewById<Button>(R.id.btnClear).setOnClickListener { logView?.text = "" }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, BotService::class.java))
            LogBus.log("用户点击停止")
        }
        view.findViewById<Button>(R.id.btnExit).setOnClickListener { stopSelf() }

        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗添加失败: ${e.message}")
            stopSelf()
        }
    }

    /** 收起：只留标题栏；展开：恢复 400dp 高度。 */
    private fun setCollapsed(collapse: Boolean) {
        val view = rootView ?: return
        val p = params ?: return
        collapsed = collapse

        scrollView?.visibility = if (collapse) View.GONE else View.VISIBLE
        p.width = windowWidth()
        // 收起后高度固定为标题栏 44dp，避免 WRAP_CONTENT 在窗口层面测量不确定
        p.height = if (collapse) dp(44) else dp(HEIGHT_DP)
        runCatching { windowManager.updateViewLayout(view, p) }
        foldButton?.text = getString(if (collapse) R.string.unfold else R.string.fold)
    }

    private fun append(line: String) {
        // LogBus 可能从后台线程回调，TextView 只能在主线程更新
        val tv = logView ?: return
        tv.post {
            tv.append("$line\n")
            // 只保留最近 MAX_LINES 行，防止文本无限增长把窗口撑大 / 占内存
            if (tv.lineCount > MAX_LINES) {
                val text = tv.text
                var cut = 0
                var lines = 0
                for (i in text.indices) {
                    if (text[i] == '\n') {
                        lines++
                        if (lines >= tv.lineCount - MAX_LINES) {
                            cut = i + 1
                            break
                        }
                    }
                }
                if (cut > 0 && cut < text.length) {
                    tv.text = text.subSequence(cut, text.length)
                }
            }
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        LogBus.unregister(logListener)
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        logView = null
        scrollView = null
        foldButton = null
        params = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
