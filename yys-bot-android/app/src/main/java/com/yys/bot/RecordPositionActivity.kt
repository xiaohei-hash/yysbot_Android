package com.yys.bot

import android.graphics.PixelFormat
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yys.bot.service.ConfigManager

/**
 * 全屏透明 Activity：记录点击坐标或框选范围。
 * - 轻点：记录单点坐标
 * - 按住拖动：框选矩形范围（范围内随机点击）
 * 坐标统一换算到 1920x1080 基准。
 */
class RecordPositionActivity : AppCompatActivity() {

    companion object {
        /** 判定为"单点"的最大拖动距离（dp） */
        private const val TAP_THRESHOLD_DP = 16f
        private const val BASE_W = 1920.0
        private const val BASE_H = 1080.0
    }

    private lateinit var tvHint: TextView
    private lateinit var tvFooter: TextView
    private lateinit var vSelection: View
    private lateinit var rootOverlay: View
    private var templateName: String = ""
    private var config: ConfigManager? = null

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setFormat(PixelFormat.TRANSLUCENT)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_record_position)

        tvHint = findViewById(R.id.tvHint)
        tvFooter = findViewById(R.id.tvFooter)
        vSelection = findViewById(R.id.vSelection)
        rootOverlay = findViewById(R.id.rootOverlay)

        templateName = intent.getStringExtra("template_name") ?: ""
        config = ConfigManager(this)

        val metrics = resources.displayMetrics
        tvHint.text = "「${templateName}」坐标标定\n当前屏幕：${metrics.widthPixels}x${metrics.heightPixels}（基准 1920x1080）\n\n轻点 = 单点坐标\n拖动 = 框选范围"

        rootOverlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isDragging = false
                    updateSelectionRect(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        val dx = event.x - startX
                        val dy = event.y - startY
                        if (dx * dx + dy * dy > dp(TAP_THRESHOLD_DP) * dp(TAP_THRESHOLD_DP)) {
                            isDragging = true
                            vSelection.visibility = View.VISIBLE
                            tvHint.visibility = View.GONE
                        }
                    }
                    if (isDragging) updateSelectionRect(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        saveRegion(startX, startY, event.x, event.y)
                    } else {
                        savePoint(event.x, event.y)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** 拖动过程中更新预览矩形 */
    private fun updateSelectionRect(curX: Float, curY: Float) {
        if (!isDragging) return
        val left = minOf(startX, curX).toInt()
        val top = minOf(startY, curY).toInt()
        val right = maxOf(startX, curX).toInt()
        val bottom = maxOf(startY, curY).toInt()
        val lp = vSelection.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = left
        lp.topMargin = top
        lp.width = (right - left).coerceAtLeast(1)
        lp.height = (bottom - top).coerceAtLeast(1)
        vSelection.layoutParams = lp
        tvFooter.text = "范围：(x=$left, y=$top)  宽=${right - left}  高=${bottom - top}"
    }

    /** 保存单点坐标 */
    private fun savePoint(x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val baseX = (x * BASE_W / metrics.widthPixels).toInt()
        val baseY = (y * BASE_H / metrics.heightPixels).toInt()
        config?.repository?.setFixedPosition(templateName, baseX, baseY, 0, 0)
        Toast.makeText(this, "已记录单点：(${x.toInt()},${y.toInt()}) → 基准($baseX,$baseY)", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    /** 保存矩形范围 */
    private fun saveRegion(x1: Float, y1: Float, x2: Float, y2: Float) {
        val metrics = resources.displayMetrics
        val sx = minOf(x1, x2)
        val sy = minOf(y1, y2)
        val sw = kotlin.math.abs(x2 - x1)
        val sh = kotlin.math.abs(y2 - y1)

        val baseX = (sx * BASE_W / metrics.widthPixels).toInt()
        val baseY = (sy * BASE_H / metrics.heightPixels).toInt()
        val baseW = (sw * BASE_W / metrics.widthPixels).toInt()
        val baseH = (sh * BASE_H / metrics.heightPixels).toInt()

        config?.repository?.setFixedPosition(templateName, baseX, baseY, baseW, baseH)
        Toast.makeText(
            this,
            "已记录范围：基准($baseX,$baseY) 宽${baseW} 高${baseH}",
            Toast.LENGTH_LONG
        ).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}