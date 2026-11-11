package com.yys.bot

import android.app.Application
import android.util.Log
import com.yys.bot.service.AssetTemplateInstaller
import org.opencv.android.OpenCVLoader

class YysBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // OpenCV 的 Java 层调用原生库前必须先加载 so，否则首个 Mat 操作会抛
        // UnsatisfiedLinkError（Error 不是 Exception，会直接崩掉进程）。
        val opencvOk = runCatching { OpenCVLoader.initLocal() }.getOrElse {
            Log.e("YysBot", "OpenCV 初始化失败: ${it.message}", it)
            false
        }
        Log.i("YysBot", "OpenCV init = $opencvOk, version = ${OpenCVLoader.OPENCV_VERSION}")

        val copied = AssetTemplateInstaller.install(this)
        Log.i("YysBot", "Application created, copied $copied template(s)")
    }
}
