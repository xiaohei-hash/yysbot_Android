package com.yys.bot.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简单的进程内日志总线：StateMachine 写日志，悬浮窗订阅显示。
 */
object LogBus {
    private const val TAG = "YysBot"

    private val listeners = mutableListOf<(String) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun register(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun unregister(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun snapshot(): List<(String) -> Unit> = listeners.toList()

    fun log(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        Log.i(TAG, line)
        snapshot().forEach { listener ->
            runCatching { listener(line) }
        }
    }
}
