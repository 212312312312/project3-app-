package com.taxiapp.client.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.ClientPromoProgressDto

class PromoAdapter(
    // Додали колбек для відкриття деталей при кліку
    private val onPromoClick: (ClientPromoProgressDto) -> Unit
) : RecyclerView.Adapter<PromoAdapter.PromoViewHolder>() {

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

        // --- 1. ОПИС ТА ТАРИФ ---
        var descText = item.description
        if (item.requiredTariffName != null) {
            descText = "$descText на ${item.requiredTariffName}"
        }
        holder.desc.text = descText

        // --- 2. ЗНИЖКА (Тільки відсоток) ---
        // Ми домовилися не показувати ліміт суми тут, щоб не захаращувати картку.
        // Деталі будуть у BottomSheet.
        holder.discount.text = "-${item.discountPercent.toInt()}%"
        holder.discount.textSize = 18f

        // --- 3. ПРОГРЕС ТА СТАТУС ---
        if (item.isRewardAvailable) {
            // Завдання виконано
            holder.progressText.text = "Готово! Знижка доступна"
            holder.progressText.setTextColor(Color.parseColor("#4CAF50")) // Зелений

            holder.progressBar.max = 100
            holder.progressBar.progress = 100

            // Активуємо клік для перегляду деталей
            holder.itemView.setOnClickListener {
                onPromoClick(item)
            }
            // Можна додати візуальний ефект клікабельності (Ripple) у XML,
            // або просто покладатися на стандартну поведінку.

        } else {
            // В процесі виконання
            holder.progressText.setTextColor(Color.GRAY)
            holder.itemView.setOnClickListener(null) // Клік не працює, поки не виконано

            // Визначаємо тип завдання: КМ чи Поїздки
            if (item.requiredDistanceMeters > 0) {
                // --> РЕЖИМ ДИСТАНЦІЇ (КМ)
                val currentKm = item.currentDistanceMeters / 1000.0
                val requiredKm = item.requiredDistanceMeters / 1000.0

                holder.progressText.text = "${String.format("%.1f", currentKm)} / ${requiredKm.toInt()} км"

                holder.progressBar.max = item.requiredDistanceMeters.toInt()
                holder.progressBar.progress = item.currentDistanceMeters.toInt()
            } else {
                // --> РЕЖИМ ПОЇЗДОК (ШТ)
                val max = if (item.requiredRides > 0) item.requiredRides else 1

                holder.progressText.text = "${item.currentRides}/$max"
                holder.progressBar.max = max
                holder.progressBar.progress = item.currentRides
            }
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<ClientPromoProgressDto>) {
        items = newItems
        notifyDataSetChanged()
    }
}