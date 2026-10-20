package com.taxiapp.client.utils

import android.content.Context
import android.util.Log
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.taxiapp.client.R

object Car3dModelHelper {

    @Volatile
    private var cachedAssetsList: Set<String>? = null

    private fun getAssetsSet(context: Context): Set<String> {
        cachedAssetsList?.let { return it }
        synchronized(this) {
            cachedAssetsList?.let { return it }
            val loaded = try {
                context.applicationContext.assets.list("cars_3d")?.toSet() ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
            cachedAssetsList = loaded
            return loaded
        }
    }

    fun bindCar3dImage(
        context: Context,
        imageView: ImageView,
        visualTypeRaw: String?,
        colorKeyRaw: String?
    ) {
        val type = (visualTypeRaw ?: "STANDARD").trim().lowercase()
        val color = (colorKeyRaw ?: "WHITE").trim().lowercase()

        val assetsList = getAssetsSet(context)

        val targetFileName = when {
            "car_3d_${type}_${color}.webp" in assetsList -> "car_3d_${type}_${color}.webp"
            "${type}_${color}.webp" in assetsList -> "${type}_${color}.webp"
            "car_3d_standard_${color}.webp" in assetsList -> "car_3d_standard_${color}.webp"
            "standard_${color}.webp" in assetsList -> "standard_${color}.webp"
            "car_3d_standard_white.webp" in assetsList -> "car_3d_standard_white.webp"
            "standard_white.webp" in assetsList -> "standard_white.webp"
            else -> null
        }

        if (com.taxiapp.client.BuildConfig.DEBUG) {
            Log.d("CAR_3D_DEBUG", "🚗 Запрос: type='$type', color='$color' -> Выбран файл: $targetFileName")
        }

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