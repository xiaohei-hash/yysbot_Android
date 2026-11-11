package com.yys.bot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.yys.bot.service.ConfigManager
import com.yys.bot.service.TemplateConfig
import com.yys.bot.service.TemplateMode
import com.yys.bot.service.TemplateRepository
import java.io.File

class TemplateManagerActivity : AppCompatActivity() {

    private lateinit var rvTemplates: RecyclerView
    private lateinit var fabAddCustom: FloatingActionButton
    private lateinit var config: ConfigManager
    private lateinit var adapter: TemplateAdapter

    // 使用 GetContent 选择图片，直接返回 Uri
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { pendingEdit?.let { name -> onImagePicked(name, it) } }
    }
    // 拍照：需要构造 Uri 并传给 TakePicture
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            pendingEdit?.let { name -> onCameraImageSaved(name) }
        }
    }
    // 坐标/范围标定：返回后重新加载配置并重开编辑框
    private val recordPositionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        config.repository.reload()
        adapter.submitList(config.repository.getAllOrdered())
        val name = pendingRecordName
        pendingRecordName = null
        if (name != null) {
            config.repository.get(name)?.let { showEditDialog(it) }
        }
    }

    private var pendingEdit: String? = null
    private var pendingCameraFile: File? = null
    private var pendingRecordName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_template_manager)

        config = ConfigManager(this)

        rvTemplates = findViewById(R.id.rvTemplates)
        fabAddCustom = findViewById(R.id.fabAddCustom)

        rvTemplates.layoutManager = GridLayoutManager(this, 2)
        adapter = TemplateAdapter(config.repository, config.templateDir)
        rvTemplates.adapter = adapter

        config.repository.configs.observe(this, Observer { _ ->
            adapter.submitList(config.repository.getAllOrdered())
        })
        adapter.submitList(config.repository.getAllOrdered())

        fabAddCustom.setOnClickListener { showAddCustomDialog() }
    }

    override fun onResume() {
        super.onResume()
        // 其他实例（如标定页）可能已修改配置，这里重新从磁盘读取
        config.repository.reload()
        adapter.submitList(config.repository.getAllOrdered())
    }

    private fun showAddCustomDialog() {
        val input = EditText(this).apply { hint = "输入模板名称（英文/数字/下划线）" }
        AlertDialog.Builder(this)
            .setTitle("新增自定义模板")
            .setMessage("只用于固定坐标/范围也可以，无需图片")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (config.repository.get(name) != null) {
                    Toast.makeText(this, "模板已存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 直接创建（不强制选图），随即打开编辑框
                config.repository.update(name) { }
                adapter.submitList(config.repository.getAllOrdered())
                config.repository.get(name)?.let { showEditDialog(it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImageSourceDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("选择图片来源")
            .setItems(arrayOf("相册选取", "拍照")) { _, which ->
                when (which) {
                    0 -> {
                        pendingEdit = name
                        imagePicker.launch("image/*")
                    }
                    1 -> {
                        pendingEdit = name
                        val file = File(cacheDir, "${name}_camera.jpg")
                        pendingCameraFile = file
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "${packageName}.fileprovider", file
                        )
                        cameraLauncher.launch(uri)
                    }
                }
            }
            .show()
    }

    private fun onImagePicked(name: String, uri: Uri) {
        if (config.repository.replaceTemplateImage(uri, name)) {
            Toast.makeText(this, "模板 '$name' 图片已更新", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
        }
        pendingEdit = null
    }

    private fun onCameraImageSaved(name: String) {
        pendingCameraFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file
                )
                onImagePicked(name, uri)
            } else {
                Toast.makeText(this, "拍照失败", Toast.LENGTH_SHORT).show()
            }
        }
        pendingCameraFile = null
    }

    private fun showEditDialog(config: TemplateConfig) {
        val repo = this.config.repository
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_template_edit, null)
        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val cbEnabled = view.findViewById<android.widget.CheckBox>(R.id.cbEnabled)
        val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)
        val rbImageRecognition = view.findViewById<RadioButton>(R.id.rbImageRecognition)
        val rbFixedPosition = view.findViewById<RadioButton>(R.id.rbFixedPosition)
        val llFixedPos = view.findViewById<LinearLayout>(R.id.llFixedPos)
        val etFixedX = view.findViewById<EditText>(R.id.etFixedX)
        val etFixedY = view.findViewById<EditText>(R.id.etFixedY)
        val etFixedW = view.findViewById<EditText>(R.id.etFixedW)
        val etFixedH = view.findViewById<EditText>(R.id.etFixedH)
        val tvRegionInfo = view.findViewById<TextView>(R.id.tvRegionInfo)
        val btnRecordPos = view.findViewById<Button>(R.id.btnRecordPos)
        val llImageRecognitionSettings = view.findViewById<LinearLayout>(R.id.llImageRecognitionSettings)
        val etConfidence = view.findViewById<EditText>(R.id.etConfidence)
        val etDetectConf = view.findViewById<EditText>(R.id.etDetectConfidence)
        val btnPickImage = view.findViewById<Button>(R.id.btnPickImage)
        val btnTakePhoto = view.findViewById<Button>(R.id.btnTakePhoto)
        val btnReset = view.findViewById<Button>(R.id.btnReset)

        val templateFile = File(this.config.templateDir, "${config.name}.png")
        if (templateFile.exists()) {
            Glide.with(this).load(templateFile).into(ivPreview)
        } else {
            ivPreview.setImageResource(android.R.color.darker_gray)
        }

        tvName.text = config.name + if (config.isBuiltIn) " (内置)" else " (自定义)"
        cbEnabled.isChecked = config.enabled

        // 设置模式单选按钮
        when (config.mode) {
            TemplateMode.IMAGE_RECOGNITION -> rbImageRecognition.isChecked = true
            TemplateMode.FIXED_POSITION -> rbFixedPosition.isChecked = true
        }
        updateModeVisibility(config.mode, llFixedPos, btnRecordPos, llImageRecognitionSettings, tvRegionInfo)

        etFixedX.setText(config.fixedX.toString())
        etFixedY.setText(config.fixedY.toString())
        etFixedW.setText(config.fixedW.toString())
        etFixedH.setText(config.fixedH.toString())
        etConfidence.setText(config.confidence.toString())
        etDetectConf.setText(config.detectConfidence.toString())

        // 模式切换监听
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbImageRecognition -> TemplateMode.IMAGE_RECOGNITION
                R.id.rbFixedPosition -> TemplateMode.FIXED_POSITION
                else -> TemplateMode.IMAGE_RECOGNITION
            }
            updateModeVisibility(mode, llFixedPos, btnRecordPos, llImageRecognitionSettings, tvRegionInfo)
        }

        btnReset.visibility = if (config.isBuiltIn) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑模板：${config.name}")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                repo.setEnabled(config.name, cbEnabled.isChecked)

                val mode = when (rgMode.checkedRadioButtonId) {
                    R.id.rbImageRecognition -> TemplateMode.IMAGE_RECOGNITION
                    R.id.rbFixedPosition -> TemplateMode.FIXED_POSITION
                    else -> TemplateMode.IMAGE_RECOGNITION
                }
                repo.setMode(config.name, mode)

                if (mode == TemplateMode.FIXED_POSITION) {
                    val x = etFixedX.text.toString().toIntOrNull() ?: -1
                    val y = etFixedY.text.toString().toIntOrNull() ?: -1
                    val w = etFixedW.text.toString().toIntOrNull() ?: 0
                    val h = etFixedH.text.toString().toIntOrNull() ?: 0
                    if (x < 0 || y < 0) {
                        Toast.makeText(this, "固定坐标模式下 X/Y 不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    repo.setFixedPosition(config.name, x, y, w, h)
                }

                val conf = etConfidence.text.toString().toFloatOrNull() ?: 0.80f
                val detConf = etDetectConf.text.toString().toFloatOrNull() ?: 0.70f
                repo.update(config.name) {
                    it.confidence = conf.coerceIn(0.01f, 1.0f)
                    it.detectConfidence = detConf.coerceIn(0.01f, 1.0f)
                }

                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton(if (config.isBuiltIn) "重置为默认" else "删除模板") { _, _ ->
                if (config.isBuiltIn) {
                    repo.resetToBuiltIn(config.name)
                    Toast.makeText(this, "已重置为内置默认", Toast.LENGTH_SHORT).show()
                } else {
                    File(this.config.templateDir, "${config.name}.png").delete()
                    repo.update(config.name) { it.enabled = false }
                    Toast.makeText(this, "已禁用（文件已删除）", Toast.LENGTH_SHORT).show()
                }
            }
            .create()

        btnPickImage.setOnClickListener {
            pendingEdit = config.name
            dialog.dismiss()
            imagePicker.launch("image/*")
        }
        btnTakePhoto.setOnClickListener {
            pendingEdit = config.name
            dialog.dismiss()
            val file = File(cacheDir, "${config.name}_camera.jpg")
            pendingCameraFile = file
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            cameraLauncher.launch(uri)
        }
        btnRecordPos.setOnClickListener {
            pendingRecordName = config.name
            dialog.dismiss()
            val intent = Intent(this, RecordPositionActivity::class.java).apply {
                putExtra("template_name", config.name)
            }
            recordPositionLauncher.launch(intent)
        }

        dialog.show()
    }

    private fun updateModeVisibility(
        mode: TemplateMode,
        llFixedPos: View,
        btnRecordPos: Button,
        llImageRecognitionSettings: View,
        tvRegionInfo: View
    ) {
        val isFixed = mode == TemplateMode.FIXED_POSITION
        llFixedPos.visibility = if (isFixed) View.VISIBLE else View.GONE
        btnRecordPos.visibility = if (isFixed) View.VISIBLE else View.GONE
        tvRegionInfo.visibility = if (isFixed) View.VISIBLE else View.GONE
        llImageRecognitionSettings.visibility = if (isFixed) View.GONE else View.VISIBLE
    }

    inner class TemplateAdapter(
        private val repo: TemplateRepository,
        private val templateDir: File
    ) : RecyclerView.Adapter<TemplateAdapter.VH>() {

        private var data: List<TemplateConfig> = emptyList()

        fun submitList(list: List<TemplateConfig>) {
            data = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_template, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount(): Int = data.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivPreview: ImageView = view.findViewById(R.id.ivPreview)
            private val tvBuiltInBadge: TextView = view.findViewById(R.id.tvBuiltInBadge)
            private val ivFixedPos: ImageView = view.findViewById(R.id.ivFixedPos)
            private val vDisabledOverlay: View = view.findViewById(R.id.vDisabledOverlay)
            private val tvDisabledLabel: TextView = view.findViewById(R.id.tvDisabledLabel)
            private val tvName: TextView = view.findViewById(R.id.tvName)
            private val tvDetail: TextView = view.findViewById(R.id.tvDetail)
            private val tvMode: TextView = view.findViewById(R.id.tvMode)

            fun bind(config: TemplateConfig) {
                tvName.text = config.name
                tvBuiltInBadge.visibility = if (config.isBuiltIn) View.VISIBLE else View.GONE
                ivFixedPos.visibility = if (config.mode == TemplateMode.FIXED_POSITION && config.hasValidFixedPosition()) View.VISIBLE else View.GONE

                if (!config.enabled) {
                    vDisabledOverlay.visibility = View.VISIBLE
                    tvDisabledLabel.visibility = View.VISIBLE
                } else {
                    vDisabledOverlay.visibility = View.GONE
                    tvDisabledLabel.visibility = View.GONE
                }

                val templateFile = File(templateDir, "${config.name}.png")
                if (templateFile.exists()) {
                    Glide.with(itemView).load(templateFile).into(ivPreview)
                } else {
                    ivPreview.setImageResource(android.R.color.darker_gray)
                }

                val detail = StringBuilder()
                if (config.mode == TemplateMode.FIXED_POSITION && config.hasValidFixedPosition()) {
                    if (config.hasFixedRegion()) {
                        detail.append("固定范围: (${config.fixedX},${config.fixedY}) ${config.fixedW}x${config.fixedH}")
                    } else {
                        detail.append("固定坐标: (${config.fixedX}, ${config.fixedY})")
                    }
                } else {
                    detail.append("阈值: ${"%.2f".format(config.confidence)} / ${"%.2f".format(config.detectConfidence)}")
                }
                tvDetail.text = detail.toString()

                tvMode.text = when (config.mode) {
                    TemplateMode.IMAGE_RECOGNITION -> "🔍 图像识别"
                    TemplateMode.FIXED_POSITION -> if (config.hasFixedRegion()) "▭ 固定范围" else "📍 固定坐标"
                }

                itemView.setOnClickListener { showEditDialog(config) }
                itemView.setOnLongClickListener {
                    repo.setEnabled(config.name, !config.enabled)
                    Toast.makeText(itemView.context, if (config.enabled) "已禁用" else "已启用", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }
}