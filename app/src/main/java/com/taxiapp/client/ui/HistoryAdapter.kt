package com.taxiapp.client.ui

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
    private val onItemClick: ((String) -> Unit)? = null, // <-- ИЗМЕНИЛИ НА String
    private val onCancelClick: ((String) -> Unit)? = null // <-- ИЗМЕНИЛИ НА String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ACTIVE = 1
        private const val VIEW_TYPE_ARCHIVE = 2
    }

    fun submitList(newOrders: List<TaxiOrderDto>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        val status = orders[position].status
        val isActive = status == "SCHEDULED" || status == "REQUESTED" ||
                status == "OFFERING" || status == "ACCEPTED" ||
                status == "DRIVER_ARRIVED" || status == "IN_PROGRESS"

        return if (isActive) VIEW_TYPE_ACTIVE else VIEW_TYPE_ARCHIVE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ACTIVE) {
            val view = inflater.inflate(R.layout.item_history_active_order, parent, false)
            ActiveOrderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_history_order, parent, false)
            ArchiveOrderViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val order = orders[position]
        if (holder is ActiveOrderViewHolder) {
            holder.bind(order, onItemClick, onCancelClick)
        } else if (holder is ArchiveOrderViewHolder) {
            holder.bind(order, onItemClick)
        }
    }

    override fun getItemCount(): Int = orders.size

    // =========================================================
    // 1. VIEWHOLDER ДЛЯ АКТИВНИХ ПОЇЗДОК
    // =========================================================
    class ActiveOrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_status_badge)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)

        private val layoutCancelContainer: View = itemView.findViewById(R.id.layout_cancel_container)
        private val btnCancel: Button = itemView.findViewById(R.id.btn_cancel_order)

        private val layoutDriverInfo: LinearLayout = itemView.findViewById(R.id.layout_driver_info)
        private val tvDriverName: TextView = itemView.findViewById(R.id.tv_driver_name)
        private val tvCarModel: TextView = itemView.findViewById(R.id.tv_car_model)

        // Новий контейнер для точок
        private val containerWaypoints: LinearLayout? = itemView.findViewById(R.id.container_waypoints)

        fun bind(order: TaxiOrderDto, onItemClick: ((String) -> Unit)?, onCancelClick: ((String) -> Unit)?) { // <-- ИЗМЕНИЛИ НА String
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"

            layoutCancelContainer.visibility = View.VISIBLE
            tvDateTime.text = "Зараз"
            tvTime.visibility = View.GONE

            when (order.status) {
                "SCHEDULED" -> {
                    try {
                        val rawScheduled = order.scheduledAt?.take(19) ?: ""
                        val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        val date = inputFormat.parse(rawScheduled)

                        // Раздельные форматы
                        val dateFormat = java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US)
                        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

                        tvDateTime.text = date?.let { dateFormat.format(it) } ?: rawScheduled
                        tvTime.text = date?.let { timeFormat.format(it) } ?: ""
                        tvTime.visibility = View.VISIBLE // Показываем время под датой
                    } catch (e: Exception) {
                        tvDateTime.text = order.scheduledAt?.replace("T", " ")?.take(16) ?: ""
                        tvTime.visibility = View.GONE
                    }
                    tvStatusBadge.text = "Заплановано"
                }
                "REQUESTED", "OFFERING" -> tvStatusBadge.text = "Пошук водія"
                "ACCEPTED" -> tvStatusBadge.text = "Водій їде"
                "DRIVER_ARRIVED" -> tvStatusBadge.text = "Водій на місці"
                "IN_PROGRESS" -> {
                    tvStatusBadge.text = "В дорозі"
                    layoutCancelContainer.visibility = View.GONE
                }
                else -> tvStatusBadge.text = "В роботі"
            }

            if (order.driver != null) {
                layoutDriverInfo.visibility = View.VISIBLE

                // --- ИСПРАВЛЕНО: Отсекаем всё лишнее и берем только Имя водителя ---
                val rawName = order.driver.fullName?.trim() ?: ""
                // Берем первое слово (предполагая формат "Имя Фамилия")
                val firstName = rawName.substringBefore(" ")
                tvDriverName.text = firstName
                // -------------------------------------------------------------------

                tvCarModel.text = "${order.driver.carColor} ${order.driver.carModel} • ${order.driver.carPlateNumber}"
            } else {
                layoutDriverInfo.visibility = View.GONE
            }

            // Рендер додаткових точок
            containerWaypoints?.removeAllViews()
            if (!order.stops.isNullOrEmpty()) {
                containerWaypoints?.visibility = View.VISIBLE
                val inflater = LayoutInflater.from(itemView.context)
                for (stop in order.stops) {
                    val waypointView = inflater.inflate(R.layout.item_active_order_waypoint, containerWaypoints, false)
                    val tvWaypointAddress = waypointView.findViewById<TextView>(R.id.tv_waypoint_address)
                    tvWaypointAddress.text = stop.address
                    containerWaypoints?.addView(waypointView)
                }
            } else {
                containerWaypoints?.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick?.invoke(order.id) }
            btnCancel.setOnClickListener { onCancelClick?.invoke(order.id) }
        }
    }

    // =========================================================
    // 2. VIEWHOLDER ДЛЯ АРХІВУ
    // =========================================================
    class ArchiveOrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date_time)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)

        // Новий контейнер для точок
        private val containerWaypoints: LinearLayout? = itemView.findViewById(R.id.container_waypoints)

        fun bind(order: TaxiOrderDto, onItemClick: ((String) -> Unit)?) { // <-- ИЗМЕНИЛИ НА String
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"

            try {
                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                val date = inputFormat.parse(order.createdAt)
                val outputFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("uk"))
                tvDateTime.text = outputFormat.format(date)
            } catch (e: Exception) {
                tvDateTime.text = order.createdAt?.replace("T", " ")?.take(16) ?: "Дата"
            }

            // Рендер додаткових точок
            containerWaypoints?.removeAllViews()
            if (!order.stops.isNullOrEmpty()) {
                containerWaypoints?.visibility = View.VISIBLE
                val inflater = LayoutInflater.from(itemView.context)
                for (stop in order.stops) {
                    val waypointView = inflater.inflate(R.layout.item_active_order_waypoint, containerWaypoints, false)
                    val tvWaypointAddress = waypointView.findViewById<TextView>(R.id.tv_waypoint_address)
                    tvWaypointAddress.text = stop.address
                    containerWaypoints?.addView(waypointView)
                }
            } else {
                containerWaypoints?.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick?.invoke(order.id) }
        }
    }
}