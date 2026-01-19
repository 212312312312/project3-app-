package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.Paint
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

    // Ручна зміна ціни юзером (Чайові)
    private val customPrices = mutableMapOf<Long, Double>()

    private var currentDiscountPercent: Double = 0.0
    private var maxDiscountAmount: Double = 0.0

    // ВАЖЛИВО: Замініть на реальний IP вашого сервера, якщо тестуєте на реальному пристрої
    private val SERVER_IP = "192.168.0.104"

    inner class TariffViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.tariff_card)
        val name: TextView = view.findViewById(R.id.tv_tariff_name)
        val price: TextView = view.findViewById(R.id.tv_tariff_price)
        val image: ImageView = view.findViewById(R.id.iv_tariff_icon)
        val desc: TextView = view.findViewById(R.id.tv_tariff_desc)
        val oldPrice: TextView = view.findViewById(R.id.tv_old_price)
        val discountBadge: TextView = view.findViewById(R.id.tv_discount_badge)

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

            // Іконка
            if (!tariff.iconUrl.isNullOrEmpty()) {
                val fullUrl = if (tariff.iconUrl.startsWith("http")) {
                    tariff.iconUrl
                } else {
                    val path = if (tariff.iconUrl.startsWith("/")) tariff.iconUrl else "/${tariff.iconUrl}"
                    "http://$SERVER_IP:8080$path"
                }

                Glide.with(itemView.context)
                    .load(fullUrl)
                    .placeholder(R.drawable.ic_car_marker_info)
                    .error(R.drawable.ic_car_marker_info)
                    .into(image)
            } else {
                image.setImageResource(R.drawable.ic_car_marker_info)
            }

            // Логіка відображення ціни зі знижкою
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

            // Стиль обраної картки
            if (isSelected) {
                cardView.cardElevation = 0f
                cardView.strokeWidth = 6
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.taxi_yellow)
            } else {
                cardView.strokeWidth = 0
                cardView.cardElevation = 0f
            }

            itemView.setOnClickListener {
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

    // Цей метод можна видалити або залишити пустим, якщо він викликається з HomeActivity,
    // але ми тепер покладаємось на submitList з calculatedPrice
    fun updatePrices(newPrices: Map<Long, Double>) {
        // Ми ігноруємо старий спосіб передачі цін мапою,
        // тому що тепер ціна приходить всередині об'єкта CarTariffDto (calculatedPrice)
        // recalculateItems() // Не потрібно викликати
    }

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

    private fun recalculateItems() {
        items = rawTariffs.map { tariff ->

            // 1. ВИЗНАЧАЄМО БАЗОВУ ЦІНУ ПОЇЗДКИ
            val basePriceForCalc = if (tariff.calculatedPrice != null && tariff.calculatedPrice!! > 0) {
                // ВАРІАНТ А: Сервер надіслав точну ціну (Smart Pricing)
                tariff.calculatedPrice!!
            } else {
                // ВАРІАНТ Б: Фолбек (рахуємо на телефоні, якщо немає інтернету)
                // ТУТ БУЛА ПОМИЛКА: ми виправляємо формулу, щоб враховувати 3 км!

                val totalKm = currentDistanceMeters / 1000.0
                val INCLUDED_KM = 3.0

                // Рахуємо тільки ті км, які перевищують 3 км
                val billableKm = if (totalKm > INCLUDED_KM) totalKm - INCLUDED_KM else 0.0

                val manualCalc = tariff.basePrice + (billableKm * tariff.pricePerKm)

                // Округляємо і гарантуємо, що не менше бази
                max(ceil(manualCalc), tariff.basePrice)
            }

            // 2. Додаємо вартість послуг (якщо обрані в ServicesActivity)
            val withServices = basePriceForCalc + currentExtraCost

            // 3. Додаємо "чайові" (якщо юзер накрутив)
            val userAdded = customPrices[tariff.id] ?: 0.0

            val finalPrice = withServices + userAdded
            val priceString = String.format("%.0f", finalPrice)

            TariffItem(tariff, priceString, finalPrice, userAdded)
        }
        notifyDataSetChanged()
    }
}