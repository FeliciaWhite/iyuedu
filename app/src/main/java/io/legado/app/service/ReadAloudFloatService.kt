package io.legado.app.service

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import io.legado.app.ui.widget.image.CircleImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startActivity
import androidx.core.animation.doOnEnd
import kotlin.math.abs

class ReadAloudFloatService : BaseService() {

    companion object {
        private const val TAG = "ReadAloudFloatService"

        /** 长按判定阈值 */
        private const val LONG_CLICK_TIMEOUT = 500L

        /** ReadBookActivity 是否在前台 */
        @Volatile
        var isReadBookActivityForeground = false

        /** 无权限时，附加悬浮窗View的前台Activity引用 */
        @Volatile
        var hostActivity: Activity? = null

        fun start(context: android.content.Context) {
            val intent = Intent(context, ReadAloudFloatService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, ReadAloudFloatService::class.java)
            context.stopService(intent)
        }

        /**
         * 根据朗读状态和前台状态决定是否显示/隐藏悬浮窗
         *
         * 注意：本方法只在朗读服务正在运行（BaseReadAloudService.isRun）且用户开启了悬浮窗时才
         * 去启动/通知服务。否则在 Android 8+ 上，应用处于后台时调用 startService 会抛出
         * "Not allowed to start service: app is in background" 导致崩溃（例如 ReadBookActivity
         * 在后台闲置很久后 onResume 时触发）。
         * 另外改用 startForegroundServiceCompat，以彻底符合后台启动前台服务的限制。
         */
        fun updateVisibility(context: android.content.Context) {
            if (!BaseReadAloudService.isRun) return
            if (!context.getPrefBoolean(PreferKey.readAloudFloatWindow)) return
            val intent = Intent(context, ReadAloudFloatService::class.java)
            intent.action = ACTION_UPDATE_VISIBILITY
            context.startForegroundServiceCompat(intent)
        }

        /**
         * 无权限模式下：通知Activity生命周期变化
         * Activity在onResume时调用，注册自己为宿主
         */
        fun onActivityResumed(activity: Activity) {
            if (activity is ReadBookActivity) return
            hostActivity = activity
            // 通知Service重新评估悬浮窗显示
            updateVisibility(activity)
        }

        /**
         * 无权限模式下：Activity在onPause时调用
         */
        fun onActivityPaused(activity: Activity) {
            if (hostActivity === activity) {
                hostActivity = null
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var ivGlowRing: ImageView? = null
    private var ivCover: CircleImageView? = null
    private var rotationAnimator: android.animation.ObjectAnimator? = null
    private var isShowing = false
    private var hasOverlayPermission = false
    /** 是否使用Activity内嵌模式（无悬浮窗权限时） */
    private var isActivityMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        hasOverlayPermission = checkOverlayPermission()
        observeEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!getPrefBoolean(PreferKey.readAloudFloatWindow)) {
            removeFloatingWindow()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BaseReadAloudService.isRun) {
            removeFloatingWindow()
            stopSelf()
            return START_NOT_STICKY
        }

        // 更新悬浮窗权限状态
        hasOverlayPermission = checkOverlayPermission()

        when (intent?.action) {
            ACTION_HIDE -> {
                removeFloatingWindow()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                if (!isShowing) {
                    createFloatingWindow()
                    updateState()
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_VISIBILITY -> {
                updateFloatWindowVisibility()
                return START_NOT_STICKY
            }
        }

        // 默认：根据当前状态决定
        updateFloatWindowVisibility()
        return START_NOT_STICKY
    }

    override fun startForegroundNotification() {
        try {
            val notification = NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
                .setSmallIcon(R.drawable.ic_volume_up)
                .setContentTitle(getString(R.string.read_aloud_float_window))
                .setOngoing(true)
                .setSilent(true)
                .build()
            startForeground(NotificationId.ReadAloudFloatService, notification)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingWindow()
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 根据朗读状态和前台状态决定悬浮窗显示/隐藏
     * 规则：
     * - 朗读服务未运行 → 不显示
     * - ReadBookActivity在前台 → 不显示
     * - 后台朗读中（播放或暂停） → 显示悬浮窗
     *
     * 有系统悬浮窗权限 → 使用 WindowManager 悬浮窗（可跨APP显示）
     * 无系统悬浮窗权限 → 使用 Activity 内嵌模式（仅在APP内的其他Activity上显示）
     */
    private fun updateFloatWindowVisibility() {
        if (!BaseReadAloudService.isRun) {
            removeFloatingWindow()
            return
        }
        if (isReadBookActivityForeground) {
            removeFloatingWindow()
            return
        }
        // 后台朗读中 → 显示悬浮窗
        if (!isShowing) {
            createFloatingWindow()
        }
        updateState()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        if (isShowing) return

        if (hasOverlayPermission) {
            createOverlayWindow()
        } else {
            createActivityModeWindow()
        }
    }

    /**
     * 有悬浮窗权限：使用 WindowManager 添加系统悬浮窗
     */
    private fun createOverlayWindow() {
        isActivityMode = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = android.view.LayoutInflater.from(this)
            .inflate(R.layout.floating_read_aloud, null)

        ivGlowRing = floatingView?.findViewById(R.id.ivGlowRing)
        ivCover = floatingView?.findViewById(R.id.ivCover)

        loadCover()

        val size = getFloatViewSizePx()
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (validX, validY) = getValidatedPosition()

        params = WindowManager.LayoutParams(
            size,
            size,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = validX
            y = validY
        }

        floatingView?.setOnTouchListener(OverlayTouchListener())
        try {
            windowManager?.addView(floatingView, params)
            isShowing = true
        } catch (e: Exception) {
            // 添加窗口失败，降级为Activity内嵌模式
            hasOverlayPermission = false
            floatingView = null
            ivGlowRing = null
            ivCover = null
            params = null
            createActivityModeWindow()
        }
    }

    /**
     * 无悬浮窗权限：将悬浮窗View附加到当前前台Activity的DecorView上
     * 仅在APP内可见，不需要任何权限
     */
    private fun createActivityModeWindow() {
        val activity = hostActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            // 没有可用的宿主Activity，无法显示
            return
        }

        isActivityMode = true
        val density = resources.displayMetrics.density
        val sizePx = (66 * density).toInt()
        val coverSizePx = (48 * density).toInt()

        val (validX, validY) = getValidatedPosition()

        // 创建彩光环
        ivGlowRing = ImageView(activity).apply {
            setImageResource(R.drawable.read_aloud_glow_ring)
            visibility = View.VISIBLE
        }

        // 创建封面
        ivCover = CircleImageView(activity).apply {
            setImageResource(R.drawable.image_float_cover_default)
        }

        // 用FrameLayout包裹，方便定位和触摸
        val floatContainer = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.START or Gravity.TOP
                leftMargin = validX
                topMargin = validY
            }
        }
        floatContainer.addView(ivGlowRing, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val coverOffset = (sizePx - coverSizePx) / 2
        floatContainer.addView(ivCover, FrameLayout.LayoutParams(
            coverSizePx, coverSizePx
        ).apply {
            leftMargin = coverOffset
            topMargin = coverOffset
            gravity = Gravity.START or Gravity.TOP
        })

        loadCover()

        floatContainer.setOnTouchListener(ActivityTouchListener(floatContainer))
        floatingView = floatContainer

        // 附加到Activity的DecorView
        try {
            val decorView = activity.window.decorView as? ViewGroup ?: return
            decorView.addView(floatContainer)
            isShowing = true
        } catch (e: Exception) {
            floatingView = null
            ivGlowRing = null
            ivCover = null
        }
    }

    private fun removeFloatingWindow() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        mainHandler.removeCallbacksAndMessages(null)
        if (isActivityMode) {
            // Activity内嵌模式：从父View中移除
            try {
                floatingView?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                }
            } catch (_: Exception) {
            }
        } else {
            // WindowManager模式
            try {
                floatingView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            }
        }
        floatingView = null
        ivGlowRing = null
        ivCover = null
        params = null
        isShowing = false
        isActivityMode = false
    }

    private fun loadCover() {
        ReadBook.book?.let { book ->
            val coverTarget = ivCover ?: return@let
            val coverUrl = book.getDisplayCover()
            if (coverUrl.isNullOrBlank()) {
                // 无封面：直接设置默认底图，CircleImageView 会自动裁剪成圆形
                coverTarget.setImageDrawable(BookCover.defaultDrawable)
            } else {
                // 有封面：由 BookCover 加载，CircleImageView 负责圆形裁剪
                // 加载失败时会自动显示 error(defaultDrawable)，也会被圆形裁剪
                BookCover.load(this, coverUrl)
                    .placeholder(BookCover.defaultDrawable)
                    .error(BookCover.defaultDrawable)
                    .into(coverTarget)
            }
        }
    }

    private fun updateState() {
        if (BaseReadAloudService.isPlay()) {
            startGlowAnimation()
        } else {
            stopGlowAnimation()
        }
    }

    private fun startGlowAnimation() {
        ivGlowRing?.let { ring ->
            ring.visibility = View.VISIBLE
            if (rotationAnimator == null) {
                rotationAnimator = android.animation.ObjectAnimator.ofFloat(
                    ring, "rotation", 0f, 360f
                ).apply {
                    duration = 2500
                    repeatCount = android.animation.ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
            }
            if (!rotationAnimator!!.isRunning) {
                rotationAnimator!!.start()
            }
        }
    }

    private fun stopGlowAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        ivGlowRing?.visibility = View.VISIBLE
    }

    private fun toggleReadAloud() {
        if (BaseReadAloudService.isPlay()) {
            ReadAloud.pause(this)
        } else {
            ReadAloud.resume(this)
        }
    }

    private fun openReadBookActivity() {
        startActivity<ReadBookActivity> {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private fun observeEvents() {
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            if (!BaseReadAloudService.isRun) {
                removeFloatingWindow()
                stopSelf()
                return@observeEvent
            }
            updateState()
            updateFloatWindowVisibility()
        }
    }

    private fun updateViewPosition() {
        try {
            params?.let { windowManager?.updateViewLayout(floatingView, it) }
        } catch (_: Exception) {
        }
    }

    /** 悬浮按钮实际像素尺寸 */
    private fun getFloatViewSizePx(): Int {
        return (66 * resources.displayMetrics.density).toInt()
    }

    /**
     * 校验并修正悬浮按钮位置，确保在屏幕范围内。
     * 用于屏幕旋转、换机、首次启动等场景。
     */
    private fun getValidatedPosition(): Pair<Int, Int> {
        val size = getFloatViewSizePx()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        var savedX = getPrefInt(PreferKey.readAloudFloatX, 30)
        var savedY = getPrefInt(PreferKey.readAloudFloatY, screenWidth / 10)

        // X 边界校验：允许部分贴边，但不允许完全超出或大半超出
        if (savedX < -size / 2) savedX = 0
        if (savedX > screenWidth - size / 2) savedX = screenWidth - size

        // Y 边界校验
        if (savedY < 0) savedY = 0
        if (savedY > screenHeight - size) savedY = screenHeight - size

        return savedX to savedY
    }

    /**
     * WindowManager悬浮窗触摸监听（有权限时使用）
     */
    inner class OverlayTouchListener : View.OnTouchListener {
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialX = 0
        private var initialY = 0
        private var isDragging = false
        private var isLongClickHandled = false
        private val longClickRunnable = Runnable {
            isLongClickHandled = true
            performLongClick()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    isLongClickHandled = false
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    mainHandler.postDelayed(longClickRunnable, LONG_CLICK_TIMEOUT)
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (abs(deltaX) > 20 || abs(deltaY) > 20) {
                        isDragging = true
                        mainHandler.removeCallbacks(longClickRunnable)
                        params?.x = (initialX + deltaX).toInt()
                        params?.y = (initialY + deltaY).toInt()
                        updateViewPosition()
                        // 保存位置
                        params?.x?.let { putPrefInt(PreferKey.readAloudFloatX, it) }
                        params?.y?.let { putPrefInt(PreferKey.readAloudFloatY, it) }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longClickRunnable)
                    if (!isDragging && !isLongClickHandled) {
                        performClick()
                    }
                    if (isDragging) {
                        startEdgeAnimation()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longClickRunnable)
                }
            }
            return false
        }

        private fun performClick() {
            val swap = getPrefBoolean(PreferKey.readAloudFloatClickSwap)
            if (swap) {
                openReadBookActivity()
            } else {
                toggleReadAloud()
            }
        }

        private fun performLongClick() {
            val swap = getPrefBoolean(PreferKey.readAloudFloatClickSwap)
            if (swap) {
                toggleReadAloud()
            } else {
                openReadBookActivity()
            }
        }
    }

    /**
     * Activity内嵌模式触摸监听（无权限时使用）
     * 通过修改LayoutParams的margin来移动
     */
    inner class ActivityTouchListener(private val container: FrameLayout) : View.OnTouchListener {
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialMarginLeft = 0
        private var initialMarginTop = 0
        private var isDragging = false
        private var isLongClickHandled = false
        private val longClickRunnable = Runnable {
            isLongClickHandled = true
            performLongClick()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    isLongClickHandled = false
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    val lp = container.layoutParams as? FrameLayout.LayoutParams
                    initialMarginLeft = lp?.leftMargin ?: 0
                    initialMarginTop = lp?.topMargin ?: 0
                    mainHandler.postDelayed(longClickRunnable, LONG_CLICK_TIMEOUT)
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (abs(deltaX) > 20 || abs(deltaY) > 20) {
                        isDragging = true
                        mainHandler.removeCallbacks(longClickRunnable)
                        val newLeft = (initialMarginLeft + deltaX).toInt()
                        val newTop = (initialMarginTop + deltaY).toInt()
                        updateActivityPosition(newLeft, newTop)
                        // 保存位置
                        putPrefInt(PreferKey.readAloudFloatX, newLeft)
                        putPrefInt(PreferKey.readAloudFloatY, newTop)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longClickRunnable)
                    if (!isDragging && !isLongClickHandled) {
                        performClick()
                    }
                    if (isDragging) {
                        startActivityEdgeAnimation()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longClickRunnable)
                }
            }
            return true
        }

        private fun performClick() {
            val swap = getPrefBoolean(PreferKey.readAloudFloatClickSwap)
            if (swap) {
                openReadBookActivity()
            } else {
                toggleReadAloud()
            }
        }

        private fun performLongClick() {
            val swap = getPrefBoolean(PreferKey.readAloudFloatClickSwap)
            if (swap) {
                toggleReadAloud()
            } else {
                openReadBookActivity()
            }
        }
    }

    private fun updateActivityPosition(left: Int, top: Int) {
        try {
            (floatingView as? FrameLayout)?.let { container ->
                (container.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = left
                    lp.topMargin = top
                    container.requestLayout()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun getEdgeOffsetPx(): Int {
        val offsetStr = try {
            defaultSharedPreferences.getString(PreferKey.readAloudFloatEdgeOffset, "0")
        } catch (e: Exception) {
            null
        }
        val offsetDp = offsetStr?.toIntOrNull() ?: 0
        return (offsetDp * resources.displayMetrics.density).toInt()
    }

    private fun startActivityEdgeAnimation() {
        val container = floatingView as? FrameLayout ?: return
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val sizePx = getFloatViewSizePx()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val centerX = lp.leftMargin + sizePx / 2
        val centerY = lp.topMargin + sizePx / 2
        val offset = getEdgeOffsetPx()

        val distLeft = centerX
        val distRight = screenWidth - centerX
        val distTop = centerY

        val startX = lp.leftMargin
        val startY = lp.topMargin
        val targetX: Int
        val targetY: Int

        when {
            distTop < distLeft && distTop < distRight -> {
                // 贴上边
                targetX = startX.coerceIn(offset, screenWidth - sizePx - offset)
                targetY = offset
            }
            distLeft < distRight -> {
                // 贴左边
                targetX = offset
                targetY = startY.coerceIn(offset, screenHeight - sizePx - offset)
            }
            else -> {
                // 贴右边
                targetX = screenWidth - sizePx - offset
                targetY = startY.coerceIn(offset, screenHeight - sizePx - offset)
            }
        }

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 250
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            val newX = (startX + (targetX - startX) * progress).toInt()
            val newY = (startY + (targetY - startY) * progress).toInt()
            updateActivityPosition(newX, newY)
        }
        animator.doOnEnd {
            putPrefInt(PreferKey.readAloudFloatX, targetX)
            putPrefInt(PreferKey.readAloudFloatY, targetY)
        }
        animator.start()
    }

    private fun startEdgeAnimation() {
        val p = params ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val viewSizePx = getFloatViewSizePx()
        val centerX = p.x + viewSizePx / 2
        val centerY = p.y + viewSizePx / 2
        val offset = getEdgeOffsetPx()

        val distLeft = centerX
        val distRight = screenWidth - centerX
        val distTop = centerY

        val startX = p.x
        val startY = p.y
        val targetX: Int
        val targetY: Int

        when {
            distTop < distLeft && distTop < distRight -> {
                // 贴上边
                targetX = startX.coerceIn(offset, screenWidth - viewSizePx - offset)
                targetY = offset
            }
            distLeft < distRight -> {
                // 贴左边
                targetX = offset
                targetY = startY.coerceIn(offset, screenHeight - viewSizePx - offset)
            }
            else -> {
                // 贴右边
                targetX = screenWidth - viewSizePx - offset
                targetY = startY.coerceIn(offset, screenHeight - viewSizePx - offset)
            }
        }

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 250
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            p.x = (startX + (targetX - startX) * progress).toInt()
            p.y = (startY + (targetY - startY) * progress).toInt()
            updateViewPosition()
        }
        animator.doOnEnd {
            putPrefInt(PreferKey.readAloudFloatX, targetX)
            putPrefInt(PreferKey.readAloudFloatY, targetY)
        }
        animator.start()
    }
}

private const val ACTION_HIDE = "hide"
private const val ACTION_SHOW = "show"
private const val ACTION_UPDATE_VISIBILITY = "update_visibility"
