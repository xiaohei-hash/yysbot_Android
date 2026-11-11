package com.yys.bot.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.yys.bot.util.toBgrMat
import org.opencv.core.Mat
import java.io.File

/**
 * 基于 MediaProjection 的截图器。持续捕获屏幕帧，供模板匹配读取。
 *
 * 注意：
 * - 尺寸必须用「当前旋转方向」下的显示尺寸（resources.displayMetrics），
 *   用 Display.getRealMetrics() 拿到的是物理竖屏尺寸，游戏横屏时会导致镜像画面错位。
 * - 图像解码放到独立 HandlerThread，避免 1080p 帧解码卡住主线程引发 ANR。
 */
class ScreenCapture(private val context: Context) {

    companion object {
        private const val TAG = "YysBot/Capture"
        private const val DEBUG_FRAME_NAME = "debug_first_frame.png"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestFrame: Mat? = null
    private val lock = Any()
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameCount = 0L
    private var debugSaved = false

    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    fun start(mediaProjection: MediaProjection) {
        stop()
        projection = mediaProjection

        val metrics = context.resources.displayMetrics
        frameWidth = metrics.widthPixels
        frameHeight = metrics.heightPixels

        decodeThread = HandlerThread("YysBotDecode").also { it.start() }
        decodeHandler = Handler(decodeThread!!.looper)

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection 已停止")
            }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                handleImage(image)
            } catch (e: Throwable) {
                Log.w(TAG, "解码截图失败: ${e.message}")
            } finally {
                runCatching { image.close() }
            }
        }, decodeHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "YysBotCapture",
            frameWidth,
            frameHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            decodeHandler
        )
        Log.i(TAG, "截图已启动 ${frameWidth}x${frameHeight} dpi=${metrics.densityDpi}")
    }

    private fun handleImage(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * frameWidth
        val rawWidth = frameWidth + if (pixelStride > 0) rowPadding / pixelStride else 0

        val bitmap = Bitmap.createBitmap(rawWidth, frameHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = if (rawWidth != frameWidth) {
            Bitmap.createBitmap(bitmap, 0, 0, frameWidth, frameHeight)
        } else {
            bitmap
        }

        val frame = cropped.toBgrMat()
        if (!debugSaved) {
            debugSaved = true
            runCatching { saveDebugFrame(cropped) }
            Log.i(TAG, "首帧 ${frame.cols()}x${frame.rows()}，已保存 ${DEBUG_FRAME_NAME}")
        }
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()

        synchronized(lock) {
            latestFrame?.release()
            latestFrame = frame
        }

        frameCount++
        if (frameCount % 60 == 0L) {
            Log.i(TAG, "已捕获 $frameCount 帧")
        }
    }

    private fun saveDebugFrame(bitmap: Bitmap) {
        val file = File(context.filesDir, DEBUG_FRAME_NAME)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** 获取最新帧的副本，调用方负责 release()。 */
    fun getLatestFrame(): Mat? = synchronized(lock) { latestFrame?.clone() }

    /** 把当前帧写成 PNG，用于排查模板匹配失败。 */
    fun dumpDebug(tag: String): String? {
        val frame = synchronized(lock) { latestFrame?.clone() } ?: return null
        return try {
            val file = File(context.filesDir, "debug_$tag.png")
            val ok = org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, frame)
            if (ok) file.absolutePath else null
        } finally {
            frame.release()
        }
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        decodeThread?.quitSafely()
        decodeThread = null
        decodeHandler = null
        synchronized(lock) {
            latestFrame?.release()
            latestFrame = null
        }
        projection?.stop()
        projection = null
        frameCount = 0
        debugSaved = false
    }
}
