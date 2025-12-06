package com.taxiapp.client.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker
import com.taxiapp.client.R

class CustomInfoWindowAdapter(context: Context) : GoogleMap.InfoWindowAdapter {

    private val windowView: View = LayoutInflater.from(context).inflate(R.layout.custom_info_window, null)

    private fun render(marker: Marker, view: View) {
        val titleTv = view.findViewById<TextView>(R.id.info_window_title)
        val snippetTv = view.findViewById<TextView>(R.id.info_window_snippet)
        val extraLayout = view.findViewById<LinearLayout>(R.id.info_window_extra)
        val distanceTv = view.findViewById<TextView>(R.id.info_window_distance)

        titleTv.text = marker.title

        val fullText = marker.snippet ?: ""

        if (fullText.contains("|")) {
            val parts = fullText.split("|")
            snippetTv.text = parts[0]
            extraLayout.visibility = View.VISIBLE
            distanceTv.text = parts[1]
        } else {
            snippetTv.text = fullText
            extraLayout.visibility = View.GONE
        }
    }

    // --- ЗМІНИ ТУТ ---

    // getInfoContents повертає вміст, який Google обгортає в свою рамку.
    // Ми повертаємо null, щоб не використовувати це.
    override fun getInfoContents(marker: Marker): View? {
        return null
    }

    // getInfoWindow повертає ПОВНЕ вікно (включаючи фон, тіні і т.д.).
    // Google НЕ буде додавати свою рамку.
    override fun getInfoWindow(marker: Marker): View? {
        render(marker, windowView)
        return windowView
    }
}