package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryAdapter(
    private var orders: List<TaxiOrderDto>,
    private val onItemClick: ((String) -> Unit)? = null,
    private val onCancelClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ACTIVE = 1
        private const val VIEW_TYPE_ARCHIVE = 2
    }

    fun submitList(newOrders: List<TaxiOrderDto>) {
        orders = newOrders.toMutableList()
        notifyDataSetChanged()
    }

    fun addOrders(moreOrders: List<TaxiOrderDto>) {
        val startPos = orders.size
        val mutable = orders.toMutableList()
        mutable.addAll(moreOrders)
        orders = mutable
        notifyItemRangeInserted(startPos, moreOrders.size)
    }

    override fun getItemViewType(position: Int): Int {
        val status = orders[position].status
        val isActive = status == "SCHEDULED" || status == "REQUESTED" ||
                status == "OFFERING" || status == "ACCEPTED" ||
                status == "DRIVER_ARRIVED" || status == "IN_PROGRESS" ||
                status == "ARRIVED_AT_WAYPOINT"

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

        private val containerWaypoints: LinearLayout? = itemView.findViewById(R.id.container_waypoints)

        fun bind(order: TaxiOrderDto, onItemClick: ((String) -> Unit)?, onCancelClick: ((String) -> Unit)?) {
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
                        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        val date = inputFormat.parse(rawScheduled)

                        val dateFormat = SimpleDateFormat("dd.MM.yy", Locale.US)
                        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

                        tvDateTime.text = date?.let { dateFormat.format(it) } ?: rawScheduled
                        tvTime.text = date?.let { timeFormat.format(it) } ?: ""
                        tvTime.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        tvDateTime.text = order.scheduledAt?.replace("T", " ")?.take(16) ?: ""
                        tvTime.visibility = View.GONE
                    }
                    tvStatusBadge.text = "Заплановано"
                }
                "REQUESTED", "OFFERING" -> tvStatusBadge.text = "Пошук водія"
                "ACCEPTED" -> tvStatusBadge.text = "Водій їде"
                "DRIVER_ARRIVED" -> tvStatusBadge.text = "Водій на місці"
                "IN_PROGRESS", "ARRIVED_AT_WAYPOINT" -> {
                    tvStatusBadge.text = if (order.status == "ARRIVED_AT_WAYPOINT") "На проміжній точці" else "В дорозі"
                    layoutCancelContainer.visibility = View.GONE
                }
                else -> tvStatusBadge.text = "В роботі"
            }

            if (order.driver != null) {
                layoutDriverInfo.visibility = View.VISIBLE
                val rawName = order.driver.fullName?.trim() ?: ""
                val firstName = rawName.substringBefore(" ")
                tvDriverName.text = firstName
                tvCarModel.text = "${order.driver.carColor ?: ""} ${order.driver.carModel ?: ""} • ${order.driver.carPlateNumber ?: ""}".trim()
            } else {
                layoutDriverInfo.visibility = View.GONE
            }

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
        private val tvDate: TextView = itemView.findViewById(R.id.tv_date)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)
        private val ivPaymentType: ImageView = itemView.findViewById(R.id.iv_history_payment_type)
        private val vStatusCorner: View = itemView.findViewById(R.id.v_status_corner)
        private val containerWaypoints: LinearLayout? = itemView.findViewById(R.id.container_waypoints)

        fun bind(order: TaxiOrderDto, onItemClick: ((String) -> Unit)?) {
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"

            // Способ оплаты
            if (order.paymentMethod?.uppercase() == "CARD") {
                ivPaymentType.setImageResource(R.drawable.ic_card)
            } else {
                ivPaymentType.setImageResource(R.drawable.ic_cash)
            }

            // Цвет треугольника статуса
            val context = itemView.context
            // Колір куточка статусу (яскраві суцільні кольори)
            when (order.status) {
                "COMPLETED" -> {
                    vStatusCorner.background?.setTint(
                        ContextCompat.getColor(itemView.context, R.color.status_order_completed)
                    )
                }
                "CANCELLED" -> {
                    vStatusCorner.background?.setTint(
                        ContextCompat.getColor(itemView.context, R.color.status_order_cancelled)
                    )
                }
                else -> {
                    // Защитная заглушка для компилятора: скрываем уголок, если статус неизвестен
                    vStatusCorner.background?.setTint(Color.TRANSPARENT)
                }
            }

            // Форматирование даты и времени в одну линию
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val date = inputFormat.parse(order.createdAt?.take(19) ?: "")
                if (date != null) {
                    val dateFormat = SimpleDateFormat("d MMM yyyy", Locale("uk"))
                    val timeFormat = SimpleDateFormat("HH:mm", Locale("uk"))

                    val dateStr = dateFormat.format(date) // "24 серп. 2026"

                    val spannable = SpannableString(dateStr)
                    val dayPart = dateStr.substringBefore(" ")
                    if (dayPart.isNotEmpty() && dayPart.all { it.isDigit() }) {
                        spannable.setSpan(
                            RelativeSizeSpan(1.25f),
                            0,
                            dayPart.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }

                    tvDate.text = spannable
                    tvTime.text = timeFormat.format(date)
                } else {
                    tvDate.text = order.createdAt?.take(10) ?: "Дата"
                    tvTime.text = "--:--"
                }
            } catch (e: Exception) {
                tvDate.text = order.createdAt?.take(10) ?: "Дата"
                tvTime.text = "--:--"
            }

            // Промежуточные остановки
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