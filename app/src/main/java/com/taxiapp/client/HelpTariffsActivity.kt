package com.taxiapp.client

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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

class HelpTariffsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var sessionManager: SessionManager

    // Текстовые поля для деталей (снизу экрана)
    private lateinit var tvPriceBase: TextView
    private lateinit var tvPriceKm: TextView
    private lateinit var tvPriceMin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_tariffs)

        // 1. ИСПРАВЛЕНИЕ: Заменили 'e' на '_', чтобы убрать предупреждение
        try { ViewUtils.makeImmersive(this) } catch (_: Exception) {}

        sessionManager = SessionManager(this)

        // Инициализация View
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.rv_tariffs)
        // Важно: горизонтальная прокрутка для карточек
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Находим поля, куда будем писать цены
        tvPriceBase = findViewById(R.id.tv_price_base)
        tvPriceKm = findViewById(R.id.tv_price_km)
        tvPriceMin = findViewById(R.id.tv_price_min)

        loadTariffs()
    }

    private fun loadTariffs() {
        val token = sessionManager.fetchAuthToken() ?: return

        // Вызываем метод API (путь: /api/v1/public/tariffs)
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

        // Создаем наш внутренний адаптер
        val adapter = HelpAdapter(tariffs) { selectedTariff ->
            // При клике обновляем тексты внизу
            updateDetails(selectedTariff)
        }

        recyclerView.adapter = adapter

        // Сразу показываем детали первого тарифа (чтобы не было пусто)
        updateDetails(tariffs[0])
    }

    private fun updateDetails(tariff: CarTariffDto) {
        tvPriceBase.text = "${tariff.basePrice.toInt()} ₴"
        tvPriceKm.text = "${tariff.pricePerKm.toInt()} ₴"
        tvPriceMin.text = "${tariff.pricePerWaitingMinute.toInt()} ₴"
    }

    // --- ВНУТРЕННИЙ АДАПТЕР ---
    private inner class HelpAdapter(
        private val list: List<CarTariffDto>,
        private val onSelected: (CarTariffDto) -> Unit
    ) : RecyclerView.Adapter<HelpAdapter.HelpViewHolder>() {

        private var selectedPosition = 0
        // URL твоего сервера для картинок
        private val IMAGES_BASE_URL = "http://192.168.0.104:8080/images/"

        inner class HelpViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val rootLayout: LinearLayout = itemView.findViewById(R.id.item_root)
            val icon: ImageView = itemView.findViewById(R.id.iv_tariff_icon)
            val name: TextView = itemView.findViewById(R.id.tv_tariff_name)

            fun bind(tariff: CarTariffDto, isSelected: Boolean) {
                name.text = tariff.name

                // 2. ИСПРАВЛЕНИЕ: Используем 'iconUrl' вместо 'imageUrl'
                // В вашем DTO на клиенте это поле называется iconUrl
                val currentIconUrl = tariff.iconUrl

                if (!currentIconUrl.isNullOrEmpty()) {
                    val fullUrl = if (currentIconUrl.startsWith("http")) {
                        currentIconUrl
                    } else {
                        IMAGES_BASE_URL + currentIconUrl
                    }

                    Glide.with(itemView.context)
                        .load(fullUrl)
                        .placeholder(R.drawable.ic_home_custom)
                        .error(R.drawable.ic_home_custom)
                        .into(icon)
                } else {
                    icon.setImageResource(R.drawable.ic_home_custom)
                }

                // Выделение (яркий если выбран, прозрачный если нет)
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
            // Используем ТВОЙ item_tariff_card.xml
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tariff_card, parent, false)
            return HelpViewHolder(view)
        }

        override fun onBindViewHolder(holder: HelpViewHolder, position: Int) {
            holder.bind(list[position], position == selectedPosition)
        }

        override fun getItemCount() = list.size
    }
}