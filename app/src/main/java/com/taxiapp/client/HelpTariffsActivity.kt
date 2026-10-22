package com.taxiapp.client

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class HelpTariffsActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var sessionManager: SessionManager
    private var progressBar: ProgressBar? = null

    private lateinit var tvPriceBase: TextView
    private lateinit var tvPriceKm: TextView
    private lateinit var tvPriceMin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_tariffs)

        try { ViewUtils.makeImmersive(this) } catch (_: Exception) {}

        sessionManager = SessionManager(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.rv_tariffs)
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        tvPriceBase = findViewById(R.id.tv_price_base)
        tvPriceKm = findViewById(R.id.tv_price_km)
        tvPriceMin = findViewById(R.id.tv_price_min)

        loadTariffs()
    }

    private fun loadTariffs() {
        progressBar?.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getActiveTariffs()
                if (isFinishing || isDestroyed) return@launch
                progressBar?.visibility = View.GONE

                if (response.isSuccessful) {
                    val tariffs = response.body() ?: emptyList()
                    setupAdapter(tariffs)
                } else {
                    Toast.makeText(this@HelpTariffsActivity, "Помилка завантаження", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                progressBar?.visibility = View.GONE
                Toast.makeText(this@HelpTariffsActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAdapter(tariffs: List<CarTariffDto>) {
        if (tariffs.isEmpty()) return

        val adapter = HelpAdapter(tariffs) { selectedTariff ->
            updateDetails(selectedTariff)
        }

        recyclerView.adapter = adapter
        updateDetails(tariffs[0])
    }

    private fun updateDetails(tariff: CarTariffDto) {
        tvPriceBase.text = "${tariff.basePrice.toInt()} ₴"
        tvPriceKm.text = "${tariff.pricePerKm.toInt()} ₴"
        tvPriceMin.text = "${tariff.pricePerWaitingMinute.toInt()} ₴"
    }

    private inner class HelpAdapter(
        private val list: List<CarTariffDto>,
        private val onSelected: (CarTariffDto) -> Unit
    ) : RecyclerView.Adapter<HelpAdapter.HelpViewHolder>() {

        private var selectedPosition = 0

        inner class HelpViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.iv_tariff_icon)
            val name: TextView = itemView.findViewById(R.id.tv_tariff_name)
            val rootLayout: View = itemView

            fun bind(tariff: CarTariffDto, isSelected: Boolean) {
                name.text = tariff.name

                val rawUrl = tariff.imageUrl
                if (!rawUrl.isNullOrEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                        rawUrl
                    } else {
                        val cleanPath = rawUrl.replace("\\", "/").replace(Regex("/{2,}"), "/").trimStart('/')
                        val baseUrlRoot = ApiClient.BASE_URL.substringBefore("api/v1/")
                        if (cleanPath.startsWith("uploads/")) {
                            "${baseUrlRoot}${cleanPath}"
                        } else {
                            "${baseUrlRoot}uploads/${cleanPath}"
                        }
                    }

                    Glide.with(itemView.context)
                        .load(fullUrl)
                        .placeholder(R.drawable.ic_taxi_model_standard)
                        .error(R.drawable.ic_taxi_model_standard)
                        .into(icon)
                } else {
                    icon.setImageResource(R.drawable.ic_taxi_model_standard)
                }

                rootLayout.alpha = if (isSelected) 1.0f else 0.5f

                itemView.setOnClickListener {
                    val newPos = bindingAdapterPosition
                    if (newPos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val prev = selectedPosition
                    selectedPosition = newPos
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