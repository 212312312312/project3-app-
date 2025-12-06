package com.taxiapp.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.ClientPromoProgressDto

class PromoAdapter : RecyclerView.Adapter<PromoAdapter.PromoViewHolder>() {

    private var items: List<ClientPromoProgressDto> = emptyList()

    class PromoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_promo_title)
        val desc: TextView = view.findViewById(R.id.tv_promo_desc)
        val progressText: TextView = view.findViewById(R.id.tv_promo_progress_text)
        val progressBar: ProgressBar = view.findViewById(R.id.pb_promo_progress)
        val discount: TextView = view.findViewById(R.id.tv_discount_percent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_promo, parent, false)
        return PromoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PromoViewHolder, position: Int) {
        val item = items[position]

        holder.title.text = item.title
        holder.desc.text = item.description
        holder.discount.text = "-${item.discountPercent.toInt()}%"

        if (item.isRewardAvailable) {
            holder.progressText.text = "Готово!"
            holder.progressBar.max = 100
            holder.progressBar.progress = 100
        } else {
            holder.progressText.text = "${item.currentRides}/${item.requiredRides}"
            holder.progressBar.max = item.requiredRides
            holder.progressBar.progress = item.currentRides
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<ClientPromoProgressDto>) {
        items = newItems
        notifyDataSetChanged()
    }
}