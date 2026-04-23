package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils // <--- Не забудь переконатися, що імпорт є
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatsActivity : BaseActivity()  {

    // UI View Variables
    private lateinit var tvKm: TextView
    private lateinit var tvHours: TextView
    private lateinit var tvOrders: TextView
    private lateinit var tvTariff: TextView
    private lateinit var tvDay: TextView

    // DATA
    private var allOrders: List<TaxiOrderDto> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Спочатку завантажуємо Layout
        setContentView(R.layout.activity_stats)

        // 2. ВИПРАВЛЕННЯ: Використовуємо ViewUtils замість ручного коду!
        // Він містить правильну логіку для "челки" (DisplayCutout)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        initViews()
        loadHistoryData()
    }

    private fun initViews() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        tvKm = findViewById(R.id.tv_stat_km)
        tvHours = findViewById(R.id.tv_stat_hours)
        tvOrders = findViewById(R.id.tv_stat_orders)
        tvTariff = findViewById(R.id.tv_stat_tariff)
        tvDay = findViewById(R.id.tv_stat_day)
    }

    private fun loadHistoryData() {
        val sessionManager = SessionManager(this)
        val token = sessionManager.fetchAuthToken()

        if (token == null) {
            Toast.makeText(this, "Авторизуйтесь для перегляду", Toast.LENGTH_SHORT).show()
            return
        }

        ApiClient.instance.getOrderHistory("Bearer $token").enqueue(object : Callback<List<TaxiOrderDto>> {
            override fun onResponse(call: Call<List<TaxiOrderDto>>, response: Response<List<TaxiOrderDto>>) {
                if (response.isSuccessful && response.body() != null) {
                    allOrders = response.body()!!.filter { it.status == "COMPLETED" }
                    calculateAndShowStats(allOrders)
                } else {
                    Toast.makeText(this@StatsActivity, "Немає даних", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<List<TaxiOrderDto>>, t: Throwable) {
                Toast.makeText(this@StatsActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun calculateAndShowStats(orders: List<TaxiOrderDto>) {
        if (orders.isEmpty()) {
            tvKm.text = "0 км"
            tvHours.text = "0 хв"
            tvOrders.text = "0"
            tvTariff.text = "-"
            tvDay.text = "-"
            return
        }

        val totalMeters = orders.sumOf { it.distanceMeters ?: 0 }
        val totalKm = totalMeters / 1000.0
        tvKm.text = String.format(Locale.US, "%.1f км", totalKm)

        val totalSeconds = orders.sumOf { it.durationSeconds ?: 0 }
        val totalMinutes = totalSeconds / 60
        tvHours.text = "$totalMinutes хв"

        tvOrders.text = orders.size.toString()

        val tariffCounts = orders.groupingBy { it.tariffName ?: "Standard" }.eachCount()
        val favoriteTariff = tariffCounts.maxByOrNull { it.value }?.key ?: "-"
        tvTariff.text = favoriteTariff

        val dayCounts = orders.groupingBy { getDayOfWeek(it.createdAt) }.eachCount()
        val favoriteDay = dayCounts.maxByOrNull { it.value }?.key ?: "-"
        tvDay.text = favoriteDay
    }

    private fun parseDate(dateString: String?): Date? {
        if (dateString == null) return null
        return try {
            dateFormat.parse(dateString)
        } catch (e: Exception) {
            null
        }
    }

    private fun getDayOfWeek(dateString: String?): String {
        val date = parseDate(dateString) ?: return "-"
        val cal = Calendar.getInstance()
        cal.time = date
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Понеділок"
            Calendar.TUESDAY -> "Вівторок"
            Calendar.WEDNESDAY -> "Середа"
            Calendar.THURSDAY -> "Четвер"
            Calendar.FRIDAY -> "П'ятниця"
            Calendar.SATURDAY -> "Субота"
            Calendar.SUNDAY -> "Неділя"
            else -> "-"
        }
    }
}