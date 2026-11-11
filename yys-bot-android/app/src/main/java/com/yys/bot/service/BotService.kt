package com.yys.bot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.yys.bot.MainActivity
import com.yys.bot.R
import com.yys.bot.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前台服务：持有 MediaProjection，驱动 StateMachine。
 * 新增：在 IO 线程初始化 TemplateMatcher（预计算金字塔），监听模板配置变化热更新。
 */
class BotService : Service() {

    companion object {
        private const val CHANNEL_ID = "yys_bot_run"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_TIMES = "extra_times"
        private const val EXTRA_LUNS = "extra_luns"
        private const val EXTRA_QW = "extra_qw"
        private const val EXTRA_GAME_PKG = "extra_game_pkg"

        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            times: Int,
            luns: Int,
            qw: Int,
            gamePackage: String
        ): Intent = Intent(context, BotService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, data)
            putExtra(EXTRA_TIMES, times)
            putExtra(EXTRA_LUNS, luns)
            putExtra(EXTRA_QW, qw)
            putExtra(EXTRA_GAME_PKG, gamePackage)
        }
    }

    private var projection: MediaProjection? = null
    private var screenCapture: ScreenCapture? = null
    private var stateMachine: StateMachine? = null
    private var config: ConfigManager? = null
    private var matcher: TemplateMatcher? = null
    private var initMatcherJob: Job? = null
    private val configObserver = Observer<Map<String, TemplateConfig>> { _ ->
        matcher?.release()
        config?.let { c ->
            matcher = TemplateMatcher(c.templateDir, c.repository, c.confidenceThreshold.toDouble(), c.detectConfidence.toDouble())
            initMatcherJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { matcher?.initialize() }
            stateMachine?.updateMatcher(matcher!!)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        config = ConfigManager(this)
        config?.repository?.configs?.observeForever(configObserver)

        config?.let { c ->
            matcher = TemplateMatcher(c.templateDir, c.repository, c.confidenceThreshold.toDouble(), c.detectConfidence.toDouble())
            initMatcherJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { matcher?.initialize() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val times = intent.getIntExtra(EXTRA_TIMES, ConfigManager.DEFAULT_TIMES)
        val luns = intent.getIntExtra(EXTRA_LUNS, ConfigManager.DEFAULT_LUNS)
        val qw = intent.getIntExtra(EXTRA_QW, ConfigManager.DEFAULT_QW)
        val gamePackage = intent.getStringExtra(EXTRA_GAME_PKG) ?: ""

        if (resultData == null) {
            LogBus.log("缺少屏幕捕获授权数据，无法启动")
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection == null) {
            startForegroundCompat()

            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                LogBus.log("获取 MediaProjection 失败")
                stopSelf()
                return START_NOT_STICKY
            }
            initBot()
            launchGame(gamePackage)
        }

        stateMachine?.start(times, luns, qw)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initBot() {
        val accessibility = AccessibilityHelper.instance
        if (accessibility == null) {
            LogBus.log("无障碍服务未启用，请先在系统设置中开启")
            return
        }

        val c = config ?: return
        val mediaProjection = projection ?: run {
            LogBus.log("MediaProjection 不存在")
            return
        }

        // 重新读取模板配置：用户可能刚在「管理模板」里改过坐标/模式
        c.repository.reload()
        matcher?.release()
        val freshMatcher = TemplateMatcher(
            c.templateDir,
            c.repository,
            c.confidenceThreshold.toDouble(),
            c.detectConfidence.toDouble()
        ).also { it.initialize() }
        matcher = freshMatcher

        val capture = ScreenCapture(this)
        capture.start(mediaProjection)
        screenCapture = capture
        stateMachine = StateMachine(capture, freshMatcher, accessibility)

        LogBus.log("模板目录：${c.templateDir.absolutePath}（${c.templateCount()} 张）")
        LogBus.log("已加载模板配置：${c.repository.getAllOrdered().count { it.enabled }} 个启用")

        val capturedDisplay = 0
        val gameDisplay = accessibility.foreignWindowDisplayId()
        LogBus.log("截图屏幕=$capturedDisplay，前台其他应用屏幕=${gameDisplay ?: "未知"}")
        if (gameDisplay != null && gameDisplay != capturedDisplay) {
            LogBus.log("⚠️ 游戏在屏幕 $gameDisplay，截图只能看到屏幕 $capturedDisplay：")
            LogBus.log("⚠️ 模拟器开了「应用分身/多屏」。请关闭分身，让游戏与 YysBot 同屏后再启动。")
        }
        val fg = accessibility.foregroundPackage()
        LogBus.log("当前前台包名=${fg ?: "未知"}")
    }

    private fun launchGame(pkg: String) {
        if (pkg.isBlank()) {
            LogBus.log("未配置游戏包名，请手动切回游戏")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            LogBus.log("找不到包名 $pkg 的启动入口，请检查包名")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(launch) }
            .onSuccess { LogBus.log("已拉起游戏 $pkg") }
            .onFailure { LogBus.log("拉起游戏失败：${it.message}（请手动切回游戏）") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YysBot 运行服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YysBot 运行中")
            .setContentText("自动化脚本正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        stateMachine?.stop()
        screenCapture?.stop()
        projection?.stop()
        matcher?.release()
        initMatcherJob?.cancel()
        config?.repository?.configs?.removeObserver(configObserver)
        stateMachine = null
        screenCapture = null
        projection = null
        matcher = null
        config = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}