package com.yys.bot.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import java.io.File

/**
 * 配置管理：原 SharedPreferences 部分保留，新增 TemplateRepository 委托
 */
class ConfigManager(private val context: Context) {

    companion object {
        private const val PREFS = "yys_bot"
        private const val KEY_TEMPLATE_URI = "template_dir_uri"
        private const val KEY_CONFIDENCE = "confidence"
        private const val KEY_DETECT_CONFIDENCE = "detect_confidence"
        private const val KEY_GAME_PKG = "game_package"
        private const val KEY_LAST_TIMES = "last_times"
        private const val KEY_LAST_LUNS = "last_luns"
        private const val KEY_LAST_QW = "last_qw"
        const val DEFAULT_GAME_PKG = "com.tencent.tmgp.yys.zqb"

        /** 首次使用的默认值。 */
        const val DEFAULT_TIMES = 1234
        const val DEFAULT_LUNS = 9
        const val DEFAULT_QW = 8
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val templateDir: File = File(context.filesDir, "templates").apply { mkdirs() }
    val repository: TemplateRepository = TemplateRepository(context)

    var templateDirUri: Uri?
        get() = prefs.getString(KEY_TEMPLATE_URI, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(KEY_TEMPLATE_URI, value?.toString()).apply()
        }

    /** 单模板匹配阈值（全局默认）。 */
    var confidenceThreshold: Float
        get() = prefs.getFloat(KEY_CONFIDENCE, 0.80f)
        set(value) {
            prefs.edit().putFloat(KEY_CONFIDENCE, value).apply()
        }

    /** 状态恢复全图扫描阈值（全局默认）。 */
    var detectConfidence: Float
        get() = prefs.getFloat(KEY_DETECT_CONFIDENCE, 0.70f)
        set(value) {
            prefs.edit().putFloat(KEY_DETECT_CONFIDENCE, value).apply()
        }

    /** 目标游戏包名。 */
    var gamePackage: String
        get() = prefs.getString(KEY_GAME_PKG, DEFAULT_GAME_PKG) ?: DEFAULT_GAME_PKG
        set(value) {
            prefs.edit().putString(KEY_GAME_PKG, value).apply()
        }

    /** 上次使用的总循环次数（默认 1234）。 */
    var lastTimes: Int
        get() = prefs.getInt(KEY_LAST_TIMES, DEFAULT_TIMES)
        set(value) {
            prefs.edit().putInt(KEY_LAST_TIMES, value).apply()
        }

    /** 上次使用的结界突破间隔（默认 9 轮）。 */
    var lastLuns: Int
        get() = prefs.getInt(KEY_LAST_LUNS, DEFAULT_LUNS)
        set(value) {
            prefs.edit().putInt(KEY_LAST_LUNS, value).apply()
        }

    /** 上次使用的 QW 轮数（默认 8）。 */
    var lastQw: Int
        get() = prefs.getInt(KEY_LAST_QW, DEFAULT_QW)
        set(value) {
            prefs.edit().putInt(KEY_LAST_QW, value).apply()
        }

    /** 从 SAF 目录批量导入（保留原有逻辑兼容）。 */
    fun importTemplatesFromUri(uri: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, uri) ?: return 0
        return copyDirectory(root)
    }

    private fun copyDirectory(dir: DocumentFile): Int {
        var count = 0
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                count += copyDirectory(child)
                continue
            }
            val name = child.name ?: continue
            val lower = name.lowercase()
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) continue
            runCatching {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(templateDir, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                count++
            }
        }
        return count
    }

    /** 当前私有模板数量。 */
    fun templateCount(): Int =
        templateDir.listFiles()?.count {
            it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg")
        } ?: 0

    /** 监听模板配置变化（UI 用）。 */
    val configsLiveData: LiveData<Map<String, TemplateConfig>> = repository.configs
}