package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.taxiapp.client.data.model.TaxiService
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.ui.ServicesAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServicesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ServicesAdapter
    private lateinit var btnApply: MaterialButton
    private lateinit var sessionManager: SessionManager // Добавили переменную класса

    private var servicesList = mutableListOf<TaxiService>()
    private var preSelectedIds = ArrayList<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_services)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}

        // Инициализируем SessionManager сразу
        sessionManager = SessionManager(this)

        val list = intent.getSerializableExtra("SELECTED_IDS") as? ArrayList<Long>
        if (list != null) {
            preSelectedIds = list
        }

        recyclerView = findViewById(R.id.rv_services)
        recyclerView.layoutManager = LinearLayoutManager(this)

        btnApply = findViewById(R.id.btn_apply)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        btnApply.setOnClickListener {
            returnResult()
        }

        // Изначально обновляем состояние
        updateButtonState()

        loadServices()
    }

    // Метод проверки состояния кнопки и стилизации
    private fun updateButtonState() {
        val hasSelection = servicesList.any { it.isSelected }

        btnApply.isEnabled = hasSelection

        if (hasSelection) {
            // Кнопка АКТИВНА
            val isDark = sessionManager.isDarkMode()

            // Убираем обводку (чтобы была сплошная заливка)
            btnApply.strokeWidth = 0

            if (isDark) {
                // Темная тема: Фон Белый, Текст Черный
                btnApply.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                btnApply.setTextColor(Color.BLACK)
            } else {
                // Светлая тема: Фон Черный, Текст Белый
                btnApply.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
                btnApply.setTextColor(Color.WHITE)
            }
        } else {
            // Кнопка НЕ АКТИВНА (Серый Outlined стиль)
            btnApply.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btnApply.setTextColor(Color.parseColor("#9E9E9E")) // Серый текст

            // Возвращаем обводку
            btnApply.strokeColor = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
            btnApply.strokeWidth = dpToPx(1)
        }
    }

    // Вспомогательный метод для конвертации dp в px (для strokeWidth)
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun loadServices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = sessionManager.fetchAuthToken()

                if (token.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ServicesActivity, "Ошибка авторизации", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val response = ApiClient.instance.getServices("Bearer $token").execute()

                if (response.isSuccessful && response.body() != null) {
                    val services = response.body()!!

                    // Восстанавливаем выбор
                    services.forEach { service ->
                        if (preSelectedIds.contains(service.id)) {
                            service.isSelected = true
                        }
                    }

                    withContext(Dispatchers.Main) {
                        servicesList.clear()
                        servicesList.addAll(services)

                        adapter = ServicesAdapter(servicesList) { clickedService ->
                            // При клике переключаем галочку
                            clickedService.isSelected = !clickedService.isSelected

                            // Обновляем конкретный элемент (чтобы галочка перерисовалась)
                            adapter.notifyItemChanged(servicesList.indexOf(clickedService))

                            // Проверяем кнопку "Готово"
                            updateButtonState()
                        }
                        recyclerView.adapter = adapter

                        // Проверяем кнопку после загрузки
                        updateButtonState()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ServicesActivity, "Ошибка сервера: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ServicesActivity, "Помилка завантаження: ${e.message}", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            }
        }
    }

    private fun returnResult() {
        val selectedServices = servicesList.filter { it.isSelected }

        val selectedIds = ArrayList(selectedServices.map { it.id })
        val totalExtraCost = selectedServices.sumOf { it.price }

        val resultIntent = Intent()
        resultIntent.putExtra("SELECTED_IDS", selectedIds)
        resultIntent.putExtra("EXTRA_COST", totalExtraCost)

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}