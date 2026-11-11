package com.yys.bot.service

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/** 简单的宽高容器，避免 Pair 类型推断问题 */
data class Size2D(val width: Int, val height: Int)

/**
 * 优化后的 TemplateMatcher：
 * 1. 启动时一次性加载所有模板到内存（Mat 缓存）
 * 2. 预计算所有尺度的模板金字塔（固定分辨率 1920x1080 下）
 * 3. 支持两种模式：
 *    - IMAGE_RECOGNITION: OpenCV 多尺度模板匹配
 *    - FIXED_POSITION: 直接返回固定坐标，完全跳过 OpenCV
 * 4. 复用 Mat 对象，减少 GC
 * 5. 单次匹配多模板（批量查找），减少截帧次数
 */
class TemplateMatcher(
    private val templateDir: File,
    private val repo: TemplateRepository,
    private val globalConfidence: Double = 0.80,
    private val globalDetectConfidence: Double = 0.70
) {

    companion object {
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg")
        private val SCALES = doubleArrayOf(0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0)
        private const val TARGET_W = 1920
        private const val TARGET_H = 1080
    }

    private val random = Random()

    private val pyramidCache = ConcurrentHashMap<String, Array<Mat>>()
    private val templateCache = ConcurrentHashMap<String, Mat>()
    private val sizeCache = ConcurrentHashMap<String, Size2D>()
    private val configCache = ConcurrentHashMap<String, MatcherTemplateConfig>()

    /** 初始化：加载所有模板并预计算金字塔（建议在 IO 线程调用）。
     *  先遍历全部配置（固定坐标模式无需图片文件即可生效），再加载有图片的模板。 */
    fun initialize() {
        // 1. 缓存所有配置（包括无图片、仅固定坐标的模板）
        repo.getAllOrdered().forEach { cfg ->
            configCache[cfg.name] = cfg.toMatcherConfig()
        }
        // 2. 加载图片文件并预计算金字塔
        val files = templateDir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (file.extension.lowercase() !in IMAGE_EXT) continue

            val name = file.nameWithoutExtension
            // 若该图片没有配置（用户直接丢进目录的），补一个默认配置
            if (!configCache.containsKey(name)) {
                val config = repo.get(name)?.toMatcherConfig()
                config?.let { configCache[name] = it }
            }

            val mat = Imgcodecs.imread(file.absolutePath)
            if (mat.empty()) continue

            templateCache[name] = mat
            sizeCache[name] = Size2D(mat.width(), mat.height())

            val pyramids = SCALES.map { scale ->
                val w = (mat.width() * scale).toInt()
                val h = (mat.height() * scale).toInt()
                if (w < 10 || h < 10 || w > TARGET_W || h > TARGET_H) {
                    Mat()
                } else {
                    val scaled = Mat()
                    Imgproc.resize(
                        mat,
                        scaled,
                        Size(w.toDouble(), h.toDouble()),
                        0.0, 0.0,
                        if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
                    )
                    scaled
                }
            }.toTypedArray()
            pyramidCache[name] = pyramids
        }
    }

    /** 释放所有缓存（服务停止时调用） */
    fun release() {
        templateCache.values.forEach { it.release() }
        templateCache.clear()
        pyramidCache.values.forEach { arr -> arr.forEach { it.release() } }
        pyramidCache.clear()
        sizeCache.clear()
        configCache.clear()
    }

    /** 热更新单个模板（用户替换图片后调用） */
    fun reloadTemplate(name: String) {
        val file = File(templateDir, "$name.png")
        if (!file.exists()) return
        val mat = Imgcodecs.imread(file.absolutePath)
        if (mat.empty()) return

        templateCache[name]?.release()
        pyramidCache[name]?.forEach { it.release() }

        templateCache[name] = mat
        sizeCache[name] = Size2D(mat.width(), mat.height())

        val pyramids = SCALES.map { scale ->
            val w = (mat.width() * scale).toInt()
            val h = (mat.height() * scale).toInt()
            if (w < 10 || h < 10 || w > TARGET_W || h > TARGET_H) {
                Mat()
            } else {
                val scaled = Mat()
                Imgproc.resize(
                    mat,
                    scaled,
                    Size(w.toDouble(), h.toDouble()),
                    0.0, 0.0,
                    if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
                )
                scaled
            }
        }.toTypedArray()
        pyramidCache[name] = pyramids

        repo.get(name)?.toMatcherConfig()?.let { configCache[name] = it }
    }

    // ==================== 对外查找 API ====================

    /** 单模板查找（返回模板内随机点，模拟真人点击） */
    fun find(screen: Mat, name: String): TmMatchResult? {
        val config = configCache[name] ?: return null
        if (!config.enabled) return null

        // FIXED_POSITION 模式：直接返回固定坐标/范围内的随机点，完全跳过 OpenCV（无图片也可用）
        if (config.mode == TemplateMode.FIXED_POSITION) {
            if (!config.hasValidFixedPosition()) return null
            val pt = randomPointInRegion(config)
            return TmMatchResult(
                name = name,
                confidence = 1.0,
                x = pt.x,
                y = pt.y,
                width = config.fixedW,
                height = config.fixedH
            )
        }

        // 没有图片但有固定坐标：自动退化为固定坐标（无需手动切模式）
        if (!hasImage(name) && config.hasValidFixedPosition()) {
            val pt = randomPointInRegion(config)
            return TmMatchResult(
                name = name,
                confidence = 1.0,
                x = pt.x,
                y = pt.y,
                width = config.fixedW,
                height = config.fixedH
            )
        }

        // IMAGE_RECOGNITION 模式：正常 OpenCV 匹配
        val best = matchBest(screen, name, config) ?: return null
        val threshold = if (config.confidence > 0) config.confidence.toDouble() else globalConfidence
        if (best.confidence < threshold) return null

        val size = sizeCache[name] ?: return best
        val w = size.width
        val h = size.height
        val padW = w / 3
        val padH = h / 3
        val x = if (w - 2 * padW > 0) {
            best.x + padW + random.nextInt(w - 2 * padW)
        } else {
            best.x + w / 2
        }
        val y = if (h - 2 * padH > 0) {
            best.y + padH + random.nextInt(h - 2 * padH)
        } else {
            best.y + h / 2
        }
        return TmMatchResult(name, best.confidence, x, y, w, h)
    }

    /** 单模板查找（返回正中心坐标） */
    fun findCenter(screen: Mat, name: String): TmMatchResult? {
        val config = configCache[name] ?: return null
        if (!config.enabled) return null

        if (config.mode == TemplateMode.FIXED_POSITION) {
            if (!config.hasValidFixedPosition()) return null
            return TmMatchResult(
                name = name,
                confidence = 1.0,
                x = config.fixedX + config.fixedW / 2,
                y = config.fixedY + config.fixedH / 2,
                width = config.fixedW,
                height = config.fixedH
            )
        }

        // 没有图片但有固定坐标：自动退化为固定坐标
        if (!hasImage(name) && config.hasValidFixedPosition()) {
            return TmMatchResult(
                name = name,
                confidence = 1.0,
                x = config.fixedX + config.fixedW / 2,
                y = config.fixedY + config.fixedH / 2,
                width = config.fixedW,
                height = config.fixedH
            )
        }

        val best = matchBest(screen, name, config) ?: return null
        val threshold = if (config.confidence > 0) config.confidence.toDouble() else globalConfidence
        if (best.confidence < threshold) return null

        val size = sizeCache[name] ?: return best
        val w = size.width
        val h = size.height
        return TmMatchResult(
            name,
            best.confidence,
            best.x + w / 2,
            best.y + h / 2,
            w, h
        )
    }

    /**
     * 图像匹配失败时的兜底：返回模板配置里保存的固定坐标（或固定范围内随机点）。
     * 与 mode 无关 —— 即使模板是「图像识别」模式，只要标定过固定坐标也能用。
     */
    fun fixedFallback(name: String): TmMatchResult? {
        val config = configCache[name] ?: return null
        if (!config.enabled) return null
        if (!config.hasValidFixedPosition()) return null
        val pt = randomPointInRegion(config)
        return TmMatchResult(name, 1.0, pt.x, pt.y, config.fixedW, config.fixedH)
    }

    /** 批量查找：同一帧上一次性查找多个模板（StateMachine 状态恢复用） */
    fun findAll(screen: Mat, names: List<String>): Map<String, TmMatchResult?> {
        val result = mutableMapOf<String, TmMatchResult?>()
        for (name in names) {
            val config = configCache[name]
            if (config == null || !config.enabled) {
                result[name] = null
                continue
            }
            if (config.mode == TemplateMode.FIXED_POSITION) {
                if (!config.hasValidFixedPosition()) {
                    result[name] = null
                    continue
                }
                val pt = randomPointInRegion(config)
                result[name] = TmMatchResult(
                    name,
                    1.0,
                    pt.x,
                    pt.y,
                    config.fixedW,
                    config.fixedH
                )
                continue
            }
            if (!hasImage(name) && config.hasValidFixedPosition()) {
                val pt = randomPointInRegion(config)
                result[name] = TmMatchResult(
                    name,
                    1.0,
                    pt.x,
                    pt.y,
                    config.fixedW,
                    config.fixedH
                )
                continue
            }
            result[name] = matchBest(screen, name, config)?.let { best ->
                val threshold = if (config.detectConfidence > 0) config.detectConfidence.toDouble() else globalDetectConfidence
                if (best.confidence >= threshold) {
                    val sz = sizeCache[name]
                    if (sz != null) {
                        TmMatchResult(
                            name = name,
                            confidence = best.confidence,
                            x = best.x,
                            y = best.y,
                            width = sz.width,
                            height = sz.height
                        )
                    } else {
                        TmMatchResult(
                            name = name,
                            confidence = best.confidence,
                            x = best.x,
                            y = best.y,
                            width = best.width,
                            height = best.height
                        )
                    }
                } else null
            }
        }
        return result
    }

    /** 状态恢复全图扫描：返回所有启用模板的匹配结果 */
    fun detectAll(screen: Mat): List<TmMatchResult> {
        val results = mutableListOf<TmMatchResult>()
        for ((name, config) in configCache) {
            if (!config.enabled) continue
            if (config.mode == TemplateMode.FIXED_POSITION) {
                if (!config.hasValidFixedPosition()) continue
                val pt = randomPointInRegion(config)
                results.add(
                    TmMatchResult(
                        name,
                        1.0,
                        pt.x,
                        pt.y,
                        config.fixedW,
                        config.fixedH
                    )
                )
                continue
            }
            if (!hasImage(name) && config.hasValidFixedPosition()) {
                val pt = randomPointInRegion(config)
                results.add(
                    TmMatchResult(
                        name,
                        1.0,
                        pt.x,
                        pt.y,
                        config.fixedW,
                        config.fixedH
                    )
                )
                continue
            }
            matchBest(screen, name, config)?.let { best ->
                val threshold = if (config.detectConfidence > 0) config.detectConfidence.toDouble() else globalDetectConfidence
                if (best.confidence >= threshold) {
                    val sz = sizeCache[name]
                    if (sz != null) {
                        results.add(TmMatchResult(
                            name = name,
                            confidence = best.confidence,
                            x = best.x,
                            y = best.y,
                            width = sz.width,
                            height = sz.height
                        ))
                    } else {
                        results.add(TmMatchResult(
                            name = name,
                            confidence = best.confidence,
                            x = best.x,
                            y = best.y,
                            width = best.width,
                            height = best.height
                        ))
                    }
                }
            }
        }
        results.sortByDescending { it.confidence }
        return results
    }

    // ==================== 内部匹配逻辑 ====================

    /** 该模板是否已成功加载图片。 */
    private fun hasImage(name: String): Boolean = templateCache[name]?.empty() == false

    /** 在固定区域（或单点）内取随机点；单点返回原坐标。 */
    private fun randomPointInRegion(config: MatcherTemplateConfig): Point2D {
        val w = config.fixedW
        val h = config.fixedH
        if (w <= 0 && h <= 0) return Point2D(config.fixedX, config.fixedY)
        val dx = if (w > 0) random.nextInt(w) else 0
        val dy = if (h > 0) random.nextInt(h) else 0
        return Point2D(config.fixedX + dx, config.fixedY + dy)
    }

    /** 简单坐标容器（避免 Pair 类型推断问题） */
    data class Point2D(val x: Int, val y: Int)

    /** 核心匹配：使用预计算金字塔，先试 hint 再全扫描 */
    private fun matchBest(screen: Mat, name: String, config: MatcherTemplateConfig): TmMatchResult? {
        val pyramids = pyramidCache[name] ?: return null
        val original = templateCache[name] ?: return null

        val hintScale = config.scaleHint
        val hintIdx = SCALES.indexOfFirst { it == hintScale }
        if (hintIdx >= 0 && hintIdx < pyramids.size) {
            val hit = matchAt(screen, pyramids[hintIdx], hintScale)
            if (hit != null && hit.confidence >= (config.confidence.toDouble().coerceAtLeast(globalConfidence))) {
                config.scaleHint = hintScale
                return hit
            }
        }

        var best: TmMatchResult? = null
        var bestScale = 1.0
        for (idx in SCALES.indices) {
            val scale = SCALES[idx]
            if (scale == hintScale) continue
            if (idx >= pyramids.size) continue
            val scaled = pyramids[idx]
            if (scaled.empty()) continue
            val r = matchAt(screen, scaled, scale)
            if (r == null) continue
            if (best == null || r.confidence > best.confidence) {
                best = r
                bestScale = scale
            }
        }

        val b = best ?: return null
        val threshold = config.confidence.toDouble().coerceAtLeast(globalConfidence)
        if (b.confidence >= threshold) {
            config.scaleHint = bestScale
        }
        return b
    }

    /** 单尺度匹配（复用 result Mat） */
    private fun matchAt(screen: Mat, template: Mat, scale: Double): TmMatchResult? {
        val result = Mat()
        try {
            Imgproc.matchTemplate(screen, template, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal <= 0.0) return null
            // 从 sizeCache 找到对应的原始尺寸
            var origW = template.width()
            var origH = template.height()
            for (entry in sizeCache) {
                val sz = entry.value
                if (sz.width == template.width() && sz.height == template.height()) {
                    origW = sz.width
                    origH = sz.height
                    break
                }
            }
            val w = (origW * scale).toInt()
            val h = (origH * scale).toInt()
            return TmMatchResult("", mm.maxVal, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt(), w, h)
        } finally {
            result.release()
        }
    }
}