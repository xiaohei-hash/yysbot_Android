package com.yys.bot.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Bitmap(RGBA) -> OpenCV BGR Mat。 */
fun Bitmap.toBgrMat(): Mat {
    val rgba = Mat()
    Utils.bitmapToMat(this, rgba)
    val bgr = Mat()
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    rgba.release()
    return bgr
}

/** OpenCV BGR Mat -> Bitmap(RGBA)。 */
fun Mat.toRgbaBitmap(): Bitmap {
    val rgba = Mat()
    Imgproc.cvtColor(this, rgba, Imgproc.COLOR_BGR2RGBA)
    val bitmap = Bitmap.createBitmap(width(), height(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, bitmap)
    rgba.release()
    return bitmap
}
