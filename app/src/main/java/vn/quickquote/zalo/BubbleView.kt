package vn.quickquote.zalo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** Nút nổi tròn, vẽ icon "trả lời" và vòng xoay khi đang xử lý. */
class BubbleView(context: Context) : View(context) {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPath = Path()
    private val arcRect = RectF()
    private var spinAngle = 0f
    private var spinner: ValueAnimator? = null

    var busy: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v) startSpin() else stopSpin()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.parseColor("#3D9BFF"), Color.parseColor("#0050D8"), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f * 0.86f
        canvas.drawCircle(cx, cy + size * 0.03f, r, shadowPaint)
        canvas.drawCircle(cx, cy, r, fillPaint)
        ringPaint.strokeWidth = size * 0.025f
        canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth, ringPaint)

        if (busy) {
            spinPaint.strokeWidth = size * 0.07f
            val rr = r * 0.55f
            arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(arcRect, spinAngle, 270f, false, spinPaint)
        } else {
            val s = r * 0.95f
            iconPaint.strokeWidth = size * 0.075f
            iconPath.reset()
            // mũi tên
            iconPath.moveTo(cx - 0.10f * s, cy - 0.38f * s)
            iconPath.lineTo(cx - 0.42f * s, cy - 0.08f * s)
            iconPath.lineTo(cx - 0.10f * s, cy + 0.22f * s)
            // thân cong
            iconPath.moveTo(cx - 0.42f * s, cy - 0.08f * s)
            iconPath.cubicTo(
                cx + 0.05f * s, cy - 0.08f * s,
                cx + 0.42f * s, cy + 0.02f * s,
                cx + 0.42f * s, cy + 0.42f * s
            )
            canvas.drawPath(iconPath, iconPaint)
        }
    }

    private fun startSpin() {
        spinner?.cancel()
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpin() {
        spinner?.cancel()
        spinner = null
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }
}
