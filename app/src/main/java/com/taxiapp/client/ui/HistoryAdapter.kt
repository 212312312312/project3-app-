package com.taxiapp.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.TaxiOrderDto

class HistoryAdapter(
    private var orders: List<TaxiOrderDto>
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    fun submitList(newOrders: List<TaxiOrderDto>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_order, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(orders[position])
    }

    override fun getItemCount(): Int = orders.size

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date_time)
        private val tvPrice: TextView = itemView.findViewById(R.id.tv_price)
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_from)
        private val tvTo: TextView = itemView.findViewById(R.id.tv_to)
        private val tvWaypoints: TextView = itemView.findViewById(R.id.tv_waypoints)
        private val ivWaypointDot: ImageView = itemView.findViewById(R.id.iv_marker_waypoint)

        // !!! НОВОЕ ПОЛЕ: Иконка оплаты !!!
        // Убедись, что в файле item_history_order.xml ты добавил ImageView с id iv_history_payment_type
        private val ivPaymentType: ImageView = itemView.findViewById(R.id.iv_history_payment_type)

        fun bind(order: TaxiOrderDto) {
            tvFrom.text = order.fromAddress
            tvTo.text = order.toAddress
            tvPrice.text = "${order.price.toInt()} ₴"

            // Обработка даты
            tvDateTime.text = order.createdAt?.replace("T", " ")?.take(16) ?: "Завершено"

            // Логика отображения доп. точек
            if (!order.formattedWaypoints.isNullOrEmpty()) {
                tvWaypoints.text = order.formattedWaypoints
                tvWaypoints.visibility = View.VISIBLE
                ivWaypointDot.visibility = View.VISIBLE
            } else {
                tvWaypoints.visibility = View.GONE
                ivWaypointDot.visibility = View.GONE
            }

            // !!! ЛОГИКА ОПЛАТЫ !!!
            if (order.paymentMethod == "CARD") {
                ivPaymentType.setImageResource(R.drawable.ic_card)
            } else {
                ivPaymentType.setImageResource(R.drawable.ic_cash)
            }
        }
    }
}