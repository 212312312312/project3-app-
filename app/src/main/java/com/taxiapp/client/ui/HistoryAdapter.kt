package com.taxiapp.client.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.TaxiOrderDto

class HistoryAdapter(
    private var orders: List<TaxiOrderDto>,
    private val onCancelClick: ((Long) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.OrderViewHolder>() {

    fun submitList(newOrders: List<TaxiOrderDto>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        // Используем ОДИН и тот же красивый макет для всех типов заказов
        // Мы просто будем скрывать кнопку отмены, если она не нужна
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_active_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(orders[position], onCancelClick)
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

        // Доп. поля (если есть в макете)
        private val tvWaypoints: TextView? = itemView.findViewById(R.id.tv_waypoints)
        private val ivWaypointDot: ImageView? = itemView.findViewById(R.id.iv_marker_waypoint)

        fun bind(order: TaxiOrderDto, onCancel: ((Long) -> Unit)?) {
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"

            // --- ЛОГИКА СТАТУСА И ВИДИМОСТИ КНОПКИ ---
            val isActive = order.status == "SCHEDULED" || order.status == "REQUESTED" ||
                    order.status == "OFFERING" || order.status == "ACCEPTED" ||
                    order.status == "DRIVER_ARRIVED" || order.status == "IN_PROGRESS"

            if (isActive) {
                // АКТИВНЫЙ ЗАКАЗ
                btnCancel.visibility = View.VISIBLE

                if (order.status == "SCHEDULED") {
                    val timeStr = order.scheduledAt?.replace("T", " ")?.take(16) ?: ""
                    tvDateTime.text = timeStr
                    tvStatusBadge.text = "Заплановано"
                    tvStatusBadge.visibility = View.VISIBLE
                    tvStatusBadge.background.setTint(Color.parseColor("#FF9800")) // Оранжевый
                } else {
                    tvDateTime.text = "Зараз"
                    tvStatusBadge.text = "В роботі"
                    tvStatusBadge.visibility = View.VISIBLE
                    tvStatusBadge.background.setTint(Color.parseColor("#4CAF50")) // Зеленый
                }
            } else {
                // АРХИВНЫЙ (Завершен или Отменен)
                btnCancel.visibility = View.GONE

                tvStatusBadge.visibility = View.VISIBLE // Или GONE, если не нужен бейдж в архиве

                if (order.status == "CANCELLED") {
                    tvStatusBadge.text = "Скасовано"
                    tvStatusBadge.background.setTint(Color.RED)
                } else {
                    tvStatusBadge.text = "Завершено"
                    tvStatusBadge.background.setTint(Color.GRAY)
                }

                tvDateTime.text = order.createdAt?.replace("T", " ")?.take(16) ?: "Дата"
            }

            // --- ВОДИТЕЛЬ ---
            if (order.driver != null) {
                layoutDriverInfo.visibility = View.VISIBLE
                tvDriverName.text = "${order.driver.fullName}"
                tvCarModel.text = "${order.driver.carColor} ${order.driver.carModel} • ${order.driver.carPlateNumber}"
            } else {
                layoutDriverInfo.visibility = View.GONE
            }

            // --- ОСТАНОВКИ (с проверкой на null, т.к. в макете могут быть скрыты) ---
            if (!order.formattedWaypoints.isNullOrEmpty()) {
                tvWaypoints?.text = order.formattedWaypoints
                tvWaypoints?.visibility = View.VISIBLE
                ivWaypointDot?.visibility = View.VISIBLE
            } else {
                tvWaypoints?.visibility = View.GONE
                ivWaypointDot?.visibility = View.GONE
            }

            // Клик по кнопке отмены
            btnCancel.setOnClickListener {
                onCancel?.invoke(order.id)
            }
        }
    }
}