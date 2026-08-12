package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogFullscreenImageBinding
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.DialogRoleManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.loadGif
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File
import java.util.Timer
import java.util.TimerTask

/**
 * 全屏 AI 图片预览界面
 *
 * - 上方按设定比例放大显示当前 AI 生图（fitCenter 不裁剪）
 * - 下方显示朗读字幕（固定 4 行高度，不挤压图片）
 * - 左右滑动切换历史图片（同朗读界面）
 * - 单击左 1/3：上一张；单击右 1/3：下一张；单击中 1/3：切换小说朗读/暂停
 * - 长按弹出菜单选择保存当前图片或保存全部历史图片（同朗读界面）
 * - 点击字幕或按返回键回到朗读界面
 *
 * 由 [ReadAloudDialog] 的字幕点击进入。
 */
class FullscreenAiImageDialog : BaseDialogFragment(R.layout.dialog_fullscreen_image) {

    private val binding by viewBinding(DialogFullscreenImageBinding::bind)

    /** 当前显示的图片文件，进入时从最新生图取，后续随 AI_IMAGE_CHANGED 更新 */
    private var currentImageFile: File? = null

    /** AI 生图历史浏览：当前显示的历史图片索引（在 aiHistoryImages 中的位置） */
    private var aiHistoryImages: List<File> = emptyList()
    private var aiHistoryIndex = -1
    /** 浏览历史图片后，30秒无操作自动回到最新图片的定时器 */
    private var aiHistoryResetTimer: Timer? = null

    /** 关闭 AI 生图时，是否处于"轮播小说文件夹本地图片"模式 */
    private var useLocalImages = false
    /** 本地图片列表与当前索引（useLocalImages=true 时生效） */
    private var localImageFiles: List<File> = emptyList()
    private var localImageIndex = 0
    /** 本地图片轮播定时器（每 5 秒切换下一张） */
    private var localImageTimer: Timer? = null

    /** 屏幕超时设置（毫秒），跟随阅读页"屏幕超时"设置；< 0 表示常亮 */
    private var screenTimeOut: Long = 0
    private val screenOffHandler by lazy { Handler(Looper.getMainLooper()) }
    private val screenOffRunnable by lazy { Runnable { keepDialogScreenOn(false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：使用 dialog_style_material 主题（parent Theme.MaterialComponents.DayNight），
        // 而非默认的 0（会继承阅读页 Activity 的透明状态栏主题）。
        // 只有标准不透明主题下，statusBarColor / FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS 才会生效。
        // 这是 ConfigListDialog 能改变状态栏颜色的根本原因。
        setStyle(STYLE_NO_FRAME, R.style.dialog_style_material)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            // 全屏、无标题栏、无内边距
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.black)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.FILL
            attr.width = ViewGroup.LayoutParams.MATCH_PARENT
            attr.height = ViewGroup.LayoutParams.MATCH_PARENT
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // 关键修复：阅读页 Activity 处于 SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN 模式，状态栏为透明，
            // 内容绘制到状态栏下方，导致 Dialog 顶部透出底层阅读页文字。
            // 参照 ConfigListDialog 的做法：给 Dialog window 添加 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS，
            // 并清除 FLAG_TRANSLUCENT_STATUS，让 Dialog window 自己绘制状态栏背景色（纯黑），
            // 从而彻底遮挡底层文字。黑色状态栏与全屏图片黑色背景融合，视觉上仍是沉浸式。
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            WindowInsetsControllerCompat(this, decorView).apply {
                // 状态栏图标是否隐藏，受阅读界面「隐藏状态栏」开关控制（与朗读设置联动同步）
                if (ReadBookConfig.hideStatusBar) {
                    hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                } else {
                    show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                }
                // 状态栏图标用浅色（白色），在黑色背景上可见
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        // 跟随阅读页"屏幕超时"设置（含常亮），应用到本全屏界面的 window
        upScreenTimeOut()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // BaseDialogFragment.onViewCreated 会把根视图背景设为 ThemeStore.backgroundColor()，
        // 这里改回纯黑，确保不会透出底层阅读页文字
        binding.rootView.setBackgroundColor(Color.BLACK)

        // 点击字幕区域：返回朗读界面
        binding.tvFullscreenSubtitle.setOnClickListener {
            dismissAllowingStateLoss()
        }
        // 根据 AI 生图开关决定图片来源：
        // 开启 → 显示 AI 生图（原逻辑）；关闭 → 扫描小说文件夹本地图片并轮播（与朗读界面小图一致）
        initImageSource()

        // 图片手势：左右滑动切换 + 单击保存 + 长按保存全部
        setupAiImageGesture()
    }

    override fun observeLiveBus() {
        // 播放状态变化不影响本界面显示，仅用于保持状态同步
        observeEvent<Int>(EventBus.ALOUD_STATE) { }
        // 字幕更新：同步显示到全屏界面下方的字幕区
        observeEvent<String>(EventBus.READ_ALOUD_SUBTITLE) {
            binding.tvFullscreenSubtitle.text = it
        }
        // AI 图片变化：更新全屏图片（本地图片模式下忽略，避免覆盖轮播）
        observeEvent<String>(EventBus.AI_IMAGE_CHANGED) { imagePath ->
            if (useLocalImages) return@observeEvent
            if (imagePath.isNotBlank() && File(imagePath).exists()) {
                // 用户正在浏览历史图片时不打断，仅刷新列表数据
                if (aiHistoryIndex > 0) {
                    aiHistoryImages = AiImageGenerator.getDisplayGallery()
                    return@observeEvent
                }
                currentImageFile = File(imagePath)
                loadImage(binding.ivFullscreenImage, currentImageFile!!)
                // 新图片生成，重置历史浏览到最新一张
                cancelAiHistoryReset()
                aiHistoryImages = AiImageGenerator.getDisplayGallery()
                aiHistoryIndex = 0
            }
        }
    }

    /**
     * 初始化全屏图片来源：
     * - AI 生图开启：沿用原有 AI 生图逻辑（最新图 + 历史列表）。
     * - AI 生图关闭：扫描 Download/chajian/xiaoshuo/{小说名}/ 下的本地图片，
     *   加载第一张并启动 5 秒轮播（与朗读界面小封面行为一致）。
     */
    private fun initImageSource() {
        val book = ReadBook.book
        if (AppConfig.readAloudAiImage) {
            useLocalImages = false
            currentImageFile = AiImageGenerator.getLastGeneratedImage()
            if (currentImageFile != null) {
                loadImage(binding.ivFullscreenImage, currentImageFile!!)
            } else {
                // AI 生图开启但暂无缓存图片时，显示书籍封面（与朗读界面小图一致）
                showBookCover()
            }
            aiHistoryImages = AiImageGenerator.getDisplayGallery()
            aiHistoryIndex = 0
        } else {
            useLocalImages = true
            cancelAiHistoryReset()
            val images = if (book != null) DialogRoleManager.scanBookImages(book.name) else emptyList()
            if (images.isNotEmpty()) {
                localImageFiles = images
                localImageIndex = 0
                loadImage(binding.ivFullscreenImage, localImageFiles[0])
                startLocalImageTimer()
            } else {
                localImageFiles = emptyList()
                localImageIndex = 0
            }
        }
    }

    /**
     * 启动本地图片轮播定时器（每 5 秒切换下一张，循环）。
     */
    private fun startLocalImageTimer() {
        stopLocalImageTimer()
        val intervalSec = AppConfig.readAloudImageInterval.coerceAtLeast(0)
        if (intervalSec <= 0) return // 0 表示不自动轮播，仅手动切换
        if (localImageFiles.size <= 1) return
        val period = intervalSec * 1000L
        localImageTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    activity?.runOnUiThread {
                        if (localImageFiles.isEmpty()) return@runOnUiThread
                        localImageIndex = (localImageIndex + 1) % localImageFiles.size
                        loadImage(binding.ivFullscreenImage, localImageFiles[localImageIndex])
                    }
                }
            }, period, period)
        }
    }

    /** 停止本地图片轮播定时器 */
    private fun stopLocalImageTimer() {
        localImageTimer?.cancel()
        localImageTimer = null
    }

    /**
     * 切换本地图片。direction: 1 上一张，-1 下一张（与 AI 历史切换同义，循环）。
     */
    private fun switchLocalImage(direction: Int) {
        if (localImageFiles.isEmpty()) return
        val size = localImageFiles.size
        // 本地图片的方向约定与 AI 历史相反，取反使 direction=-1=下一张、1=上一张，与手势一致
        localImageIndex = (localImageIndex - direction + size) % size
        val file = localImageFiles[localImageIndex]
        if (file.exists()) loadImage(binding.ivFullscreenImage, file)
        // 手动切换后重启轮播定时器：从当前位置重新等待完整的间隔时间再自动切下一张，
        // 避免沿用进入界面时的固定节拍导致切换后很快又跳图（表现为"回到前面的图片"）。
        startLocalImageTimer()
    }

    /**
     * 根据当前模式分发图片切换：本地图片模式走 switchLocalImage，否则走 AI 历史切换。
     */
    private fun switchImage(direction: Int) {
        if (useLocalImages) switchLocalImage(direction) else switchAiHistoryImage(direction)
    }

    /**
     * 为 AI 生图图片设置手势（与朗读界面完全一致）：
     * - 左右滑动：切换历史图片（上一张/下一张）
     * - 从上向下滑动：退出全屏
     * - 单击：保存当前图片
     * - 长按：弹出菜单询问是否保存所有历史图片
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAiImageGesture() {
        val iv = binding.ivFullscreenImage
        var downX = 0f
        var downY = 0f
        var moved = false
        val gesture = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 若发生了滑动切换则不触发单击动作
                if (moved) return false
                val viewWidth = binding.ivFullscreenImage.width.takeIf { it > 0 } ?: return false
                when {
                    e.x < viewWidth / 3f -> {
                        // 左 1/3：上一张图片（本地/AI 通用）
                        switchImage(1)
                    }
                    e.x > viewWidth * 2f / 3f -> {
                        // 右 1/3：下一张图片（本地/AI 通用）
                        switchImage(-1)
                    }
                    else -> {
                        // 中 1/3：切换小说朗读/暂停
                        (requireActivity() as? ReadAloudDialog.CallBack)?.onClickReadAloud()
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                // 已切换过则忽略本次手势后续移动
                if (moved) return false
                val startX = e1?.x ?: downX
                val startY = e1?.y ?: downY
                val dx = e2.x - startX
                val dy = e2.y - startY
                // 纵向向下位移需大于横向，且超过阈值即退出全屏
                if (dy > 60 && Math.abs(dy) > Math.abs(dx)) {
                    dismissAllowingStateLoss()
                    moved = true
                    return false
                }
                // 横向位移需大于纵向，且超过阈值即切换
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
                    if (dx > 0) {
                        // 从左向右滑：查看之前生成的图片（更旧的）
                        switchImage(1)
                    } else {
                        // 从右向左滑：查看后面的图片（更新的）
                        switchImage(-1)
                    }
                    moved = true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                // 切换逻辑已在 onScroll 中处理
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                // 若发生了滑动则不触发长按菜单
                if (moved) return
                val viewWidth = binding.ivFullscreenImage.width.takeIf { it > 0 } ?: return
                when {
                    e.x < viewWidth / 3f || e.x > viewWidth * 2f / 3f -> {
                        // 左/右 1/3：仅 AI 生图模式弹保存菜单（保持原行为）
                        if (AppConfig.readAloudAiImage) showSaveMenu()
                    }
                    else -> {
                        // 中 1/3：弹出图片设置菜单（含轮播时间设置）
                        showImageSettingsMenu()
                    }
                }
            }
        })
        iv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    // 用户操作时重置屏幕超时计时
                    screenOffTimerStart()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 复位标记
                }
            }
            gesture.onTouchEvent(event)
            true
        }
    }

    /**
     * 切换 AI 历史图片。direction: -1 上一张，1 下一张。
     * 滑动后停留在目标图片，并启动 30 秒倒计时，无操作则自动回到最新图片。
     */
    private fun switchAiHistoryImage(direction: Int) {
        if (aiHistoryImages.isEmpty()) {
            aiHistoryImages = AiImageGenerator.getDisplayGallery()
            if (aiHistoryImages.isEmpty()) {
                toastOnUi("暂无历史图片")
                return
            }
            aiHistoryIndex = 0
        }
        val newIndex = aiHistoryIndex + direction
        if (newIndex < 0) {
            toastOnUi("已是最新一张")
            return
        }
        if (newIndex >= aiHistoryImages.size) {
            toastOnUi("已是最早一张")
            return
        }
        aiHistoryIndex = newIndex
        val file = aiHistoryImages[aiHistoryIndex]
        if (file.exists()) {
            loadImage(binding.ivFullscreenImage, file)
        }
        // 停留在滑动到的图片，启动 30 秒后回最新的定时器
        scheduleAiHistoryReset()
    }

    /**
     * 启动 30 秒定时器，到期后回到最新图片并恢复自动生成切换。
     */
    private fun scheduleAiHistoryReset() {
        aiHistoryResetTimer?.cancel()
        aiHistoryResetTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    activity?.runOnUiThread {
                        // 回到最新一张
                        aiHistoryImages = AiImageGenerator.getDisplayGallery()
                        aiHistoryIndex = 0
                        val latest = aiHistoryImages.firstOrNull()
                        if (latest != null && latest.exists()) {
                            loadImage(binding.ivFullscreenImage, latest)
                        }
                        aiHistoryResetTimer?.cancel()
                        aiHistoryResetTimer = null
                    }
                }
            }, 30000L)
        }
    }

    /** 取消历史浏览回最新定时器 */
    private fun cancelAiHistoryReset() {
        aiHistoryResetTimer?.cancel()
        aiHistoryResetTimer = null
    }

    /**
     * 长按中间弹出图片设置菜单：含轮播时间设置；AI 生图模式额外含保存选项。
     */
    private fun showImageSettingsMenu() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        items.add("轮播时间设置")
        actions.add { showIntervalSetting() }
        if (AppConfig.readAloudAiImage) {
            items.add("保存当前图片")
            actions.add { saveAiImage(currentFullscreenImageFile()) }
            items.add("保存全部图片")
            actions.add { showSaveAllMenu() }
            // 缓存数量与清空临时缓存（AI 生图相关，仅开关开启时显示）
            items.add("设置缓存数量")
            actions.add { showCacheCountSetting() }
            items.add("清空临时缓存图片")
            actions.add { confirmClearTempCache() }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("图片设置")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 当前全屏显示的图片文件（本地/AI 通用） */
    private fun currentFullscreenImageFile(): File? {
        return if (useLocalImages) {
            localImageFiles.getOrNull(localImageIndex)
        } else {
            if (aiHistoryImages.isNotEmpty() && aiHistoryIndex in aiHistoryImages.indices) {
                aiHistoryImages[aiHistoryIndex]
            } else {
                AiImageGenerator.getCurrentDisplayImage()
            }
        }
    }

    /**
     * 设置 AI 生图缓存数量（默认 50）：保存后写入 AppConfig，并立即按新上限清理超量旧图。
     */
    private fun showCacheCountSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.aiImageCacheCount.toString())
            hint = "缓存图片数量，默认 50"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("设置缓存数量")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val count = editText.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 50
                AppConfig.aiImageCacheCount = count
                AiImageGenerator.applyCacheLimit()
                toastOnUi("已设为最多缓存 ${count} 张")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清空 AI 生图临时缓存：确认后删除缓存目录下的所有生图文件，
     * 画廊与内存引用同步重置，并回退显示书籍封面。不影响「保存AI图片」的本地永久图。
     */
    private fun confirmClearTempCache() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空临时缓存图片")
            .setMessage("将删除 AI 生图临时缓存（最多缓存的 ${AppConfig.aiImageCacheCount} 张），不影响已保存到本地的图片，确定？")
            .setPositiveButton("清空") { _, _ ->
                val deleted = AiImageGenerator.clearTempCache()
                // 重置画廊，回退到书籍封面
                aiHistoryImages = emptyList()
                aiHistoryIndex = -1
                showBookCover()
                toastOnUi("已清空 $deleted 张临时缓存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 显示当前书籍封面（清空临时缓存、无图可显示时回退用） */
    private fun showBookCover() {
        val book = ReadBook.book ?: return
        // BookCover.load 返回 RequestBuilder<Drawable>，直接交给 into()，不要再用 Glide.load() 包裹。
        // （Glide.load(RequestBuilder) 会匹配 load(Object) 重载，导致加载不出任何内容。）
        BookCover.load(requireContext(), book.getDisplayCover())
            .fitCenter()
            .into(binding.ivFullscreenImage)
    }

    /**
     * 轮播时间设置：输入秒数（0=不自动轮播），保存后重启本地轮播定时器。
     */
    private fun showIntervalSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudImageInterval.toString())
            hint = "秒数，0=不自动轮播"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("轮播时间设置（秒）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val sec = editText.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 5
                AppConfig.readAloudImageInterval = sec
                // 本地图片模式重启轮播定时器以应用新间隔
                if (useLocalImages) startLocalImageTimer()
                val tip = if (sec <= 0) "已设为不自动轮播（仅手动切换）" else "已设为 ${sec} 秒轮播"
                toastOnUi(tip)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 长按 AI 图片：弹出菜单选择保存当前图片或保存全部。
     */
    private fun showSaveMenu() {
        val currentFile = if (useLocalImages) {
            localImageFiles.getOrNull(localImageIndex)
        } else {
            if (aiHistoryImages.isNotEmpty() && aiHistoryIndex in aiHistoryImages.indices) {
                aiHistoryImages[aiHistoryIndex]
            } else {
                AiImageGenerator.getCurrentDisplayImage()
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("保存图片")
            .setItems(arrayOf("保存当前图片", "保存全部图片")) { _, which ->
                when (which) {
                    0 -> saveAiImage(currentFile)
                    1 -> showSaveAllMenu()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 长按 AI 图片弹出菜单：确认是否保存所有历史图片。
     */
    private fun showSaveAllMenu() {
        val allImages = if (useLocalImages) localImageFiles else AiImageGenerator.getDisplayGallery()
        val emptyTip = if (useLocalImages) "暂无本地图片可保存" else "暂无 AI 生图可保存"
        if (allImages.isEmpty()) {
            toastOnUi(emptyTip)
            return
        }
        val title = if (useLocalImages) "保存所有图片" else "保存所有 AI 生图"
        val message = "将保存 ${allImages.size} 张图片到 /storage/emulated/0/Download/AI生图/，是否继续？"
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("保存全部") { _, _ ->
                saveAllAiImages(allImages)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存单张 AI 图片到指定目录 */
    private fun saveAiImage(file: File?) {
        if (file == null || !file.exists()) {
            toastOnUi("图片不存在")
            return
        }
        val targetDir = java.io.File("/storage/emulated/0/Download/AI生图/")
        if (!targetDir.exists()) targetDir.mkdirs()
        // 用缓存文件的时间戳命名，重复保存也只会覆盖同名文件，不会产生重复
        val target = java.io.File(targetDir, "${file.lastModified()}.png")
        try {
            file.copyTo(target, overwrite = true)
            toastOnUi("已保存到 ${target.absolutePath}")
        } catch (e: Exception) {
            toastOnUi("保存失败：${e.localizedMessage}")
        }
    }

    /** 保存所有 AI 历史图片到指定目录 */
    private fun saveAllAiImages(images: List<File>) {
        if (images.isEmpty()) {
            toastOnUi("暂无图片可保存")
            return
        }
        val targetDir = java.io.File("/storage/emulated/0/Download/AI生图/")
        if (!targetDir.exists()) targetDir.mkdirs()
        var success = 0
        images.forEach { f ->
            if (f.exists()) {
                try {
                    val target = java.io.File(targetDir, "${f.lastModified()}.png")
                    f.copyTo(target, overwrite = true)
                    success++
                } catch (_: Exception) {}
            }
        }
        toastOnUi("已保存 $success/${images.size} 张到 ${targetDir.absolutePath}")
    }

    /**
     * 判断文件是否为 GIF 动图（按扩展名，不区分大小写）。
     */
    private fun isGifFile(file: File): Boolean {
        return file.extension.equals("gif", ignoreCase = true)
    }

    /**
     * 加载图片文件到 ImageView，fitCenter 模式：保持比例放大到最大，不裁剪。
     * GIF 复用书架封面同款 Glide 加载链路（ImageLoader + into(view)），本构建可正常播放动画。
     * 静态图仍用 Glide into(view)；placeholder 保留当前图，避免切换时短暂空白。
     */
    private fun loadImage(iv: ImageView, file: File) {
        if (isGifFile(file)) {
            // GIF：复用书架封面同款 Glide 加载链路（ImageLoader + into(view)），会自动播放动画
            iv.loadGif(file, ImageView.ScaleType.FIT_CENTER)
            return
        }
        val placeholder = iv.drawable
        Glide.with(iv.context)
            .load(file)
            .fitCenter()
            .placeholder(placeholder)
            .into(iv)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        stopLocalImageTimer()
        cancelAiHistoryReset()
        // 清理屏幕超时计时器，避免泄漏；退出后由朗读界面/阅读界面自行管理屏幕状态
        screenOffHandler.removeCallbacks(screenOffRunnable)
    }

    /**
     * 读取"屏幕超时"设置并应用到本全屏界面的 window。
     * 与 ReadBookActivity 保持一致：常亮保持常亮；设定时间大于系统息屏时间则在该时间后允许息屏；默认使用系统设置。
     */
    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toIntOrNull() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 根据 [screenTimeOut] 控制 Dialog window 的保持屏幕常亮标志。
     */
    private fun screenOffTimerStart() {
        screenOffHandler.post {
            if (screenTimeOut < 0) {
                keepDialogScreenOn(true)
                return@post
            }
            val t = screenTimeOut - requireContext().sysScreenOffTime
            if (t > 0) {
                keepDialogScreenOn(true)
                screenOffHandler.removeCallbacks(screenOffRunnable)
                screenOffHandler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepDialogScreenOn(false)
            }
        }
    }

    /** 给本 Dialog 的 window 添加/清除保持屏幕常亮标志 */
    private fun keepDialogScreenOn(on: Boolean) {
        val window = dialog?.window ?: return
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
