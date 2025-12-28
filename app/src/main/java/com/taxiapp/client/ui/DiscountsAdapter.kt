package com.taxiapp.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DiscountAdapter(
    // Ця лямбда викликається при кліку
    private val onDiscountClick: (ClientPromoProgressDto) -> Unit
) : RecyclerView.Adapter<DiscountAdapter.DiscountViewHolder>() {

    private var items: List<ClientPromoProgressDto> = emptyList()

    class DiscountViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val percent: TextView = view.findViewById(R.id.tv_card_percent)
        val title: TextView = view.findViewById(R.id.tv_card_title)
        val date: TextView = view.findViewById(R.id.tv_card_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiscountViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discount_card, parent, false)
        return DiscountViewHolder(view)
    }

    override fun onBindViewHolder(holder: DiscountViewHolder, position: Int) {
        val item = items[position]

        // --- ВІДОБРАЖЕННЯ ДАНИХ ---
        // Використовуємо прямі поля, як ми домовились (щоб не було помилок зборки)

        val percentValue = item.discountPercent.toInt()
        holder.percent.text = "-$percentValue%"
        holder.title.text = item.title

        val dateString = item.rewardExpiresAt
        if (dateString != null) {
            try {
                val date = LocalDateTime.parse(dateString.toString())
                val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                holder.date.text = "до ${date.format(formatter)}"
            } catch (e: Exception) {
                holder.date.text = "до $dateString"
            }
        } else {
            holder.date.text = "Безстроково"
        }

        // --- ВАЖЛИВО: ОБРОБКА КЛІКУ ---
        // Цей код робить картку "живою"
        holder.itemView.setOnClickListener {
            onDiscountClick(item)
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<ClientPromoProgressDto>) {
        items = newItems
        notifyDataSetChanged()
    }
}