package com.yys.bot.service

import android.content.Context
import java.io.File

/**
 * 把打包在 assets/templates 下的内置模板复制到私有目录，保证 OpenCV 可直接读取。
 *
 * - 首次启动：全部复制。
 * - 升级（ASSETS_VERSION 变化）：强制刷新内置模板图片，但跳过用户自己替换过图片的模板
 *   （config.isBuiltIn == false），避免覆盖用户的修改。
 */
object AssetTemplateInstaller {

    private const val PREFS = "yys_bot_assets"
    private const val KEY_VERSION = "assets_version"

    /** 每次需要让已安装用户更新内置模板图片时 +1。 */
    private const val ASSETS_VERSION = 2L

    fun install(context: Context): Int {
        val targetDir = File(context.filesDir, "templates")
        targetDir.mkdirs()

        val names = runCatching { context.assets.list("templates") }.getOrNull() ?: return 0

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val forceRefresh = prefs.getLong(KEY_VERSION, 0L) != ASSETS_VERSION

        // 用户替换过图片的模板，升级时不覆盖
        val customized: Set<String> = if (forceRefresh) {
            runCatching {
                TemplateRepository(context).getAllOrdered()
                    .filter { !it.isBuiltIn }
                    .map { it.name }
                    .toSet()
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

        var copied = 0
        for (name in names) {
            val target = File(targetDir, name)
            val stem = name.substringBeforeLast('.')
            val shouldCopy = if (target.exists() && target.length() > 0) {
                // 已存在：仅升级时刷新，且跳过用户自定义的模板
                forceRefresh && stem !in customized
            } else {
                true
            }
            if (!shouldCopy) continue

            runCatching {
                context.assets.open("templates/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }
        }

        if (forceRefresh) {
            prefs.edit().putLong(KEY_VERSION, ASSETS_VERSION).apply()
        }
        return copied
    }
}