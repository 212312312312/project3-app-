package com.taxiapp.client.controllers

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.maps.android.SphericalUtil
import java.util.WeakHashMap

/**
 * Контроллер плавной интерполяции движения и вращения маркеров автомобилей на карте.
 *
 * Исправляет баг наложения ValueAnimator: ранее при каждом новом тике координат на один и тот же
 * маркер запускался новый 2-секундный ValueAnimator поверх старого, что вызывало дергание
 * машинки и утечку аниматоров при 20+ соседних водителях на карте.
 */
class MarkerAnimationController {

    private val activeAnimators = WeakHashMap<Marker, ValueAnimator>()

    fun animateMarker(
        marker: Marker,
        toPosition: LatLng,
        toRotation: Float,
        durationMs: Long = 2000L
    ) {
        // Отменяем предыдущий незавершенный аниматор для этого же маркера
        activeAnimators.remove(marker)?.cancel()

        val startPosition = marker.position
        val startRotation = marker.rotation

        val distance = SphericalUtil.computeDistanceBetween(startPosition, toPosition)
        val finalRotation = if (distance > 1.5) {
            SphericalUtil.computeHeading(startPosition, toPosition).toFloat()
        } else {
            toRotation
        }

        val valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                try {
                    val v = animation.animatedFraction
                    val lng = v * toPosition.longitude + (1 - v) * startPosition.longitude
                    val lat = v * toPosition.latitude + (1 - v) * startPosition.latitude
                    marker.position = LatLng(lat, lng)

                    var delta = finalRotation - startRotation
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    marker.rotation = startRotation + (v * delta)
                } catch (_: Exception) {
                    cancel()
                }
            }
        }

        activeAnimators[marker] = valueAnimator
        valueAnimator.start()
    }

    fun cancelForMarker(marker: Marker?) {
        if (marker == null) return
        activeAnimators.remove(marker)?.cancel()
    }

    fun cancelAll() {
        val copy = activeAnimators.values.toList()
        activeAnimators.clear()
        copy.forEach { it.cancel() }
    }
}