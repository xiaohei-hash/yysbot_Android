package com.yys.bot.service

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

/**
 * 模板匹配模式
 * - IMAGE_RECOGNITION: 使用 OpenCV 多尺度模板匹配
 * - FIXED_POSITION: 直接使用固定坐标/固定范围点击，完全跳过匹配
 */
enum class TemplateMode {
    IMAGE_RECOGNITION,
    FIXED_POSITION
}

/**
 * 单个模板的完整配置。
 * - name: 逻辑名（如 "i_tansuo"），对应 assets/templates/ 下的文件名
 * - mode: 匹配模式（图像识别 / 固定范围）
 * - enabled: 是否参与匹配/检测
 * - fixedX/fixedY: 固定区域的左上角（1920x1080 基准）
 * - fixedW/fixedH: 固定区域宽高；都为 0 表示单点坐标
 * - confidence: 单模板匹配阈值（仅图像识别模式生效，覆盖全局）
 * - detectConfidence: 状态恢复扫描阈值（仅图像识别模式生效，覆盖全局）
 * - scaleHint: 上次命中的缩放比例（TemplateMatcher 运行时维护）
 * - isBuiltIn: 是否为内置默认模板（用于 UI 区分，用户不可删，只能替换图片）
 */
data class TemplateConfig(
    val name: String,
    var mode: TemplateMode = TemplateMode.IMAGE_RECOGNITION,
    var enabled: Boolean = true,
    var fixedX: Int = -1,
    var fixedY: Int = -1,
    var fixedW: Int = 0,
    var fixedH: Int = 0,
    var confidence: Float = 0.80f,
    var detectConfidence: Float = 0.70f,
    var scaleHint: Double = 1.0,
    var isBuiltIn: Boolean = true
) {
    /** 是否有有效坐标 */
    fun hasValidFixedPosition(): Boolean = fixedX >= 0 && fixedY >= 0

    /** 是否是范围（而非单点） */
    fun hasFixedRegion(): Boolean = hasValidFixedPosition() && fixedW > 0 && fixedH > 0

    /** 生成给 TemplateMatcher 用的只读视图 */
    fun toMatcherConfig(): MatcherTemplateConfig = MatcherTemplateConfig(
        name = name,
        mode = mode,
        enabled = enabled,
        fixedX = fixedX,
        fixedY = fixedY,
        fixedW = fixedW,
        fixedH = fixedH,
        confidence = confidence,
        detectConfidence = detectConfidence,
        scaleHint = scaleHint
    )
}

/** 传给 TemplateMatcher 的不可变配置 */
data class MatcherTemplateConfig(
    val name: String,
    val mode: TemplateMode,
    val enabled: Boolean,
    val fixedX: Int,
    val fixedY: Int,
    val fixedW: Int,
    val fixedH: Int,
    val confidence: Float,
    val detectConfidence: Float,
    var scaleHint: Double
) {
    fun hasValidFixedPosition(): Boolean = fixedX >= 0 && fixedY >= 0
    fun hasFixedRegion(): Boolean = hasValidFixedPosition() && fixedW > 0 && fixedH > 0
}

/** TemplateMatcher 返回的匹配结果（避免与 kotlin.text.MatchResult 冲突） */
data class TmMatchResult(
    val name: String,
    val confidence: Double,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/** 仓库：负责模板配置的增删改查、JSON 持久化、内置模板初始化 */
class TemplateRepository(private val context: Context) {

    private val gson = Gson()
    private val configFile: File = File(context.filesDir, "template_config.json").apply {
        parentFile?.mkdirs()
    }
    private val _configs = MutableLiveData<Map<String, TemplateConfig>>()
    val configs: LiveData<Map<String, TemplateConfig>> = _configs

    /** 内置模板清单（顺序 = UI 显示顺序） */
    private val builtInNames = listOf(
        "i_tansuo", "i_28", "i_boss", "i_zhandou", "i_end",
        "i_paper", "i_treasure", "i_off", "fanhui1", "fanhui", "fanhui2", "i_back",
        "home", "dl", "long_sure", "i_jj", "yuhun", "easy-znq",
        "xsfy", "yijujue", "decline", "disonline", "disonline_sure", "i_sure",
        "gao", "zudui", "30man1", "30man2", "offtp",
        "i_tou", "crash", "i_again",
        // 结界突破按键（可用固定坐标/范围，无需图片）
        "key_1", "key_2", "key_3", "key_4", "key_5",
        "key_6", "key_7", "key_8", "key_9",
        "key_q", "key_w"
    )

    init { load() }

    private fun load() {
        val map = mutableMapOf<String, TemplateConfig>()
        if (configFile.exists()) {
            configFile.readText().let { json ->
                try {
                    val type: Type = object : TypeToken<Map<String, TemplateConfig>>() {}.type
                    val loaded = gson.fromJson<Map<String, TemplateConfig>>(json, type) ?: emptyMap()
                    loaded.forEach { (k, v) -> map[k] = v }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        builtInNames.forEach { name ->
            if (!map.containsKey(name)) {
                map[name] = TemplateConfig(name = name, isBuiltIn = true)
            } else {
                val existing = map[name]!!
                map[name] = existing.copy(isBuiltIn = true)
            }
        }
        val templateDir = File(context.filesDir, "templates")
        templateDir.listFiles()?.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.forEach { file ->
            val name = file.nameWithoutExtension
            if (!map.containsKey(name)) {
                map[name] = TemplateConfig(name = name, isBuiltIn = false)
            }
        }
        _configs.value = map
        save()
    }

    private fun save() {
        _configs.value?.let { map ->
            configFile.writeText(gson.toJson(map))
        }
    }

    fun get(name: String): TemplateConfig? = _configs.value?.get(name)

    fun getAllOrdered(): List<TemplateConfig> {
        val map = _configs.value ?: return emptyList()
        val ordered = mutableListOf<TemplateConfig>()
        builtInNames.forEach { name -> map[name]?.let { ordered.add(it) } }
        map.entries
            .filter { !builtInNames.contains(it.key) }
            .sortedBy { it.key }
            .forEach { ordered.add(it.value) }
        return ordered
    }

    /** 更新配置；模板不存在时自动创建（支持“只有固定范围、没有图片”的模板）。 */
    fun update(name: String, block: (TemplateConfig) -> Unit) {
        val currentMap = _configs.value ?: mutableMapOf()
        val existing = currentMap[name]
        val target = existing ?: TemplateConfig(name = name, isBuiltIn = false)
        block(target)
        val newMap = mutableMapOf<String, TemplateConfig>()
        currentMap.forEach { (k, v) -> newMap[k] = v }
        newMap[name] = target
        _configs.value = newMap
        save()
    }

    /** 从磁盘重新读取配置（跨 Activity/Service 实例同步用）。 */
    fun reload() {
        load()
    }

    /** 设置固定坐标/范围（自动切换到 FIXED_POSITION 模式） */
    fun setFixedPosition(name: String, x: Int, y: Int, w: Int = 0, h: Int = 0) {
        update(name) {
            it.mode = TemplateMode.FIXED_POSITION
            it.fixedX = x
            it.fixedY = y
            it.fixedW = w.coerceAtLeast(0)
            it.fixedH = h.coerceAtLeast(0)
        }
    }

    /** 切换模式 */
    fun setMode(name: String, mode: TemplateMode) {
        update(name) { it.mode = mode }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        update(name) { it.enabled = enabled }
    }

    fun replaceTemplateImage(uri: android.net.Uri, name: String): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val target = File(context.filesDir, "templates/$name.png")
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            _configs.value?.get(name)?.let { config ->
                if (config.isBuiltIn) {
                    update(name) { it.isBuiltIn = false }
                }
            } ?: run {
                _configs.value?.let { map ->
                    val newMap = mutableMapOf<String, TemplateConfig>()
                    map.forEach { (k, v) -> newMap[k] = v }
                    newMap[name] = TemplateConfig(name = name, isBuiltIn = false)
                    _configs.value = newMap
                    save()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun resetToBuiltIn(name: String) {
        val templateFile = File(context.filesDir, "templates/$name.png")
        templateFile.delete()
        update(name) {
            it.enabled = true
            it.mode = TemplateMode.IMAGE_RECOGNITION
            it.fixedX = -1
            it.fixedY = -1
            it.fixedW = 0
            it.fixedH = 0
            it.confidence = 0.80f
            it.detectConfidence = 0.70f
            it.isBuiltIn = true
        }
    }

    fun exportJson(): String = gson.toJson(_configs.value)

    fun importJson(json: String): Boolean {
        return try {
            val type: Type = object : TypeToken<Map<String, TemplateConfig>>() {}.type
            val loaded = gson.fromJson<Map<String, TemplateConfig>>(json, type) ?: return false
            loaded.forEach { (k, v) ->
                _configs.value?.get(k)?.let { existing ->
                    v.isBuiltIn = existing.isBuiltIn
                }
            }
            _configs.value = loaded
            save()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}