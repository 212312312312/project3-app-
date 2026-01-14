package com.taxiapp.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R
import com.taxiapp.client.data.model.TaxiService

class ServicesAdapter(
    private val services: List<TaxiService>,
    private val onItemClick: (TaxiService) -> Unit
) : RecyclerView.Adapter<ServicesAdapter.ServiceViewHolder>() {

    class ServiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_service_name)
        val price: TextView = view.findViewById(R.id.tv_service_price)
        val checkIcon: ImageView = view.findViewById(R.id.iv_selected_check)
        // container используем, если он есть, иначе itemView
        val container: View? = view.findViewById(R.id.item_container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_service, parent, false)
        return ServiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServiceViewHolder, position: Int) {
        val service = services[position]

        holder.name.text = service.name
        holder.price.text = "${service.price.toInt()} ₴"

        // Видимость галочки
        holder.checkIcon.visibility = if (service.isSelected) View.VISIBLE else View.GONE

        // !!! ИСПРАВЛЕНИЕ: Один надежный клик !!!
        // Если есть специальный контейнер - вешаем на него, иначе на весь элемент
        val clickTarget = holder.container ?: holder.itemView

        clickTarget.setOnClickListener {
            onItemClick(service)
        }
    }

    override fun getItemCount(): Int = services.size
}