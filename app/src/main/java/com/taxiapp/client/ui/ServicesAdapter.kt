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
        // Теперь это ImageView, а не CheckBox
        val checkIcon: ImageView = view.findViewById(R.id.iv_selected_check)
        val container: View = view.findViewById(R.id.item_container)
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

        // Логика видимости иконки
        if (service.isSelected) {
            holder.checkIcon.visibility = View.VISIBLE
        } else {
            holder.checkIcon.visibility = View.GONE
        }

        // Клик по всему элементу
        holder.container.setOnClickListener {
            onItemClick(service)
        }

        // Клик можно вешать и на itemView, но container надежнее внутри нашего layout
        holder.itemView.setOnClickListener {
            onItemClick(service)
        }
    }

    override fun getItemCount(): Int = services.size
}