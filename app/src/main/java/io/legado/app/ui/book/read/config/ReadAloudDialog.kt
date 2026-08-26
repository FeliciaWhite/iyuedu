package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.loadGif
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.BookCover
import io.legado.app.help.BgmMarqueeSpeed
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Color
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.BgmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.service.HttpReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.*
import io.legado.app.ui.book.read.page.DialogRoleManager
import java.io.File
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Timer
import java.util.TimerTask
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog

class ReadAloudDialog : BaseDialogFragment(R.layout.dialog_read_aloud) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：使用 dialog_style_material（标准不透明主题），而非默认的 0（会继承
        // 阅读页 Activity 的透明状态栏主题）。只有标准不透明主题下，
        // statusBarColor / FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS 才会生效，
        // 全屏背景图才能真正延伸到状态栏之下并遮住底层阅读页文字。
        setStyle(STYLE_NO_FRAME, R.style.dialog_style_material)
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)
    private var isSeekingChapterProgress = false
    private var oldBgmStartedCallback: (() -> Unit)? = null
    private var imageTimer: Timer? = null
    private var currentImageIndex = 0
    private var bookImageFiles: List<File> = emptyList()

    /** AI 生图历史浏览：当前显示的历史图片索引（在 aiHistoryImages 中的位置） */
    private var aiHistoryImages: List<File> = emptyList()
    private var aiHistoryIndex = -1
    /** 当前窗口是否为全屏模式（由 applyWindowMode 维护）；用于手势判定半屏专属操作 */
    private var isFullscreen = false
    /** 浏览历史图片后，30秒无操作自动回到最新图片的定时器 */
    private var aiHistoryResetTimer: Timer? = null

    /**
     * 加载图片文件并按 View 的宽高比从图片中间裁剪，使裁剪后图片比例与 View 完全一致，
     * 避免不同比例图片被拉伸变形。View 尺寸保持不变。
     * 裁剪在后台线程执行，完成后切回主线程做交叉淡入显示。
     */
    /**
     * 判断文件是否为 GIF 动图（按扩展名，不区分大小写）。
     */
    private fun isGifFile(file: File): Boolean {
        return file.extension.equals("gif", ignoreCase = true)
    }

    private fun loadImageWithCenterCrop(iv: ImageView, file: File) {
        if (isGifFile(file)) {
            // GIF：复用书架封面同款 Glide 加载链路（ImageLoader + into(view)），本构建可正常播放动画
            iv.loadGif(file, ImageView.ScaleType.CENTER_CROP)
            return
        }
        val viewW = iv.layoutParams.width
        val viewH = iv.layoutParams.height
        if (viewW <= 0 || viewH <= 0) {
            // View 尺寸还未确定，退回直接加载
            loadWithCrossFade(iv, file)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val croppedBitmap = withContext(Dispatchers.IO) {
                centerCropBitmapFromFile(file, viewW, viewH)
            }
            if (croppedBitmap != null) {
                loadWithCrossFade(iv, croppedBitmap)
            } else {
                // 裁剪失败（如文件损坏），退回直接加载
                loadWithCrossFade(iv, file)
            }
        }
    }

    // 全屏背景图与主预览同步显示同一张图（延伸到状态栏）
    private fun loadIntoBoth(file: File) {
        loadImageWithCenterCrop(binding.ivBookCover, file)
        binding.ivFullscreenBg.let { bg ->
            if (isGifFile(file)) bg.loadGif(file, ImageView.ScaleType.CENTER_CROP)
            else Glide.with(bg).load(file).into(bg)
        }
    }

    private fun loadIntoBoth(model: Any?) {
        loadWithCrossFade(binding.ivBookCover, model)
        binding.ivFullscreenBg.let { bg ->
            Glide.with(bg).load(model).into(bg)
        }
    }

    /** 直接加载已配置好 placeholder/error 的 RequestBuilder（用于书籍封面） */
    private fun loadIntoBoth(builder: com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable>) {
        builder.into(binding.ivBookCover)
        binding.ivFullscreenBg.let { bg ->
            Glide.with(bg).load(builder).into(bg)
        }
    }

    /**
     * 从图片文件解码并按目标宽高比从中间裁剪。
     * - 读取图片实际尺寸，计算 inSampleSize 降采样防止 OOM
     * - 按目标比例（targetW:targetH）从图片中间裁出最大区域
     * - 返回裁剪后的 Bitmap，比例与目标一致
     */
    private fun centerCropBitmapFromFile(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            // 1. 先读尺寸
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val imgW = boundsOpts.outWidth
            val imgH = boundsOpts.outHeight
            if (imgW <= 0 || imgH <= 0) return null

            // 2. 计算降采样倍数，避免大图 OOM（目标 View 尺寸的 2 倍即可保证清晰度）
            val reqW = targetW * 2
            val reqH = targetH * 2
            var sampleSize = 1
            if (imgW > reqW || imgH > reqH) {
                val halfW = imgW / 2
                val halfH = imgH / 2
                while (halfW / sampleSize >= reqW && halfH / sampleSize >= reqH) {
                    sampleSize *= 2
                }
            }

            // 3. 降采样解码
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            val sW = sampled.width
            val sH = sampled.height

            // 4. 按目标宽高比从中间裁剪
            val targetRatio = targetW.toFloat() / targetH.toFloat()
            val srcRatio = sW.toFloat() / sH.toFloat()
            val cropX: Int
            val cropY: Int
            val cropW: Int
            val cropH: Int
            if (srcRatio > targetRatio) {
                // 图片更宽：高度填满，左右裁掉
                cropH = sH
                cropW = (sH * targetRatio).toInt()
                cropX = (sW - cropW) / 2
                cropY = 0
            } else if (srcRatio < targetRatio) {
                // 图片更高：宽度填满，上下裁掉
                cropW = sW
                cropH = (sW / targetRatio).toInt()
                cropX = 0
                cropY = (sH - cropH) / 2
            } else {
                // 比例一致，不裁
                return sampled
            }
            Bitmap.createBitmap(sampled, cropX, cropY, cropW, cropH)
        } catch (e: Exception) {
            null
        }
    }

    /** 使用后台加载实现交叉淡入：旧图片继续显示，新图片加载完成后叠加，800ms 过渡 */
    private fun loadWithCrossFade(imageView: ImageView, model: Any?) {
        val currentDrawable = imageView.drawable ?: run {
            Glide.with(imageView.context).load(model).into(imageView)
            return
        }
        Glide.with(imageView.context)
            .asDrawable()
            .load(model)
            .override(Target.SIZE_ORIGINAL)
            .into(object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (resource is Animatable) {
                        imageView.setImageDrawable(resource)
                        resource.start()
                        return
                    }
                    val transitionDrawable = TransitionDrawable(
                        arrayOf(currentDrawable, resource)
                    )
                    transitionDrawable.isCrossFadeEnabled = true
                    imageView.setImageDrawable(transitionDrawable)
                    transitionDrawable.startTransition(800)
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    // 不清理，保留当前图片避免空白
                }
            })
    }

    /** 对 RequestBuilder 版本做交叉淡入 */
    private fun loadWithCrossFade(imageView: ImageView, builder: RequestBuilder<Drawable>) {
        val currentDrawable = imageView.drawable ?: run {
            builder.into(imageView)
            return
        }
        builder.into(object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (resource is Animatable) {
                    imageView.setImageDrawable(resource)
                    resource.start()
                    return
                }
                val transitionDrawable = TransitionDrawable(
                    arrayOf(currentDrawable, resource)
                )
                transitionDrawable.isCrossFadeEnabled = true
                imageView.setImageDrawable(transitionDrawable)
                transitionDrawable.startTransition(800)
            }
            override fun onLoadCleared(placeholder: Drawable?) {
                // 不清理，保留当前图片避免空白
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // 根据是否显示封面字幕面板，选择全屏延伸 / 底部面板两套窗口模式
        applyWindowMode(BaseReadAloudService.isRun && AppConfig.showReadAloudCoverSubtitle)
    }

    /**
     * 两套窗口模式：
     * - showImage = true （显示封面字幕面板）：全屏窗口，背景图延伸到状态栏之下，
     *   彻底遮住底层阅读页文字，呈现沉浸式大图效果。
     * - showImage = false（关闭封面显示）：底部面板模式，只占屏幕下半部分，
     *   不延伸到状态栏，露出上方原文，不遮挡阅读内容。
     */
    @SuppressLint("WrongConstant")
    private fun applyWindowMode(showImage: Boolean) {
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            isFullscreen = showImage
            val hideIcons = ReadBookConfig.hideStatusBar
            if (showImage) {
                // 全屏延伸模式
                decorView.setPadding(0, 0, 0, 0)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                val attr = attributes
                attr.gravity = Gravity.FILL
                attr.dimAmount = 0.0f
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowInsetsControllerCompat(this, decorView).apply {
                    if (hideIcons) {
                        hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    } else {
                        show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    }
                    // 黑色状态栏图标，在白色主题/浅色背景上清晰可见
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            } else {
                // 底部面板模式：只占屏幕下半部分（WRAP_CONTENT + BOTTOM），
                // 上半屏空白不在窗口内，且通过 FLAG_NOT_TOUCH_MODAL 让窗口外触摸穿透到下层阅读页，
                // 可直接点字翻页、操作原文。
                // 必须彻底复位全屏延伸模式对 decorView / 系统栏的改动，否则穿透会失效：
                WindowCompat.setDecorFitsSystemWindows(this, true)
                decorView.setPadding(0, 0, 0, 0)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                // 让窗口外（上半屏）触摸穿透到下层阅读页
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                WindowInsetsControllerCompat(this, decorView).apply {
                    show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
                val attr = attributes
                attr.gravity = Gravity.BOTTOM
                attr.dimAmount = 0.0f
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
        binding.ivBookCover.setMarqueeEnabled(false)
        BgmManager.onBgmStarted = oldBgmStartedCallback
        oldBgmStartedCallback = null
        stopImageTimer()
        cancelAiHistoryReset()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.run {
            // 根布局保持透明：图片层全屏延伸状态栏，仅底部 content_panel 不透明，
            // 避免整屏不透明背景遮盖原文阅读区（关闭/无图时露出原文）
            contentPanel.setBackgroundColor(bg)
            tvPre.setTextColor(textColor)
            tvNext.setTextColor(textColor)
            ivPlayPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivPlayNext.setColorFilter(textColor)
            ivStop.setColorFilter(textColor)
            ivTimer.setColorFilter(textColor)
            tvTimer.setTextColor(textColor)
            ivTtsSpeechReduce.setColorFilter(textColor)
            tvTtsSpeed.setTextColor(textColor)
            tvTtsSpeedValue.setTextColor(textColor)
            ivTtsSpeechAdd.setColorFilter(textColor)
            ivCatalog.setColorFilter(textColor)
            tvCatalog.setTextColor(textColor)
            ivMainMenu.setColorFilter(textColor)
            tvMainMenu.setTextColor(textColor)
            ivToBackstage.setColorFilter(textColor)
            tvToBackstage.setTextColor(textColor)
            ivSetting.setColorFilter(textColor)
            tvSetting.setTextColor(textColor)
            cbTtsFollowSys.setTextColor(textColor)
            tvReadAloudSubtitle.setTextColor(textColor)
            tvChapterProgressStart.setTextColor(textColor)
            tvChapterProgressEnd.setTextColor(textColor)
        }
        applyChapterProgressVisibility()
        initData()
        initEvent()
        initSwipeToDismiss()
        // 根据系统导航栏高度动态调整底部 padding，避免三键导航遮挡按钮
        binding.contentPanel.applyNavigationBarPadding(withInitialPadding = true)
    }

    /**
     * 全屏模式的退出手势：自上而下滑动 → 退出朗读界面。
     * 半屏模式不挂任何 decorView 触摸监听（让上半屏空白触摸穿透到下层阅读页，
     * 可直接点字翻页、操作原文），故不在此拦截。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeToDismiss() {
        if (!isFullscreen) return
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var startY = 0f
        var handled = false
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                startY = e.y
                handled = false
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (handled) return false
                val dy = e2.y - startY
                // 从上往下滑动 → 退出
                if (dy > touchSlop * 3) {
                    dismiss()
                    handled = true
                    return true
                }
                return false
            }
        })
        dialog?.window?.decorView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    /**
     * 根据「隐藏章节进度条」设置控制进度条可见性。
     * 开关默认开启（hide=true）→ 彻底移除进度条及其占用的空间（GONE），让图片更大；
     * 关闭 → 显示进度条。
     * 注意：不能用 visible(false)（那是 INVISIBLE，会保留空间）。
     */
    private fun applyChapterProgressVisibility() = binding.run {
        llChapterProgress.visibility =
            if (AppConfig.readAloudHideChapterProgress) View.GONE else View.VISIBLE
    }

    private fun initData() = binding.run {
        upPlayState()
        upTimerText(BaseReadAloudService.timeMinute)
        cbTtsFollowSys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        upTtsSpeechRateEnabled(!cbTtsFollowSys.isChecked)
        upSeekTimer()
        upTopSection()
        // 主动请求当前字幕，解决暂停/初始进入时字幕空白问题
        HttpReadAloudService.requestCurrentSubtitle()
        // 设置 BGM 切换回调，联动跑马灯速度
        // 必须链式调用旧回调（BaseReadAloudService 的 triggerBgmAnalysis），否则 AI 预分析会被截断
        oldBgmStartedCallback = BgmManager.onBgmStarted
        BgmManager.onBgmStarted = {
            oldBgmStartedCallback?.invoke()
            if (AppConfig.readAloudCoverMarqueeEnabled) {
                val baseSpeed = AppConfig.readAloudCoverMarqueeSpeed.coerceIn(500, 10000)
                val bgmName = BgmManager.getCurrentBgmName()
                val finalSpeed = BgmMarqueeSpeed.calculateSpeed(baseSpeed, bgmName)
                ivBookCover.setMarqueeSpeed(finalSpeed)
            }
        }
    }

    /**
     * 初始化上半部分：仅 Http 转发器模式显示，且受设置开关控制
     */
    private fun upTopSection() = binding.run {
        val show = BaseReadAloudService.isRun && AppConfig.showReadAloudCoverSubtitle
        llReadAloudTop.visible(show)
        if (show) {
            // 用户设定尺寸：不再限制图片最大高度，图片完全按设定宽度与比例显示，可设得更大。
            // llReadAloudTop 使用 layout_weight=1 自动占据屏幕剩余空间，下方内容不会被挤掉。
            val widthDp = AppConfig.readAloudCoverWidth.coerceIn(80, 600)
            val heightDp = (widthDp * 340f / 240f).toInt()
            val widthPx = widthDp.dpToPx()
            val heightPx = heightDp.dpToPx()

            ivBookCover.layoutParams = ivBookCover.layoutParams.apply {
                width = widthPx
                height = heightPx
            }
            // 应用「图片与顶部的距离」设置（dp）
            val topMarginPx = AppConfig.readAloudCoverTopMargin.coerceAtLeast(0).dpToPx()
            (ivBookCover.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = topMarginPx
            }

            // 初始化跑马灯边框（直接画在封面 View 上，避免层级遮挡问题）
            // 仅在朗读播放中才启动跑马灯，暂停时不启动
            if (AppConfig.readAloudCoverMarqueeEnabled && !BaseReadAloudService.pause) {
                val baseSpeed = AppConfig.readAloudCoverMarqueeSpeed.coerceIn(500, 10000)
                val bgmName = BgmManager.getCurrentBgmName()
                val finalSpeed = BgmMarqueeSpeed.calculateSpeed(baseSpeed, bgmName)
                ivBookCover.setMarqueeSpeed(finalSpeed)
                ivBookCover.setMarqueeEnabled(true)
            } else {
                ivBookCover.setMarqueeEnabled(false)
            }

            // 加载书籍封面或 AI 生图或小说文件夹图片
            ReadBook.book?.let { book ->
                if (AppConfig.readAloudAiImage) {
                    // AI生图已改为字数驱动，优先显示最近生成的图片
                    val lastImage = AiImageGenerator.getLastGeneratedImage()
                    if (lastImage != null) {
                        loadIntoBoth(lastImage)
                    } else {
                        // AI 生图开启但没缓存时，显示书籍封面
                        loadIntoBoth(BookCover.load(requireContext(), book.getDisplayCover()))
                    }
                    stopImageTimer()
                    // 始终请求 Service 同步当前场景图片：即使本地有缓存图片，
                    // Service 可能已切换到更新的场景，需让其通知最新图片，避免退出重进时显示过期或默认封面
                    postEvent(EventBus.AI_IMAGE_REQUEST, "0")
                } else {
                    loadBookCoverOrLocalImages(book, ivBookCover)
                }
                // 设置书名
                tvBookName.text = book.name
            }
            // 设置章节名
            tvChapterName.text = ReadBook.curTextChapter?.title ?: ""
            // 初始化进度条位置（使用阅读位置估算，服务会随后推送精确值）
            ReadBook.curTextChapter?.let { tc ->
                val total = tc.getContent().length.coerceAtLeast(1)
                val current = ReadBook.durChapterPos.coerceIn(0, total)
                val progress = (current.toFloat() / total * 1000).toInt()
                seekChapterProgress.progress = progress
                tvChapterProgressStart.text = "${(progress / 10f).toInt()}%"
            }
            // 同步全屏背景图可见性
            ivFullscreenBg.visible(show)
        } else {
            // 关闭封面显示：隐藏全屏背景图
            ivFullscreenBg.gone()
        }
        // 根据开关切换窗口模式（全屏延伸 / 底部面板）
        if (dialog?.isShowing == true) {
            applyWindowMode(show)
        }
    }

    /**
     * 加载书籍封面或扫描小说文件夹中的图片
     */
    private fun loadBookCoverOrLocalImages(book: io.legado.app.data.entities.Book, ivBookCover: io.legado.app.ui.widget.image.CoverImageView) {
        // 扫描小说文件夹中的图片
        val bookImages = scanBookImages(book.name)
        if (bookImages.isNotEmpty()) {
            bookImageFiles = bookImages
            currentImageIndex = 0
            // 显示第一张图片
            loadIntoBoth(bookImageFiles[0])
            // 启动定时器轮换图片
            startImageTimer(ivBookCover)
        } else {
            // 没有本地图片，显示书籍封面（使用带占位/错误图的加载链路，避免白屏）
            loadIntoBoth(BookCover.load(requireContext(), book.getDisplayCover()))
            stopImageTimer()
        }
    }

    /**
     * 扫描小说文件夹中的图片文件（实现见 [DialogRoleManager.scanBookImages]）
     */
    private fun scanBookImages(bookName: String): List<File> {
        return DialogRoleManager.scanBookImages(bookName)
    }

    /**
     * 启动图片轮换定时器
     */
    private fun startImageTimer(ivBookCover: io.legado.app.ui.widget.image.CoverImageView) {
        stopImageTimer()
        val intervalSec = AppConfig.readAloudImageInterval.coerceAtLeast(0)
        if (intervalSec <= 0) return // 0 表示不自动轮播，仅手动切换
        if (bookImageFiles.size <= 1) return
        val period = intervalSec * 1000L
        imageTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    activity?.runOnUiThread {
                        currentImageIndex = (currentImageIndex + 1) % bookImageFiles.size
                        val nextImage = bookImageFiles[currentImageIndex]
                        loadIntoBoth(nextImage)
                    }
                }
            }, period, period)
        }
    }

    /**
     * 停止图片轮换定时器
     */
    private fun stopImageTimer() {
        imageTimer?.cancel()
        imageTimer = null
    }

    /**
     * 手动切换本地小说文件夹图片。direction: 1 上一张，-1 下一张（循环）。
     * 切换后重置轮播定时器，5 秒后继续自动轮播。
     */
    private fun switchLocalImage(direction: Int) {
        if (bookImageFiles.isEmpty()) return
        val size = bookImageFiles.size
        // 本地图片的方向约定与 AI 历史相反，取反使 direction=-1=下一张、1=上一张，与手势一致
        currentImageIndex = (currentImageIndex - direction + size) % size
        val file = bookImageFiles[currentImageIndex]
        if (file.exists()) {
            loadIntoBoth(file)
        }
        // 手动切换后重新启动轮播定时器
        startImageTimer(binding.ivBookCover)
    }

    private fun initEvent() = binding.run {
        // 点击字幕区域：进入全屏 AI 图片预览界面
        // 使用 activity 的 supportFragmentManager 而非 childFragmentManager，
        // 使全屏 Dialog 获得独立的顶层窗口，不被本 Dialog（底部定位、WRAP_CONTENT）的窗口范围限制，
        // 避免顶部透出底层阅读页文字
        tvReadAloudSubtitle.setOnClickListener {
            FullscreenAiImageDialog().show(requireActivity().supportFragmentManager, "fullscreenAiImageDialog")
        }
        llMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        llSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        llCatalog.setOnClickListener { callBack?.openChapterList() }
        llBgm.setOnClickListener { callBack?.showBgmConfig() }
        llToBackstage.setOnClickListener { callBack?.finish() }
        llCharacterManager.setOnClickListener {
            CharacterManagerDialog().show(childFragmentManager, "characterManagerDialog")
        }
        llConfigList.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), io.legado.app.ui.tts.plugin.TtsPluginActivity::class.java))
        }
        // 打开当前 TTS 应用
        llOpenTtsApp.setOnClickListener {
            openCurrentTtsApp()
        }
        // 打开朗读引擎选择页面
        llSpeakEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        // 朗读引擎 - 长按弹出清理缓存弹窗
        llSpeakEngine.setOnLongClickListener {
            SpeakEngineDialog.showClearCacheDialog(requireContext())
            true
        }
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
        ivTtsSpeechReduce.setOnClickListener {
            seekTtsSpeechRate.progress = AppConfig.ttsSpeechRate - 1
            AppConfig.ttsSpeechRate -= 1
            upTtsSpeechRate()
        }
        ivTtsSpeechAdd.setOnClickListener {
            seekTtsSpeechRate.progress = AppConfig.ttsSpeechRate + 1
            AppConfig.ttsSpeechRate += 1
            upTtsSpeechRate()
        }
        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.progress
            toastOnUi("保存设定时间成功！")
        }
        tvTimer.setOnClickListener {
            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
            val timeKeys = times.map { "$it 分钟" }
            context?.selector("设定时间", timeKeys) { _, index ->
                ReadAloud.setTimer(requireContext(), times[index])
            }
        }
        //设置保存的默认值
        seekTtsSpeechRate.progress = AppConfig.ttsSpeechRate
        seekTtsSpeechRate.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                super.onProgressChanged(seekBar, progress, fromUser)
                upTtsSpeechRateText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.ttsSpeechRate = seekBar.progress
                upTtsSpeechRate()
            }
        })
        seekTimer.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                upTimerText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ReadAloud.setTimer(requireContext(), seekTimer.progress)
            }
        })
        // 章节进度条拖动
        seekChapterProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isSeekingChapterProgress = true
                    tvChapterProgressStart.text = "${(progress / 10f).toInt()}%"
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeekingChapterProgress = false
                seekToChapterProgress(seekBar.progress)
            }
        })
        // 监听朗读播放/暂停状态，同步控制跑马灯：暂停时保持在当前位置，不消失
        observeEvent<Int>(EventBus.ALOUD_STATE) { state ->
            when (state) {
                Status.PLAY -> {
                    if (AppConfig.readAloudCoverMarqueeEnabled) {
                        ivBookCover.resumeMarquee()
                    }
                }
                Status.PAUSE -> {
                    ivBookCover.pauseMarquee()
                }
                Status.STOP -> {
                    ivBookCover.stopMarquee()
                }
            }
        }
    }

    /**
     * 根据进度条位置跳转朗读：直接发送段落跳转事件，不停止播放
     * @param progress 千分比 0~1000
     */
    private fun seekToChapterProgress(progress: Int) {
        postEvent(EventBus.READ_ALOUD_SEEK_PARAGRAPH, progress)
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(AppConfig.ttsSpeechRate)
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.ivPlayPause.setColorFilter(textColor)
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            if (BaseReadAloudService.timeMinute > 0) {
                binding.seekTimer.progress = BaseReadAloudService.timeMinute
            } else {
                binding.seekTimer.progress = AppConfig.ttsTimer
            }
        }

        // AI 生图图片：滑动手势 + 长按菜单
        setupAiImageGesture()
    }

    /**
     * 为图片设置手势（AI 生图与本地图片轮播通用）：
     * - 左右滑动：切换上一张 / 下一张
     * - 单击左 1/3：上一张；单击右 1/3：下一张
     * - 单击中 1/3：进入全屏图片预览（两种模式通用）
     * - 长按：仅 AI 生图模式弹出菜单选择保存当前图片或保存全部
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAiImageGesture() {
        val iv = binding.ivBookCover
        // 记录按下起点，用于 onScroll 判断方向
        var downX = 0f
        var downY = 0f
        var moved = false
        val gesture = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 若发生了滑动切换则不触发单击动作
                if (moved) return false
                val viewWidth = iv.width.takeIf { it > 0 } ?: return false
                when {
                    e.x < viewWidth / 3f -> {
                        // 左 1/3：上一张图片（AI/本地通用）
                        if (AppConfig.readAloudAiImage) switchAiHistoryImage(1) else switchLocalImage(1)
                    }
                    e.x > viewWidth * 2f / 3f -> {
                        // 右 1/3：下一张图片（AI/本地通用）
                        if (AppConfig.readAloudAiImage) switchAiHistoryImage(-1) else switchLocalImage(-1)
                    }
                    else -> {
                        // 中 1/3：进入全屏图片预览（两种模式通用）
                        FullscreenAiImageDialog().show(requireActivity().supportFragmentManager, "fullscreenAiImageDialog")
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
                val dx = e2.x - startX
                val dy = e2.y - (e1?.y ?: downY)
                // 横向位移需大于纵向，且超过阈值即切换（大范围慢速滑动也能触发）
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
                    if (AppConfig.readAloudAiImage) {
                        if (dx > 0) {
                            // 从左向右滑：查看之前生成的图片（更旧的）
                            switchAiHistoryImage(1)
                        } else {
                            // 从右向左滑：查看后面的图片（更新的）
                            switchAiHistoryImage(-1)
                        }
                    } else {
                        if (dx > 0) switchLocalImage(1) else switchLocalImage(-1)
                    }
                    moved = true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                // 切换逻辑已在 onScroll 中处理，Fling 不再重复触发
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                // 若发生了滑动则不触发长按菜单
                if (moved) return
                val viewWidth = iv.width.takeIf { it > 0 } ?: return
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
     * 滑动后停留在目标图片，并启动 30 秒倒计时，无操作则自动回到最新图片恢复自动生成切换。
     */
    private fun switchAiHistoryImage(direction: Int) {
        if (aiHistoryImages.isEmpty()) {
            aiHistoryImages = AiImageGenerator.getDisplayGallery()
            if (aiHistoryImages.isEmpty()) {
                toastOnUi("暂无历史图片")
                return
            }
            // 当前显示的是最新一张
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
            loadIntoBoth(file)
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
                            loadIntoBoth(latest)
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
        // 图片宽度设置 / 图片与顶部距离设置：与朗读界面设置联通
        items.add("图片宽度设置")
        actions.add { showCoverWidthSetting() }
        items.add("图片与顶部距离设置")
        actions.add { showCoverTopMarginSetting() }
        if (AppConfig.readAloudAiImage) {
            items.add("保存当前图片")
            actions.add { saveCurrentAiImage() }
            items.add("保存全部图片")
            actions.add { showSaveAllMenu() }
            // 缓存数量与清空临时缓存（AI 生图相关，仅开关开启时显示）
            items.add("设置缓存数量")
            actions.add { showCacheCountSetting() }
            items.add("清空临时缓存图片")
            actions.add { confirmClearTempCache() }
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("图片设置")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
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
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清空临时缓存图片")
            .setMessage("将删除 AI 生图临时缓存（最多缓存的 ${AppConfig.aiImageCacheCount} 张），不影响已保存到本地的图片，确定？")
            .setPositiveButton("清空") { _, _ ->
                val deleted = AiImageGenerator.clearTempCache()
                // 重置画廊，回退到书籍封面（与「AI 生图开启但无缓存」一致）
                aiHistoryImages = emptyList()
                aiHistoryIndex = -1
                stopImageTimer()
                ReadBook.book?.let { book ->
                    loadWithCrossFade(binding.ivBookCover, BookCover.load(requireContext(), book.getDisplayCover()))
                }
                toastOnUi("已清空 $deleted 张临时缓存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 轮播时间设置：输入秒数（0=不自动轮播），保存后重启轮播定时器。
     */
    private fun showIntervalSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudImageInterval.toString())
            hint = "秒数，0=不自动轮播"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("轮播时间设置（秒）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val sec = editText.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 5
                AppConfig.readAloudImageInterval = sec
                // 重启轮播定时器以应用新间隔（0 则不轮播，仅手动切换）
                startImageTimer(binding.ivBookCover)
                val tip = if (sec <= 0) "已设为不自动轮播（仅手动切换）" else "已设为 ${sec} 秒轮播"
                toastOnUi(tip)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 图片宽度设置：与朗读界面设置（readAloudCoverWidth）联通。
     * 保存后重新布局上半部分以应用新宽度。
     */
    private fun showCoverWidthSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudCoverWidth.toString())
            hint = "宽度（dp），范围 80~600"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("图片宽度设置（dp）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val width = editText.text.toString().toIntOrNull()?.coerceIn(80, 600) ?: 240
                AppConfig.readAloudCoverWidth = width
                upTopSection()
                toastOnUi("图片宽度已设为 ${width}dp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 图片与顶部距离设置：与朗读界面设置（readAloudCoverTopMargin）联通。
     * 保存后重新布局上半部分以应用新顶部距离。
     */
    private fun showCoverTopMarginSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudCoverTopMargin.toString())
            hint = "距离顶部（dp），默认 16"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("图片与顶部距离（dp）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val margin = editText.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 16
                AppConfig.readAloudCoverTopMargin = margin
                upTopSection()
                toastOnUi("图片与顶部距离已设为 ${margin}dp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存当前显示的 AI 图片 */
    private fun saveCurrentAiImage() {
        val currentFile = if (aiHistoryImages.isNotEmpty() && aiHistoryIndex in aiHistoryImages.indices) {
            aiHistoryImages[aiHistoryIndex]
        } else {
            AiImageGenerator.getCurrentDisplayImage()
        }
        saveAiImage(currentFile)
    }

    /**
     * 长按 AI 图片：弹出菜单选择保存当前图片或保存全部。
     */
    private fun showSaveMenu() {
        val currentFile = if (aiHistoryImages.isNotEmpty() && aiHistoryIndex in aiHistoryImages.indices) {
            aiHistoryImages[aiHistoryIndex]
        } else {
            AiImageGenerator.getCurrentDisplayImage()
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
        val allImages = AiImageGenerator.getDisplayGallery()
        if (allImages.isEmpty()) {
            toastOnUi("暂无 AI 生图可保存")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("保存所有 AI 生图")
            .setMessage("将保存 ${allImages.size} 张历史图片到 /storage/emulated/0/Download/AI生图/，是否继续？")
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
                    // 用缓存文件的时间戳命名，重复保存也只会覆盖同名文件，不会产生重复
                    val target = java.io.File(targetDir, "${f.lastModified()}.png")
                    f.copyTo(target, overwrite = true)
                    success++
                } catch (_: Exception) {}
            }
        }
        toastOnUi("已保存 $success/${images.size} 张到 ${targetDir.absolutePath}")
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    @SuppressLint("SetTextIsNull")
    private fun upTtsSpeechRateText(value: Int) {
        binding.tvTtsSpeedValue.text = ((value + 5) / 10f).toString()
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { binding.seekTimer.progress = it }
        observeEvent<String>(EventBus.READ_ALOUD_SUBTITLE) {
            binding.tvReadAloudSubtitle.text = it
        }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER_PROGRESS) {
            if (!isSeekingChapterProgress) {
                binding.seekChapterProgress.progress = it
                binding.tvChapterProgressStart.text = "${(it / 10f).toInt()}%"
            }
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_CONFIG_CHANGED) {
            upTopSection()
        }
        observeEvent<String>(EventBus.READ_ALOUD_CHAPTER_CHANGED) {
            // 章节变化时更新章节名
            binding.tvChapterName.text = ReadBook.curTextChapter?.title ?: it
        }
        observeEvent<String>(EventBus.AI_IMAGE_CHANGED) { imagePath ->
            if (imagePath.isNotBlank() && File(imagePath).exists()) {
                // 用户正在浏览历史图片时不打断，仅刷新列表数据，不重置索引和显示
                if (aiHistoryIndex > 0) {
                    aiHistoryImages = AiImageGenerator.getDisplayGallery()
                    return@observeEvent
                }
                stopImageTimer() // AI 生图开启时不进行本地图片轮换
                loadIntoBoth(File(imagePath))
                // 新图片生成，重置历史浏览到最新一张，并取消回最新定时器
                cancelAiHistoryReset()
                aiHistoryImages = AiImageGenerator.getDisplayGallery()
                aiHistoryIndex = 0
            } else {
                // 恢复书籍封面或本地图片
                ReadBook.book?.let { book ->
                    loadBookCoverOrLocalImages(book, binding.ivBookCover)
                }
                cancelAiHistoryReset()
                aiHistoryImages = emptyList()
                aiHistoryIndex = -1
            }
        }
    }

    /**
     * 打开当前 TTS 引擎对应的应用
     */
    @SuppressLint("SetTextIsNull")
    private fun openCurrentTtsApp() {
        val ttsEngine = ReadAloud.ttsEngine

        // 1. 转发器模式（数字 ID）
        if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            val httpTtsId = ttsEngine.toLongOrNull()
            if (httpTtsId != null) {
                val httpTts = appDb.httpTTSDao.get(httpTtsId)
                val packageName = httpTts?.ttsPackageName
                if (!packageName.isNullOrBlank()) {
                    openTtsAppByPackageName(packageName)
                } else {
                    toastOnUi("当前为转发器模式，请在角色管理中配置TTS包名")
                }
            } else {
                openSystemTtsApp()
            }
            return
        }

        // 2. 系统 TTS 模式（空/null 或 JSON）
        // 优先从 JSON 解析包名，其次使用手动设置的包名
        val packageName = when {
            ttsEngine.isNullOrBlank() -> AppConfig.sysTtsPackageName
            ttsEngine.startsWith("{") -> {
                GSON.fromJsonObject<io.legado.app.lib.dialogs.SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value?.takeIf { it.isNotBlank() }
                    ?: AppConfig.sysTtsPackageName
            }
            else -> ttsEngine.takeIf { it.isNotBlank() }
        }

        if (!packageName.isNullOrBlank()) {
            openTtsAppByPackageName(packageName)
        } else {
            openSystemTtsApp()
        }
    }

    /**
     * 打开系统 TTS 设置页面
     */
    private fun openSystemTtsApp() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toastOnUi("无法打开系统设置")
        }
    }

    /**
     * 根据包名打开 TTS 应用
     */
    private fun openTtsAppByPackageName(packageName: String) {
        try {
            val packageManager = requireContext().packageManager
            // 尝试获取应用启动 Intent
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                // 如果没有启动 Intent，尝试打开应用详情页面
                openAppDetailsPage(packageName)
            }
        } catch (e: Exception) {
            toastOnUi("无法打开应用: $packageName")
        }
    }

    /**
     * 根据 TTS 引擎名称打开应用
     */
    private fun openTtsAppByEngineName(engineName: String) {
        try {
            val packageManager = requireContext().packageManager
            // 获取所有 TTS 引擎
            val ttsIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val resolveInfos = packageManager.queryIntentActivities(ttsIntent, 0)

            for (info in resolveInfos) {
                // 获取引擎的包名
                val enginePackageName = info.activityInfo.packageName
                try {
                    val appInfo = packageManager.getApplicationInfo(enginePackageName, 0)
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    // 检查是否匹配（模糊匹配）
                    if (appName.contains(engineName) || engineName.contains(appName)) {
                        openTtsAppByPackageName(enginePackageName)
                        return
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            // 如果没找到匹配，尝试直接打开应用详情页
            openAppDetailsPage(engineName)
        } catch (e: Exception) {
            toastOnUi("无法找到对应的 TTS 应用")
        }
    }

    /**
     * 打开应用详情页面
     */
    private fun openAppDetailsPage(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 如果 ACTION_APPLICATION_DETAILS_SETTINGS 不可用，尝试打开市场页面
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                toastOnUi("无法打开应用详情")
            }
        }
    }

    private fun showAiImageSettings() {
        AiImageSettingsDialog().show(childFragmentManager, "aiImageSettingsDialog")
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun showBgmConfig()
        fun finish()
    }
}