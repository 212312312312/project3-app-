package com.taxiapp.client.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.taxiapp.client.R

object BitmapHelper {

    fun createCustomMarkerFromLayout(
        context: Context,
        title: String,
        isPointB: Boolean,
        distanceText: String? = null,
        isBubbleUp: Boolean = true
    ): BitmapDescriptor {

        val view = LayoutInflater.from(context).inflate(R.layout.layout_custom_marker, null) as LinearLayout

        // Пытаемся найти по ID, если они есть
        val tvAddress = view.findViewById<TextView>(R.id.tv_address)
        val layoutBottom = view.findViewById<LinearLayout>(R.id.layout_bottom_info)
        val tvDistance = view.findViewById<TextView>(R.id.tv_distance)
        val ivBase = view.findViewById<ImageView>(R.id.iv_marker_base)

        tvAddress.text = title

        if (isPointB && distanceText != null) {
            layoutBottom.visibility = View.VISIBLE
            tvDistance.text = distanceText
            ivBase.setImageResource(R.drawable.ic_marker_base_white)
        } else {
            layoutBottom.visibility = View.GONE
            ivBase.setImageResource(R.drawable.ic_marker_base_yellow)
        }

        // --- ЛОГІКА ВІДДЗЕРКАЛЕННЯ (ВГОРУ/ВНИЗ) ---
        if (!isBubbleUp) {
            val card = view.getChildAt(0)
            view.removeView(card)
            view.addView(card)

            val params = card.layoutParams as LinearLayout.LayoutParams
            val oldBottom = params.bottomMargin
            params.bottomMargin = 0
            params.topMargin = oldBottom
            card.layoutParams = params
        }
        // -----------------------------------------

        val displayMetrics = context.resources.displayMetrics
        val maxPixels = (180 * displayMetrics.density).toInt()

        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bitmap = Bitmap.createBitmap(
            view.measuredWidth,
            view.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // Твой старый метод (оставляем, если он где-то используется)
    fun vectorToBitmap(context: Context, @androidx.annotation.DrawableRes id: Int): BitmapDescriptor {
        val vectorDrawable = ContextCompat.getDrawable(context, id)!!
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(
            vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // --- ДОБАВЛЕННЫЙ МЕТОД (Исправляет ошибку компиляции) ---
    fun vectorToBitmapDescriptor(context: Context, @androidx.annotation.DrawableRes id: Int): BitmapDescriptor? {
        val vectorDrawable = ContextCompat.getDrawable(context, id) ?: return null
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(
            vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}