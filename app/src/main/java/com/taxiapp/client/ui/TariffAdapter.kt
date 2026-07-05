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
    val addedValue: Double = 0.0,
    val oldPriceValue: Double? = null
)

class TariffAdapter(
    private val onTariffSelected: (TariffItem) -> Unit,
    private val onImagesLoaded: () -> Unit
) : RecyclerView.Adapter<TariffAdapter.TariffViewHolder>() {

    private var items: List<TariffItem> = emptyList()
    private var selectedPosition: Int = -1
    private var rawTariffs: List<CarTariffDto> = emptyList()

    private var imagesToLoadCount = 0
    private var currentDistanceMeters: Int = 0
    private var currentExtraCost: Double = 0.0

    private val customPrices = mutableMapOf<Long, Double>()
    private var currentDiscountPercent: Double = 0.0
    private var maxDiscountAmount: Double = 0.0

    // НАЛАШТУВАННЯ СЕРВЕРА
    private val SERVER_IP = "192.168.0.107"
    private val SERVER_PORT = "8080"

    inner class TariffViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.tariff_card)
        val name: TextView = view.findViewById(R.id.tv_tariff_name)
        val price: TextView = view.findViewById(R.id.tv_tariff_price)
        val image: ImageView = view.findViewById(R.id.iv_tariff_icon)
        val desc: TextView = view.findViewById(R.id.tv_tariff_desc)
        val oldPrice: TextView = view.findViewById(R.id.tv_old_price)
        val discountBadge: TextView = view.findViewById(R.id.tv_discount_badge)
        val betaBadge: TextView = view.findViewById(R.id.tv_beta_badge)

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
                    // Исправлено выражение регулярки на {2,}
                    val cleanPath = rawUrl.replace("\\", "/").replace(Regex("/{2,}"), "/").trimStart('/')
                    val baseUrlRoot = com.taxiapp.client.network.ApiClient.BASE_URL.substringBefore("api/v1/")
                    "${baseUrlRoot}${cleanPath}"
                }

                // ⚡️ ЖЕЛЕЗОБЕТОННЫЙ ВАРИАНТ ЧЕРЕЗ CUSTOM_TARGET (Исключает конфликты версий Glide)
                Glide.with(itemView.context)
                    .load(fullUrl)
                    .placeholder(R.drawable.ic_taxi_model_standard)
                    .error(R.drawable.ic_taxi_model_standard)
                    .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        private fun decrementAndCheck() {
                            imagesToLoadCount--
                            if (imagesToLoadCount <= 0) {
                                onImagesLoaded() // Все картинки загружены или упали в ошибку — скрываем шиммер
                            }
                        }

                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                        ) {
                            image.setImageDrawable(resource)
                            decrementAndCheck()
                        }

                        override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                            super.onLoadFailed(errorDrawable)
                            if (errorDrawable != null) {
                                image.setImageDrawable(errorDrawable)
                            } else {
                                image.setImageResource(R.drawable.ic_taxi_model_standard)
                            }
                            decrementAndCheck()
                        }

                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                            image.setImageDrawable(placeholder)
                        }
                    })
            } else {
                image.setImageResource(R.drawable.ic_taxi_model_standard)
            }
// -----------------------------
// -----------------------------
            // -----------------------------

            // Знижки
            // --- УМНАЯ ДИНАМИЧЕСКАЯ ЛОГИКА СКИДОК С СЕРВЕРА ---
            if (item.oldPriceValue != null && item.oldPriceValue > item.priceValue) {
                oldPrice.visibility = View.VISIBLE
                oldPrice.text = "${item.oldPriceValue.roundToInt()} ₴"
                oldPrice.paintFlags = oldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

                // Автоматически вычисляем процент скидки на основе двух цен
                val calculatedPct = ((item.oldPriceValue - item.priceValue) / item.oldPriceValue * 100).roundToInt()

                discountBadge.visibility = View.VISIBLE
                discountBadge.text = "-$calculatedPct%"

                if (isSelected) {
                    discountBadge.setBackgroundResource(R.drawable.bg_discount_filled)
                    discountBadge.setTextColor(Color.WHITE)
                } else {
                    discountBadge.setBackgroundResource(R.drawable.bg_discount_outline)
                    discountBadge.setTextColor(Color.parseColor("#00E5FF"))
                }
            } else {
                oldPrice.visibility = View.GONE
                discountBadge.visibility = View.GONE
            }

            // Вывод текущей актуальной цены
            price.text = "${item.priceValue.roundToInt()} ₴"

            // --- ЛОГИКА НЕДОСТУПНОГО ТАРИФА (UNAVAILABLE) ---
            cardView.stateListAnimator = null
            itemView.clearAnimation()
            cardView.clearAnimation()

            if (tariff.isUnavailable) {
                itemView.alpha = 0.4f
                cardView.alpha = 0.4f
                cardView.strokeWidth = 0
                cardView.cardElevation = 0f
                cardView.isEnabled = false
                itemView.isEnabled = false
                itemView.isClickable = false
            } else {
                itemView.alpha = 1.0f
                cardView.alpha = 1.0f
                cardView.isEnabled = true
                itemView.isEnabled = true
                itemView.isClickable = true

                if (isSelected) {
                    cardView.cardElevation = 0f
                    cardView.strokeWidth = 6
                    cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.taxi_yellow)
                } else {
                    cardView.strokeWidth = 0
                    cardView.cardElevation = 0f
                }
            }
            // -----------------------------

            itemView.setOnClickListener {
                if (tariff.isUnavailable) return@setOnClickListener

                val prev = selectedPosition
                selectedPosition = bindingAdapterPosition

                if (prev != androidx.recyclerview.widget.RecyclerView.NO_POSITION && prev < items.size) {
                    notifyItemChanged(prev)
                }
                if (selectedPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    notifyItemChanged(selectedPosition)
                }

                com.taxiapp.client.analytics.AnalyticsManager.trackCustomEvent("tariff_select", tariff.name)
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
        // Фиксируем, сколько картинок нам нужно дождаться
        this.imagesToLoadCount = tariffs.count { !it.imageUrl.isNullOrEmpty() }

        // 👈 ФИКС: Гасим шиммер только если картинок 0 И при этом список тарифов НЕ пустой!
        // При первичной очистке панели (emptyList()) скелетон продолжит красиво гореть.
        if (this.imagesToLoadCount == 0 && tariffs.isNotEmpty()) {
            onImagesLoaded()
        }

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

        val currentSelected = getSelectedTariff()
        if (currentSelected != null && currentSelected.tariff.id == tariffId) {
            onTariffSelected(currentSelected)
        }
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

            val finalOldPrice = if (tariff.oldPrice != null && tariff.oldPrice!! > 0) {
                tariff.oldPrice!! + currentExtraCost + userAdded
            } else null

            TariffItem(tariff, priceString, finalPrice, userAdded, finalOldPrice)
        }
        notifyDataSetChanged()
    }
}