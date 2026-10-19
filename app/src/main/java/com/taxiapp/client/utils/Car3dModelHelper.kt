package com.taxiapp.client.utils

import android.content.Context
import android.util.Log
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.taxiapp.client.R

object Car3dModelHelper {

    fun bindCar3dImage(
        context: Context,
        imageView: ImageView,
        visualTypeRaw: String?,
        colorKeyRaw: String?
    ) {
        val type = (visualTypeRaw ?: "STANDARD").trim().lowercase()
        val color = (colorKeyRaw ?: "WHITE").trim().lowercase()

        val assetsList = try {
            context.assets.list("cars_3d")?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        // 1. Ищем точное совпадение: car_3d_business_blue.webp или business_blue.webp
        val targetFileName = when {
            "car_3d_${type}_${color}.webp" in assetsList -> "car_3d_${type}_${color}.webp"
            "${type}_${color}.webp" in assetsList -> "${type}_${color}.webp"
            // 2. Если класса нет (например, business еще не залит) — берем standard в цвете авто!
            "car_3d_standard_${color}.webp" in assetsList -> "car_3d_standard_${color}.webp"
            "standard_${color}.webp" in assetsList -> "standard_${color}.webp"
            // 3. Если и этого цвета нет — берем белый standard
            "car_3d_standard_white.webp" in assetsList -> "car_3d_standard_white.webp"
            "standard_white.webp" in assetsList -> "standard_white.webp"
            else -> null
        }

        Log.d("CAR_3D_DEBUG", "🚗 Запрос: type='$type', color='$color' -> Выбран файл: $targetFileName")

        if (targetFileName != null) {
            val assetUrl = "file:///android_asset/cars_3d/$targetFileName"
            Glide.with(context)
                .load(assetUrl)
                .placeholder(R.drawable.ic_taxi_model_standard)
                .error(R.drawable.ic_taxi_model_standard)
                .into(imageView)
        } else {
            imageView.setImageResource(R.drawable.ic_taxi_model_standard)
        }
    }
}