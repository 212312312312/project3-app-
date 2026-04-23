package com.taxiapp.client

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class HelpTariffsActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var sessionManager: SessionManager

    // Текстові поля для деталей (знизу екрану)
    private lateinit var tvPriceBase: TextView
    private lateinit var tvPriceKm: TextView
    private lateinit var tvPriceMin: TextView

    // Налаштування сервера
    private val SERVER_IP = "192.168.0.104" // Твій актуальний IP
    private val SERVER_PORT = "8080"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_tariffs)

        try { ViewUtils.makeImmersive(this) } catch (_: Exception) {}

        sessionManager = SessionManager(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.rv_tariffs)
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        tvPriceBase = findViewById(R.id.tv_price_base)
        tvPriceKm = findViewById(R.id.tv_price_km)
        tvPriceMin = findViewById(R.id.tv_price_min)

        loadTariffs()
    }

    private fun loadTariffs() {
        val token = sessionManager.fetchAuthToken() ?: return

        ApiClient.instance.getTariffs("Bearer $token").enqueue(object : Callback<List<CarTariffDto>> {
            override fun onResponse(call: Call<List<CarTariffDto>>, response: Response<List<CarTariffDto>>) {
                if (response.isSuccessful && response.body() != null) {
                    val tariffs = response.body()!!
                    setupAdapter(tariffs)
                } else {
                    Toast.makeText(this@HelpTariffsActivity, "Не вдалося завантажити тарифи", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<CarTariffDto>>, t: Throwable) {
                Toast.makeText(this@HelpTariffsActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setupAdapter(tariffs: List<CarTariffDto>) {
        if (tariffs.isEmpty()) return

        val adapter = HelpAdapter(tariffs) { selectedTariff ->
            updateDetails(selectedTariff)
        }

        recyclerView.adapter = adapter
        // Відразу показуємо деталі першого тарифу
        updateDetails(tariffs[0])
    }

    private fun updateDetails(tariff: CarTariffDto) {
        tvPriceBase.text = "${tariff.basePrice.toInt()} ₴"
        tvPriceKm.text = "${tariff.pricePerKm.toInt()} ₴"
        // Використовуємо правильне поле для хвилин очікування
        tvPriceMin.text = "${tariff.pricePerWaitingMinute.toInt()} ₴"
    }

    // --- ВНУТРІШНІЙ АДАПТЕР ---
    private inner class HelpAdapter(
        private val list: List<CarTariffDto>,
        private val onSelected: (CarTariffDto) -> Unit
    ) : RecyclerView.Adapter<HelpAdapter.HelpViewHolder>() {

        private var selectedPosition = 0

        inner class HelpViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.iv_tariff_icon)
            val name: TextView = itemView.findViewById(R.id.tv_tariff_name)

            // Використовуємо itemView як кореневий елемент для зміни прозорості
            val rootLayout: View = itemView

            fun bind(tariff: CarTariffDto, isSelected: Boolean) {
                name.text = tariff.name

                // --- ЗАВАНТАЖЕННЯ КАРТИНКИ ---
                if (!tariff.imageUrl.isNullOrEmpty()) {
                    val fullUrl = "http://$SERVER_IP:$SERVER_PORT/uploads/${tariff.imageUrl}"

                    Glide.with(itemView.context)
                        .load(fullUrl)
                        .placeholder(R.drawable.ic_taxi_model_standard)
                        .error(R.drawable.ic_taxi_model_standard)
                        .into(icon)
                } else {
                    icon.setImageResource(R.drawable.ic_taxi_model_standard)
                }

                // --- ЛОГІКА ВИДІЛЕННЯ (ALPHA) ---
                if (isSelected) {
                    rootLayout.alpha = 1.0f
                } else {
                    rootLayout.alpha = 0.5f
                }

                itemView.setOnClickListener {
                    val prev = selectedPosition
                    selectedPosition = adapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(selectedPosition)
                    onSelected(tariff)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HelpViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tariff_card, parent, false)
            return HelpViewHolder(view)
        }

        override fun onBindViewHolder(holder: HelpViewHolder, position: Int) {
            holder.bind(list[position], position == selectedPosition)
        }

        override fun getItemCount() = list.size
    }
}