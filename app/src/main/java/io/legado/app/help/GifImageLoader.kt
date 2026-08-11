package io.legado.app.help

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import com.bumptech.glide.Glide
import java.io.File
import java.util.WeakHashMap

/**
 * 用安卓系统解码器加载 GIF 动图（替代 Glide 的 Java GIF 解码器）。
 *
 * 部分 GIF（特殊 disposal 方式、超大逻辑屏、长动画等）在 Glide 的 StandardGifDecoder
 * 下只能解出静态首帧、动画解码失败；安卓系统解码器（ImageDecoder / Movie）更宽容，
 * 往往能正常播放。
 *
 * - API 28+：ImageDecoder.decodeDrawable → AnimatedImageDrawable（系统原生动画）
 * - API 23~27：Movie.decodeFile → 自绘 MovieDrawable（Handler 驱动逐帧）
 *
 * 失败时回退到 Glide 的静态首帧，避免完全空白，并打印异常便于排查。
 *
 * @param file      本地 GIF 文件
 * @param scaleType 期望的缩放方式（小图用 CENTER_CROP，大图用 FIT_CENTER）
 */
fun ImageView.loadGif(file: File, scaleType: ImageView.ScaleType) {
    this.scaleType = scaleType

    // 停掉上一个动画，避免叠加 / 后台空转
    (drawable as? Animatable)?.stop()

    val d = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeByImageDecoder(file)
        } else {
            val movie = Movie.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Movie.decodeFile 返回 null")
            MovieDrawable(movie)
        }
    } catch (e: Exception) {
        Log.e("loadGif", "系统解码 GIF 失败，回退静态首帧: ${file.name} (size=${file.length()}B)", e)
        post { Glide.with(this).asBitmap().load(file).into(this) }
        return
    }

    setImageDrawable(d)
    if (d is Animatable) d.start()

    // 仅注册一次：视图脱离窗口时停动画、回到窗口时恢复，避免后台空转
    gifListeners[this]?.let { removeOnAttachStateChangeListener(it) }
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            val dr = (v as? ImageView)?.drawable
            if (dr is Animatable && !dr.isRunning) dr.start()
        }

        override fun onViewDetachedFromWindow(v: View) {
            val dr = (v as? ImageView)?.drawable
            if (dr is Animatable && dr.isRunning) dr.stop()
        }
    }
    gifListeners[this] = listener
    addOnAttachStateChangeListener(listener)
}

/** 用系统 ImageDecoder 解码 GIF（API 28+），返回可原生动画的 Drawable。 */
@RequiresApi(Build.VERSION_CODES.P)
private fun decodeByImageDecoder(file: File): Drawable {
    val src = ImageDecoder.createSource(file)
    return ImageDecoder.decodeDrawable(src)
}

/** 用于 API 23~27 的系统 Movie 解码器动画封装。 */
private class MovieDrawable(private val movie: Movie) : Drawable(), Animatable {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var startTime = 0L

    override fun draw(canvas: Canvas) {
        val dur = movie.duration().coerceAtLeast(1)
        // 未播放时停在首帧；播放时按已用时间取模定位帧
        val t = if (running) ((SystemClock.uptimeMillis() - startTime) % dur).toInt() else 0
        movie.setTime(t)
        movie.draw(canvas, 0f, 0f)
    }

    override fun getIntrinsicWidth(): Int = movie.width()
    override fun getIntrinsicHeight(): Int = movie.height()

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (running || movie.duration() <= 0) return
        running = true
        startTime = SystemClock.uptimeMillis()
        invalidateSelf()
        scheduleNext()
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(runFrame)
    }

    override fun isRunning(): Boolean = running

    private val runFrame = Runnable {
        invalidateSelf()
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!running) return
        val dur = movie.duration().coerceAtLeast(1)
        val elapsed = (SystemClock.uptimeMillis() - startTime) % dur
        val delay = (dur - elapsed).coerceAtLeast(0)
        handler.postDelayed(runFrame, delay)
    }
}

/** 记录已注册的 attach 监听，避免重复添加（ImageView 在扩展函数里无法持有状态）。 */
private val gifListeners = WeakHashMap<ImageView, View.OnAttachStateChangeListener>()
