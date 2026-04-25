package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TariffItem(
    val tariff: CarTariffDto,
    val priceString: String,
    val priceValue: Double,
    val addedValue: Double = 0.0
)

class TariffAdapter(
    private val onTariffSelected: (TariffItem) -> Unit
) : RecyclerView.Adapter<TariffAdapter.TariffViewHolder>() {

    private var items: List<TariffItem> = emptyList()
    private var selectedPosition: Int = -1
    private var rawTariffs: List<CarTariffDto> = emptyList()
    private var currentDistanceMeters: Int = 0
    private var currentExtraCost: Double = 0.0

    private val customPrices = mutableMapOf<Long, Double>()
    private var currentDiscountPercent: Double = 0.0
    private var maxDiscountAmount: Double = 0.0

    // НАЛАШТУВАННЯ СЕРВЕРА
    private val SERVER_IP = "192.168.0.107" // Твій IP
    private val SERVER_PORT = "8080"

    inner class TariffViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.tariff_card)
        val name: TextView = view.findViewById(R.id.tv_tariff_name)
        val price: TextView = view.findViewById(R.id.tv_tariff_price)
        val image: ImageView = view.findViewById(R.id.iv_tariff_icon)
        val desc: TextView = view.findViewById(R.id.tv_tariff_desc)
        val oldPrice: TextView = view.findViewById(R.id.tv_old_price)
        val discountBadge: TextView = view.findViewById(R.id.tv_discount_badge)
        val betaBadge: TextView = view.findViewById(R.id.tv_beta_badge) // <-- ДОБАВЛЕНО

        fun bind(item: TariffItem, isSelected: Boolean) {
            val tariff = item.tariff
            name.text = tariff.name

            // Опис
            if (!tariff.description.isNullOrEmpty()) {
                desc.visibility = View.VISIBLE
                desc.text = tariff.description
            } else {
                desc.visibility = View.GONE
            }

            // --- BETA БЕЙДЖ ---
            if (tariff.isBeta) {
                betaBadge.visibility = View.VISIBLE
            } else {
                betaBadge.visibility = View.GONE
            }
            // ------------------

            // --- ЗАВАНТАЖЕННЯ КАРТИНКИ ---
            if (!tariff.imageUrl.isNullOrEmpty()) {
                val rawUrl = tariff.imageUrl

                val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    var cleanPath = rawUrl.replace("\\", "/")
                        .replace(Regex("/{2,}"), "/")
                        .trimStart('/')

                    if (!cleanPath.startsWith("uploads/")) {
                        cleanPath = "uploads/$cleanPath"
                    }
                    "http://$SERVER_IP:$SERVER_PORT/$cleanPath"
                }

                Glide.with(itemView.context)
                    .load(fullUrl)
                    .placeholder(R.drawable.ic_taxi_model_standard)
                    .error(R.drawable.ic_taxi_model_standard)
                    .into(image)
            } else {
                image.setImageResource(R.drawable.ic_taxi_model_standard)
            }
            // -----------------------------

            // Знижки
            if (currentDiscountPercent > 0.0) {
                oldPrice.visibility = View.VISIBLE
                oldPrice.text = "${item.priceValue.roundToInt()} ₴"
                oldPrice.paintFlags = oldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

                discountBadge.visibility = View.VISIBLE
                discountBadge.text = "-${currentDiscountPercent.toInt()}%"

                if (isSelected) {
                    discountBadge.setBackgroundResource(R.drawable.bg_discount_filled)
                    discountBadge.setTextColor(Color.WHITE)
                } else {
                    discountBadge.setBackgroundResource(R.drawable.bg_discount_outline)
                    discountBadge.setTextColor(Color.parseColor("#00E5FF"))
                }

                val rawDiscount = item.priceValue * (currentDiscountPercent / 100.0)
                val finalDiscount = if (maxDiscountAmount > 0.0) {
                    min(rawDiscount, maxDiscountAmount)
                } else {
                    rawDiscount
                }

                val newPrice = item.priceValue - finalDiscount
                val displayPrice = if (newPrice < 0) 0.0 else newPrice

                price.text = "${displayPrice.roundToInt()} ₴"
            } else {
                oldPrice.visibility = View.GONE
                discountBadge.visibility = View.GONE
                price.text = "${item.priceValue.roundToInt()} ₴"
            }

            // --- ЛОГИКА НЕДОСТУПНОГО ТАРИФА (UNAVAILABLE) ---
            if (tariff.isUnavailable) {
                // Делаем тусклым, убираем выделение и отключаем клики
                cardView.alpha = 0.4f
                cardView.strokeWidth = 0
                cardView.cardElevation = 0f
                itemView.isEnabled = false
                itemView.isClickable = false
            } else {
                // Возвращаем нормальный вид
                cardView.alpha = 1.0f
                itemView.isEnabled = true
                itemView.isClickable = true

                // Стиль виділення
                if (isSelected) {
                    cardView.cardElevation = 0f
                    cardView.strokeWidth = 6
                    cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.taxi_yellow)
                } else {
                    cardView.strokeWidth = 0
                    cardView.cardElevation = 0f
                }
            }

            itemView.setOnClickListener {
                if (tariff.isUnavailable) return@setOnClickListener // Дополнительная защита

                val prev = selectedPosition
                selectedPosition = bindingAdapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedPosition)
                onTariffSelected(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TariffViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.tariff_item, parent, false)
        return TariffViewHolder(view)
    }

    override fun onBindViewHolder(holder: TariffViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(tariffs: List<CarTariffDto>, distanceMeters: Int) {
        this.rawTariffs = tariffs
        this.currentDistanceMeters = distanceMeters
        recalculateItems()
    }

    fun updatePrices(newPrices: Map<Long, Double>) { }

    fun setDiscount(percent: Double, limit: Double) {
        this.currentDiscountPercent = percent
        this.maxDiscountAmount = limit
        notifyDataSetChanged()
    }

    fun updateExtraCost(cost: Double) {
        this.currentExtraCost = cost
        recalculateItems()
    }

    fun setCustomPrice(tariffId: Long, addedValue: Double) {
        customPrices[tariffId] = addedValue
        recalculateItems()
    }

    fun clearCustomPrices() {
        customPrices.clear()
        recalculateItems()
    }

    fun setSelectedTariffId(id: Long) {
        val index = items.indexOfFirst { it.tariff.id == id }
        if (index != -1) {
            val prev = selectedPosition
            selectedPosition = index
            if (prev != -1 && prev < items.size) notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onTariffSelected(items[index])
        }
    }

    fun getSelectedTariff(): TariffItem? {
        if (selectedPosition >= 0 && selectedPosition < items.size) {
            return items[selectedPosition]
        }
        return null
    }

    private fun recalculateItems() {
        items = rawTariffs.map { tariff ->
            val basePriceForCalc = if (tariff.calculatedPrice != null && tariff.calculatedPrice!! > 0) {
                tariff.calculatedPrice!!
            } else {
                val totalKm = currentDistanceMeters / 1000.0
                val INCLUDED_KM = 3.0
                val billableKm = if (totalKm > INCLUDED_KM) totalKm - INCLUDED_KM else 0.0
                val manualCalc = tariff.basePrice + (billableKm * tariff.pricePerKm)
                max(ceil(manualCalc), tariff.basePrice)
            }

            val withServices = basePriceForCalc + currentExtraCost
            val userAdded = customPrices[tariff.id] ?: 0.0
            val finalPrice = withServices + userAdded
            val priceString = String.format("%.0f", finalPrice)

            TariffItem(tariff, priceString, finalPrice, userAdded)
        }
        notifyDataSetChanged()
    }
}