package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.card.MaterialCardView
import com.taxiapp.client.R
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.CarTariffDto
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

    private var currentDistanceMeters: Int = 0
    private var currentExtraCost: Double = 0.0

    private val customPrices = mutableMapOf<Long, Double>()
    private var currentDiscountPercent: Double = 0.0
    private var maxDiscountAmount: Double = 0.0

    // Флаг для того, чтобы показать Toast об ошибке просчета только 1 раз за перерасчет
    private var errorToastShown = false

    inner class TariffViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.tariff_card)
        val name: TextView = view.findViewById(R.id.tv_tariff_name)
        val price: TextView = view.findViewById(R.id.tv_tariff_price)
        val image: ImageView = view.findViewById(R.id.iv_tariff_icon)
        val desc: TextView = view.findViewById(R.id.tv_tariff_desc)

        val iconContainer: View = view.findViewById(R.id.fl_tariff_icon_container)
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
            betaBadge.visibility = if (tariff.isBeta) View.VISIBLE else View.GONE

            // --- ЗАВАНТАЖЕННЯ КАРТИНКИ (без зайвого миготіння скелетона при кліку) ---
            if (!tariff.imageUrl.isNullOrEmpty()) {
                val rawUrl = tariff.imageUrl
                val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    val cleanPath = rawUrl.replace("\\", "/").replace(Regex("/{2,}"), "/").trimStart('/')
                    val baseUrlRoot = ApiClient.BASE_URL.substringBefore("api/v1/")
                    "${baseUrlRoot}${cleanPath}"
                }

                if (image.tag != fullUrl || image.drawable == null) {
                    iconContainer.setBackgroundResource(R.drawable.bg_car_placeholder)
                    image.tag = fullUrl

                    Glide.with(itemView.context)
                        .load(fullUrl)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                iconContainer.setBackgroundResource(R.drawable.bg_car_placeholder)
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                iconContainer.background = null
                                return false
                            }
                        })
                        .into(image)
                } else {
                    iconContainer.background = null
                }
            } else {
                image.tag = null
                image.setImageDrawable(null)
                iconContainer.setBackgroundResource(R.drawable.bg_car_placeholder)
            }

            // --- Знижки та Стара ціна ---
            if (item.oldPriceValue != null && item.oldPriceValue > item.priceValue && item.priceValue > 0) {
                oldPrice.visibility = View.VISIBLE
                oldPrice.text = "${item.oldPriceValue.roundToInt()} ₴"
                oldPrice.paintFlags = oldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

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

            // --- ОБРАБОТКА ОШИБКИ ИЛИ ВЫВОД АКТУАЛЬНОЙ ЦЕНЫ ---
            cardView.stateListAnimator = null
            itemView.clearAnimation()
            cardView.clearAnimation()

            if (item.priceValue < 0) {
                price.text = "Помилка"
                price.setTextColor(Color.RED)

                itemView.alpha = 0.5f
                cardView.alpha = 0.5f
                cardView.strokeWidth = 0
                cardView.cardElevation = 0f
                cardView.isEnabled = false
                itemView.isEnabled = false
                itemView.isClickable = false

                if (!errorToastShown) {
                    errorToastShown = true
                    Toast.makeText(
                        itemView.context,
                        "Помилка розрахунку вартості. Спробуйте пізніше.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                price.text = "${item.priceValue.roundToInt()} ₴"
                price.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))

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
                        val strokePx = (2.5f * itemView.context.resources.displayMetrics.density).roundToInt()
                        cardView.cardElevation = 0f
                        cardView.strokeWidth = strokePx
                        cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.taxi_yellow)
                    } else {
                        cardView.strokeWidth = 0
                        cardView.cardElevation = 0f
                    }
                }
            }

            cardView.setOnClickListener {
                if (tariff.isUnavailable || item.priceValue < 0) return@setOnClickListener

                val clickedPos = bindingAdapterPosition
                if (clickedPos == RecyclerView.NO_POSITION) return@setOnClickListener

                val prev = selectedPosition
                selectedPosition = clickedPos

                if (prev != RecyclerView.NO_POSITION && prev < items.size) {
                    notifyItemChanged(prev)
                }
                if (selectedPosition != RecyclerView.NO_POSITION && selectedPosition < items.size) {
                    notifyItemChanged(selectedPosition)
                }

                AnalyticsManager.trackCustomEvent("tariff_select", tariff.name)
                onTariffSelected(items[selectedPosition])
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

        errorToastShown = false
        recalculateItems()

        if (tariffs.isNotEmpty()) {
            onImagesLoaded()
        }
    }

    fun updatePrices(newPrices: Map<Long, Double>) {
        // Совместимость с HomeActivity: расчет цен выполняется централизованно на сервере
    }

    fun setDiscount(percent: Double, limit: Double) {
        this.currentDiscountPercent = percent
        this.maxDiscountAmount = limit
        recalculateItems()
    }

    fun updateExtraCost(cost: Double) {
        this.currentExtraCost = cost
        errorToastShown = false
        recalculateItems()
    }

    fun setCustomPrice(tariffId: Long, addedValue: Double) {
        customPrices[tariffId] = addedValue
        errorToastShown = false
        recalculateItems()

        val currentSelected = getSelectedTariff()
        if (currentSelected != null && currentSelected.tariff.id == tariffId) {
            onTariffSelected(currentSelected)
        }
    }

    fun clearCustomPrices() {
        customPrices.clear()
        errorToastShown = false
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
        val oldItems = items
        val newItems = rawTariffs.map { tariff ->
            val basePriceForCalc = if (tariff.calculatedPrice != null && tariff.calculatedPrice!! > 0) {
                tariff.calculatedPrice!!
            } else if (currentDistanceMeters > 0) {
                -1.0
            } else {
                tariff.basePrice
            }

            val extraAndCustom = currentExtraCost + (customPrices[tariff.id] ?: 0.0)

            val finalPrice: Double
            val finalOldPrice: Double?

            if (basePriceForCalc <= 0) {
                finalPrice = -1.0
                finalOldPrice = null
            } else if (tariff.oldPrice != null && tariff.oldPrice!! > basePriceForCalc) {
                // Скидка уже посчитана сервером в calculatedPrice и oldPrice
                finalPrice = basePriceForCalc + extraAndCustom
                finalOldPrice = tariff.oldPrice!! + extraAndCustom
            } else if (currentDiscountPercent > 0.0) {
                // Локальный расчет скидки (если установлена через setDiscount)
                val rawDiscount = basePriceForCalc * (currentDiscountPercent / 100.0)
                val cappedDiscount = if (maxDiscountAmount > 0.0) min(rawDiscount, maxDiscountAmount) else rawDiscount
                val discountedBase = (basePriceForCalc - cappedDiscount).coerceAtLeast(1.0)
                finalPrice = discountedBase + extraAndCustom
                finalOldPrice = if (cappedDiscount >= 1.0) basePriceForCalc + extraAndCustom else null
            } else {
                finalPrice = basePriceForCalc + extraAndCustom
                finalOldPrice = null
            }

            val priceString = if (finalPrice > 0) String.format("%.0f", finalPrice) else "—"

            TariffItem(
                tariff = tariff,
                priceString = priceString,
                priceValue = finalPrice,
                addedValue = customPrices[tariff.id] ?: 0.0,
                oldPriceValue = finalOldPrice
            )
        }

        items = newItems
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].tariff.id == newItems[newItemPosition].tariff.id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        diff.dispatchUpdatesTo(this)
    }
}