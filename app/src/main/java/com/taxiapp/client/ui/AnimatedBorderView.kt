package com.taxiapp.client.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.animation.PathInterpolator
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.taxiapp.client.R
import kotlin.math.pow

class AnimatedBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cornerRadius = 14f * density
    private val strokeWidthPx = 1.5f * density

    // Стало (линия будет заметно длиннее):
    private val beamLengthPx = 450f * density

    private val path = Path()
    private val tempSegmentPath = Path()
    private val pathMeasure = PathMeasure()
    private var pathLength = 0f
    private val headPoint = FloatArray(2)

    // Фоновая статичная рамка
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.divider_color)
    }

    // Тело луча: Cap.BUTT полностью убирает ребра и наплывы на стыках
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx + (0.5f * density)
        strokeCap = Paint.Cap.BUTT
    }

    // Закруглённая голова луча
    private val headCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animator: ValueAnimator? = null
    private var isAnimationRunning = false
    private var currentProgress = 0f

    // 60 микро-сегментов для бесшовного аналогового градиента
    private val tailSteps = 60

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        val rect = RectF(inset, inset, w - inset, h - inset)

        path.reset()
        // Движение строго по часовой стрелке
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        pathMeasure.setPath(path, true)
        pathLength = pathMeasure.length
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pathLength <= 0f) return

        // 1. Статичная база контура
        canvas.drawPath(path, basePaint)

        // 2. Бегущий луч без рёбер и рывков
        if (isAnimationRunning) {
            val baseColor = ContextCompat.getColor(context, R.color.text_primary)
            val red = Color.red(baseColor)
            val green = Color.green(baseColor)
            val blue = Color.blue(baseColor)

            val headDist = currentProgress * pathLength
            val stepLength = beamLengthPx / tailSteps

            for (i in 0 until tailSteps) {
                val rawStart = headDist - beamLengthPx + (i * stepLength)
                // Нахлёст в 1px нивелирует любые субпиксельные зазоры экрана
                val rawEnd = rawStart + stepLength + 1f

                // Плавное экспоненциальное растворение шлейфа к нулю
                val fraction = (i + 1).toFloat() / tailSteps.toFloat()
                val alpha = (255 * fraction.toDouble().pow(1.8)).toInt().coerceIn(0, 255)

                beamPaint.color = Color.argb(alpha, red, green, blue)
                drawSegment(canvas, rawStart, rawEnd)
            }

            // Аккуратная круглая шапочка на переднем крае луча
            var normalizedHead = headDist % pathLength
            if (normalizedHead < 0) normalizedHead += pathLength
            pathMeasure.getPosTan(normalizedHead, headPoint, null)
            headCapPaint.color = baseColor
            canvas.drawCircle(headPoint[0], headPoint[1], beamPaint.strokeWidth / 2f, headCapPaint)
        }
    }

    private fun drawSegment(canvas: Canvas, rawStart: Float, rawEnd: Float) {
        var start = rawStart % pathLength
        if (start < 0) start += pathLength
        var end = rawEnd % pathLength
        if (end < 0) end += pathLength

        tempSegmentPath.reset()
        if (start < end) {
            pathMeasure.getSegment(start, end, tempSegmentPath, true)
            canvas.drawPath(tempSegmentPath, beamPaint)
        } else {
            // Бесшовный проход через нулевой угол
            pathMeasure.getSegment(start, pathLength, tempSegmentPath, true)
            canvas.drawPath(tempSegmentPath, beamPaint)
            tempSegmentPath.reset()
            pathMeasure.getSegment(0f, end, tempSegmentPath, true)
            canvas.drawPath(tempSegmentPath, beamPaint)
        }
    }

    fun startBorderAnimation() {
        if (isAnimationRunning) return
        isAnimationRunning = true

        // Сила перепада скорости: от 0.0 (строго равномерно) до 0.85 (очень сильный контраст).
        // Значение 0.62 даёт идеальный баланс: ощутимый рывок и мягкий сброс без остановки.
        val surgeFactor = 0.62f
        val twoPi = (2.0 * Math.PI).toFloat()

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L

            // Математический интерполятор непрерывной скорости: скорость > 0 везде
            interpolator = android.animation.TimeInterpolator { t ->
                t - (surgeFactor / twoPi) * kotlin.math.sin(twoPi * t)
            }

            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { va ->
                currentProgress = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    fun stopBorderAnimation() {
        isAnimationRunning = false
        animator?.cancel()
        animator = null
        currentProgress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopBorderAnimation()
        super.onDetachedFromWindow()
    }
}