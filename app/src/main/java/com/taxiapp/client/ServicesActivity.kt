package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class ServicesActivity : BaseActivity()  {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ServicesAdapter
    private lateinit var btnApply: MaterialButton
    private lateinit var sessionManager: SessionManager

    private var servicesList = mutableListOf<TaxiService>()
    private var preSelectedIds = ArrayList<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_services)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}

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

        // Кнопка теперь всегда активна при старте
        updateButtonState()

        loadServices()
    }

    // !!! ИСПРАВЛЕНИЕ ЗДЕСЬ !!!
    // Кнопка теперь ВСЕГДА активна, чтобы можно было снять все галочки и нажать "Готово"
    private fun updateButtonState() {
        btnApply.isEnabled = true // Разрешаем клик всегда

        val isDark = sessionManager.isDarkMode()

        // Убираем обводку, делаем кнопку сплошной и красивой всегда
        btnApply.strokeWidth = 0

        if (isDark) {
            btnApply.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btnApply.setTextColor(Color.BLACK)
        } else {
            btnApply.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
            btnApply.setTextColor(Color.WHITE)
        }
    }

    private fun loadServices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = sessionManager.fetchAuthToken()
                if (token.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ServicesActivity, "Помилка авторизації", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val response = ApiClient.instance.getServices().execute()

                if (response.isSuccessful && response.body() != null) {
                    val services = response.body()!!

                    // Восстанавливаем галочки
                    services.forEach { service ->
                        if (preSelectedIds.contains(service.id)) {
                            service.isSelected = true
                        }
                    }

                    withContext(Dispatchers.Main) {
                        servicesList.clear()
                        servicesList.addAll(services)

                        adapter = ServicesAdapter(servicesList) { clickedService ->
                            // Переключаем выбор
                            clickedService.isSelected = !clickedService.isSelected
                            adapter.notifyItemChanged(servicesList.indexOf(clickedService))
                            // Кнопку обновлять не обязательно (она всегда активна), но если захочешь менять текст - можно
                        }
                        recyclerView.adapter = adapter
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ServicesActivity, "Помилка сервера", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ServicesActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
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