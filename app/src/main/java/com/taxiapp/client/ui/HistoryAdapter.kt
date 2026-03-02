package com.taxiapp.client.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.TaxiOrderDto

class HistoryAdapter(
    private var orders: List<TaxiOrderDto>,
    private val onItemClick: ((Long) -> Unit)? = null,   // <-- ДОДАНО: Клік по картці для розгортання
    private val onCancelClick: ((Long) -> Unit)? = null  // <-- Клік по кнопці скасування

) : RecyclerView.Adapter<HistoryAdapter.OrderViewHolder>() {

    fun submitList(newOrders: List<TaxiOrderDto>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_active_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(orders[position], onItemClick, onCancelClick)
    }

    override fun getItemCount(): Int = orders.size

    class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_status_badge)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)
        private val btnCancel: Button = itemView.findViewById(R.id.btn_cancel_order)
        private val layoutDriverInfo: LinearLayout = itemView.findViewById(R.id.layout_driver_info)
        private val tvDriverName: TextView = itemView.findViewById(R.id.tv_driver_name)
        private val tvCarModel: TextView = itemView.findViewById(R.id.tv_car_model)

        private val tvWaypoints: TextView? = itemView.findViewById(R.id.tv_waypoints)
        private val ivWaypointDot: ImageView? = itemView.findViewById(R.id.iv_marker_waypoint)

        fun bind(order: TaxiOrderDto, onItemClick: ((Long) -> Unit)?, onCancelClick: ((Long) -> Unit)?) {
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"

            // --- ЛОГІКА СТАТУСУ ТА ВИДИМОСТІ КНОПКИ ---
            // --- ЛОГІКА СТАТУСУ ТА ВИДИМОСТІ КНОПКИ ---
            val isActive = order.status == "SCHEDULED" || order.status == "REQUESTED" ||
                    order.status == "OFFERING" || order.status == "ACCEPTED" ||
                    order.status == "DRIVER_ARRIVED" || order.status == "IN_PROGRESS"

            if (isActive) {
                // АКТИВНЕ ЗАМОВЛЕННЯ
                btnCancel.visibility = View.VISIBLE
                tvStatusBadge.visibility = View.VISIBLE
                tvDateTime.text = "Зараз" // За замовчуванням "Зараз"

                // Детальний розбір активних статусів
                when (order.status) {
                    "SCHEDULED" -> {
                        val timeStr = order.scheduledAt?.replace("T", " ")?.take(16) ?: ""
                        tvDateTime.text = timeStr
                        tvStatusBadge.text = "Заплановано"
                        tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#FF9800")) // Помаранчевий
                    }
                    "REQUESTED", "OFFERING" -> {
                        tvStatusBadge.text = "Пошук водія"
                        // Можемо зробити синім, щоб відрізнялося від того, коли водій вже знайдений
                        tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#2196F3"))
                    }
                    "ACCEPTED" -> {
                        tvStatusBadge.text = "Водій їде"
                        tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#4CAF50")) // Зелений
                    }
                    "DRIVER_ARRIVED" -> {
                        tvStatusBadge.text = "Водій на місці"
                        tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#4CAF50")) // Зелений

                        // Коли водій на місці, скасування вже зазвичай заборонено/платне,
                        // але залежить від твоєї логіки. Якщо треба сховати кнопку:
                        // btnCancel.visibility = View.GONE
                    }
                    "IN_PROGRESS" -> {
                        tvStatusBadge.text = "В дорозі"
                        tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#4CAF50")) // Зелений
                        btnCancel.visibility = View.GONE // В дорозі точно не можна скасувати
                    }
                    else -> {
                        tvStatusBadge.text = "В роботі"
                        tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#4CAF50"))
                    }
                }
            } else {
                // АРХІВНЕ (Завершено або Скасовано)
                btnCancel.visibility = View.GONE
                tvStatusBadge.visibility = View.VISIBLE

                if (order.status == "CANCELLED") {
                    tvStatusBadge.text = "Скасовано"
                    tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#F44336")) // Червоний
                } else {
                    tvStatusBadge.text = "Завершено"
                    tvStatusBadge.background.setTint(android.graphics.Color.parseColor("#9E9E9E")) // Сірий
                }

                tvDateTime.text = order.createdAt?.replace("T", " ")?.take(16) ?: "Дата"
            }

            // --- ВОДІЙ ---
            if (order.driver != null) {
                layoutDriverInfo.visibility = View.VISIBLE
                tvDriverName.text = "${order.driver.fullName}"
                tvCarModel.text = "${order.driver.carColor} ${order.driver.carModel} • ${order.driver.carPlateNumber}"
            } else {
                layoutDriverInfo.visibility = View.GONE
            }

            // --- ЗУПИНКИ ---
            if (!order.formattedWaypoints.isNullOrEmpty()) {
                tvWaypoints?.text = order.formattedWaypoints
                tvWaypoints?.visibility = View.VISIBLE
                ivWaypointDot?.visibility = View.VISIBLE
            } else {
                tvWaypoints?.visibility = View.GONE
                ivWaypointDot?.visibility = View.GONE
            }

            // --- ОБРОБКА КЛІКІВ ---

            // 1. Клік по всій картці (розгортає замовлення на головний екран)
            itemView.setOnClickListener {
                onItemClick?.invoke(order.id)
            }

            // 2. Клік тільки по кнопці "Скасувати"
            btnCancel.setOnClickListener {
                onCancelClick?.invoke(order.id)
            }
        }
    }
}