package com.yys.bot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yys.bot.service.BotService
import com.yys.bot.service.ConfigManager
import com.yys.bot.service.LogOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectDir: Button
    private lateinit var btnStart: Button
    private lateinit var btnManageTemplates: Button
    private lateinit var tvTemplateDir: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etTimes: EditText
    private lateinit var etLuns: EditText
    private lateinit var etQw: EditText
    private lateinit var etGamePkg: EditText
    private lateinit var config: ConfigManager

    private val dirPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 某些文件管理器不返回持久化权限，忽略
            }
            lifecycleScope.launch {
                val count = withContext(Dispatchers.IO) {
                    config.importTemplatesFromUri(uri)
                }
                updateTemplateInfo()
                Toast.makeText(
                    this@MainActivity,
                    "已导入 $count 张模板",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                Toast.makeText(this, "已取消录屏授权", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val times = etTimes.text.toString().toIntOrNull() ?: ConfigManager.DEFAULT_TIMES
            val luns = etLuns.text.toString().toIntOrNull() ?: ConfigManager.DEFAULT_LUNS
            val qw = etQw.text.toString().toIntOrNull() ?: ConfigManager.DEFAULT_QW
            val gamePkg = etGamePkg.text.toString().trim().ifBlank { ConfigManager.DEFAULT_GAME_PKG }

            // 记住本次参数，下次启动自动回填
            config.lastTimes = times
            config.lastLuns = luns
            config.lastQw = qw

            val intent = BotService.startIntent(
                this,
                result.resultCode,
                result.data!!,
                times,
                luns,
                qw,
                gamePkg
            )
            ContextCompat.startForegroundService(this, intent)
            startService(Intent(this, LogOverlayService::class.java))
            Toast.makeText(this, "脚本已启动，正在返回桌面", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAndStart()
        }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAndStart()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = ConfigManager(this)

        btnSelectDir = findViewById(R.id.btnSelectDir)
        btnStart = findViewById(R.id.btnStart)
        btnManageTemplates = findViewById(R.id.btnManageTemplates)
        tvTemplateDir = findViewById(R.id.tvTemplateDir)
        tvStatus = findViewById(R.id.tvStatus)
        etTimes = findViewById(R.id.etTimes)
        etLuns = findViewById(R.id.etLuns)
        etQw = findViewById(R.id.etQw)
        etGamePkg = findViewById(R.id.etGamePkg)
        etGamePkg.setText(config.gamePackage)

        // 回填上次使用的运行参数
        etTimes.setText(config.lastTimes.toString())
        etLuns.setText(config.lastLuns.toString())
        etQw.setText(config.lastQw.toString())

        btnSelectDir.setOnClickListener { dirPicker.launch(null) }
        btnStart.setOnClickListener { checkAndStart() }
        btnManageTemplates.setOnClickListener {
            startActivity(Intent(this, TemplateManagerActivity::class.java))
        }
        updateTemplateInfo()
    }

    override fun onResume() {
        super.onResume()
        tvStatus.text = if (isAccessibilityEnabled()) {
            "无障碍服务已开启，可以启动脚本"
        } else {
            "请先开启 YysBot 无障碍服务（设置 → 无障碍）"
        }
    }

    private fun updateTemplateInfo() {
        val total = config.templateCount()
        val enabled = config.repository.getAllOrdered().count { it.enabled }
        tvTemplateDir.text = "本地模板：$total 张 | 启用：$enabled 个"
    }

    private fun checkAndStart() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启 YysBot 无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                batteryLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        config.gamePackage = etGamePkg.text.toString().trim().ifBlank { ConfigManager.DEFAULT_GAME_PKG }
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.startsWith(packageName) }
    }
}
