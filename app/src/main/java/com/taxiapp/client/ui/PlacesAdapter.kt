package com.taxiapp.client.ui

import android.graphics.Typeface
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import java.util.Locale

class PlacesAdapter(
    private val onPlaceClick: (PlaceSuggestion) -> Unit
) : RecyclerView.Adapter<PlacesAdapter.PlaceViewHolder>() {

    private var items: List<PlaceSuggestion> = emptyList()

    class PlaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val primaryText: TextView = view.findViewById(R.id.tv_primary)
        val secondaryText: TextView = view.findViewById(R.id.tv_secondary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place_suggestion, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        val item = items[position]

        holder.primaryText.text = item.title

        // Формуємо "1.2 км • Адреса"
        val distanceStr = if (item.distanceMeters != null) {
            if (item.distanceMeters < 1000) {
                "${item.distanceMeters} м"
            } else {
                String.format(Locale.US, "%.1f км", item.distanceMeters / 1000.0)
            }
        } else null

        val finalText = if (distanceStr != null && item.subtitle.isNotEmpty()) {
            "$distanceStr • ${item.subtitle}"
        } else if (distanceStr != null) {
            distanceStr
        } else {
            item.subtitle
        }

        holder.secondaryText.text = finalText

        holder.itemView.setOnClickListener {
            onPlaceClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<PlaceSuggestion>) {
        items = newItems
        notifyDataSetChanged()
    }
}