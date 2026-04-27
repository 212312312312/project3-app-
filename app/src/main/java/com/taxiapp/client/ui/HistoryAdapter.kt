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
    private val onItemClick: ((Long) -> Unit)? = null,
    private val onCancelClick: ((Long) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ACTIVE = 1
        private const val VIEW_TYPE_ARCHIVE = 2
    }

    fun submitList(newOrders: List<TaxiOrderDto>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    // ВИЗНАЧАЄМО ТИП ЗАМОВЛЕННЯ
    override fun getItemViewType(position: Int): Int {
        val status = orders[position].status
        val isActive = status == "SCHEDULED" || status == "REQUESTED" ||
                status == "OFFERING" || status == "ACCEPTED" ||
                status == "DRIVER_ARRIVED" || status == "IN_PROGRESS"

        return if (isActive) VIEW_TYPE_ACTIVE else VIEW_TYPE_ARCHIVE
    }

    // РОЗДУВАЄМО ПОТРІБНИЙ МАКЕТ
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
    // 1. VIEWHOLDER ДЛЯ АКТИВНИХ ПОЇЗДОК (зі статусом і кнопкою)
    // =========================================================
    class ActiveOrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_status_badge)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)

        // Знаходимо саме КОНТЕЙНЕР кнопки, а не тільки кнопку
        private val layoutCancelContainer: View = itemView.findViewById(R.id.layout_cancel_container)
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

            // За замовчуванням показуємо кнопку
            layoutCancelContainer.visibility = View.VISIBLE
            tvDateTime.text = "Зараз"

            when (order.status) {
                "SCHEDULED" -> {
                    val timeStr = order.scheduledAt?.replace("T", " ")?.take(16) ?: ""
                    tvDateTime.text = timeStr
                    tvStatusBadge.text = "Заплановано"
                    tvStatusBadge.background.setTint(Color.parseColor("#FF9800"))
                }
                "REQUESTED", "OFFERING" -> {
                    tvStatusBadge.text = "Пошук водія"
                    tvStatusBadge.background.setTint(Color.parseColor("#2196F3"))
                }
                "ACCEPTED" -> {
                    tvStatusBadge.text = "Водій їде"
                    tvStatusBadge.background.setTint(Color.parseColor("#4CAF50"))
                }
                "DRIVER_ARRIVED" -> {
                    tvStatusBadge.text = "Водій на місці"
                    tvStatusBadge.background.setTint(Color.parseColor("#4CAF50"))
                }
                "IN_PROGRESS" -> {
                    tvStatusBadge.text = "В дорозі"
                    tvStatusBadge.background.setTint(Color.parseColor("#4CAF50"))
                    // ТУТ ХОВАЄМО ВЕСЬ КОНТЕЙНЕР, щоб не було пустої червоної пігулки!
                    layoutCancelContainer.visibility = View.GONE
                }
                else -> {
                    tvStatusBadge.text = "В роботі"
                    tvStatusBadge.background.setTint(Color.parseColor("#4CAF50"))
                }
            }

            if (order.driver != null) {
                layoutDriverInfo.visibility = View.VISIBLE
                tvDriverName.text = order.driver.fullName
                tvCarModel.text = "${order.driver.carColor} ${order.driver.carModel} • ${order.driver.carPlateNumber}"
            } else {
                layoutDriverInfo.visibility = View.GONE
            }

            if (!order.formattedWaypoints.isNullOrEmpty()) {
                tvWaypoints?.text = order.formattedWaypoints
                tvWaypoints?.visibility = View.VISIBLE
                ivWaypointDot?.visibility = View.VISIBLE
            } else {
                tvWaypoints?.visibility = View.GONE
                ivWaypointDot?.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick?.invoke(order.id) }
            btnCancel.setOnClickListener { onCancelClick?.invoke(order.id) }
        }
    }

    // =========================================================
    // 2. VIEWHOLDER ДЛЯ АРХІВУ (без кнопок і зайвого коду)
    // =========================================================
    class ArchiveOrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date_time)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)
        private val tvWaypoints: TextView? = itemView.findViewById(R.id.tv_waypoints)
        private val ivWaypointDot: ImageView? = itemView.findViewById(R.id.iv_marker_waypoint)

        fun bind(order: TaxiOrderDto, onItemClick: ((Long) -> Unit)?) {
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"
            tvDateTime.text = order.createdAt?.replace("T", " ")?.take(16) ?: "Дата"

            if (!order.formattedWaypoints.isNullOrEmpty()) {
                tvWaypoints?.text = order.formattedWaypoints
                tvWaypoints?.visibility = View.VISIBLE
                ivWaypointDot?.visibility = View.VISIBLE
            } else {
                tvWaypoints?.visibility = View.GONE
                ivWaypointDot?.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick?.invoke(order.id) }
        }
    }
}