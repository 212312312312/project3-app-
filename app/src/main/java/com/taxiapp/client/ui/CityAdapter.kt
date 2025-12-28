package com.taxiapp.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.R

class CityAdapter(
    private var cities: List<String>,
    private val selectedCity: String?, // <-- Принимаем текущий город
    private val onCitySelected: (String) -> Unit
) : RecyclerView.Adapter<CityAdapter.CityViewHolder>() {

    fun updateList(newCities: List<String>) {
        cities = newCities
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CityViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_city, parent, false)
        return CityViewHolder(view)
    }

    override fun onBindViewHolder(holder: CityViewHolder, position: Int) {
        val city = cities[position]
        holder.bind(city)
    }

    override fun getItemCount(): Int = cities.size

    inner class CityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCityName: TextView = itemView.findViewById(R.id.tv_city_name)
        private val ivCheck: ImageView = itemView.findViewById(R.id.iv_check)

        fun bind(city: String) {
            tvCityName.text = city

            // Логика галочки: если город совпадает с выбранным - показываем
            if (city == selectedCity) {
                ivCheck.visibility = View.VISIBLE
            } else {
                ivCheck.visibility = View.GONE
            }

            itemView.setOnClickListener { onCitySelected(city) }
        }
    }
}