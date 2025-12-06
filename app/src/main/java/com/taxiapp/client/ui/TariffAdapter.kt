package com.taxiapp.client.ui

import android.graphics.Paint // <-- Для закреслення
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.CarTariffDto

data class TariffItem(
    val tariff: CarTariffDto,
    val price: String,
    val priceValue: Double
)

class TariffAdapter(
    private val onTariffSelected: (TariffItem) -> Unit
) : RecyclerView.Adapter<TariffAdapter.TariffViewHolder>() {

    private var items: List<TariffItem> = emptyList()
    private var selectedPosition: Int = -1

    // --- ДОДАНО: Знижка ---
    private var currentDiscountPercent: Double = 0.0

    // IP (Ваш код)
    private val SERVER_IP = "192.168.0.104"

    class TariffViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.tariff_card)
        val name: TextView = view.findViewById(R.id.tv_tariff_name)
        val price: TextView = view.findViewById(R.id.tv_tariff_price)
        val image: ImageView = view.findViewById(R.id.iv_tariff_icon)
        val desc: TextView = view.findViewById(R.id.tv_tariff_desc)
        // --- ДОДАНО: Стара ціна ---
        val oldPrice: TextView = view.findViewById(R.id.tv_old_price)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TariffViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.tariff_item, parent, false)
        return TariffViewHolder(view)
    }

    override fun onBindViewHolder(holder: TariffViewHolder, position: Int) {
        val item = items[position]

        holder.name.text = item.tariff.name
        holder.desc.visibility = View.GONE

        // --- ЛОГІКА ЦІНИ ЗІ ЗНИЖКОЮ ---
        if (currentDiscountPercent > 0.0) {
            // Є знижка -> показуємо стару і нову ціну
            holder.oldPrice.visibility = View.VISIBLE
            holder.oldPrice.text = "${item.price} грн"
            holder.oldPrice.paintFlags = holder.oldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

            // Рахуємо нову ціну
            val discountAmount = item.priceValue * (currentDiscountPercent / 100.0)
            val newPrice = item.priceValue - discountAmount
            val formattedNewPrice = String.format("%.0f", newPrice)

            holder.price.text = "$formattedNewPrice грн"
            // Можна зробити зеленим, якщо хочете:
            // holder.price.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.taxi_green))
        } else {
            // Немає знижки -> стандартний вигляд
            holder.oldPrice.visibility = View.GONE
            holder.price.text = "${item.price} грн"
        }
        // ------------------------------

        // --- ВАШ КОД ЗАВАНТАЖЕННЯ КАРТИНКИ (БЕЗ ЗМІН) ---
        var iconUrl = item.tariff.iconUrl

        if (iconUrl != null) {
            if (iconUrl.contains("localhost")) {
                iconUrl = iconUrl.replace("localhost", SERVER_IP)
            }
            if (!iconUrl.startsWith("http")) {
                iconUrl = "http://$SERVER_IP:8080$iconUrl"
            }
        }

        Log.d("TariffAdapter", "Loading Icon: $iconUrl")

        if (!iconUrl.isNullOrEmpty()) {
            Glide.with(holder.itemView.context)
                .load(iconUrl)
                .placeholder(R.drawable.ic_car_marker_info)
                .error(R.drawable.ic_car_marker_info)
                .fitCenter()
                .into(holder.image)
        } else {
            holder.image.setImageResource(R.drawable.ic_car_marker_info)
        }
        // ------------------------------------------------

        // --- ВАШ КОД ВИДІЛЕННЯ (БЕЗ ЗМІН) ---
        if (selectedPosition == position) {
            holder.cardView.strokeWidth = 4
            holder.cardView.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.taxi_yellow)
            holder.cardView.cardElevation = 8f
        } else {
            holder.cardView.strokeWidth = 0
            holder.cardView.cardElevation = 2f
        }

        holder.itemView.setOnClickListener {
            val previousPos = selectedPosition
            selectedPosition = holder.adapterPosition
            notifyItemChanged(previousPos)
            notifyItemChanged(selectedPosition)
            onTariffSelected(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(tariffs: List<CarTariffDto>, distanceMeters: Int) {
        selectedPosition = -1
        items = tariffs.map { tariff ->
            val distKm = distanceMeters / 1000.0
            val priceValue = tariff.basePrice + (distKm * tariff.pricePerKm)
            val finalPrice = String.format("%.0f", priceValue)
            TariffItem(tariff, finalPrice, priceValue)
        }
        notifyDataSetChanged()
    }

    // --- ДОДАНО: Метод для встановлення знижки ---
    fun setDiscount(percent: Double) {
        this.currentDiscountPercent = percent
        notifyDataSetChanged() // Оновлюємо весь список, щоб перерахувати ціни
    }
}