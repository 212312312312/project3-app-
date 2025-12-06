package com.taxiapp.client.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.taxiapp.client.R

object BitmapHelper {

    fun createCustomMarkerFromLayout(
        context: Context,
        title: String,
        isPointB: Boolean,
        distanceText: String? = null,
        isBubbleUp: Boolean = true // <-- НОВИЙ ПАРАМЕТР (за замовчуванням зверху)
    ): BitmapDescriptor {

        val view = LayoutInflater.from(context).inflate(R.layout.layout_custom_marker, null) as LinearLayout

        val cardView = view.findViewById<View>(R.id.info_card_container) // Треба додати ID в XML (див. нижче) або знайти CardView
        // Оскільки в layout_custom_marker.xml кореневий елемент LinearLayout,
        // а всередині CardView і FrameLayout, знайдемо їх за типами або індексами,
        // але надійніше додати ID в XML.
        // ДАВАЙТЕ ПРИПУСТИМО, ЩО ВИ ДОДАЛИ ID. ЯКЩО НІ - КОД НИЖЧЕ ЗРОБИТЬ ЦЕ ПРОГРАМНО.

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
            // Якщо бульбашка має бути ЗНИЗУ:
            // 1. Видаляємо CardView (вона перша)
            val card = view.getChildAt(0)
            view.removeView(card)
            // 2. Додаємо її в кінець (після маркера)
            view.addView(card)

            // 3. Міняємо Margin (був bottom, стане top)
            val params = card.layoutParams as LinearLayout.LayoutParams
            val oldBottom = params.bottomMargin
            params.bottomMargin = 0
            params.topMargin = oldBottom // Переносимо відступ наверх
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

    fun vectorToBitmap(context: Context, @androidx.annotation.DrawableRes id: Int): com.google.android.gms.maps.model.BitmapDescriptor {
        val vectorDrawable = androidx.core.content.ContextCompat.getDrawable(context, id)!!
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(
            vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}