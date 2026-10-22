package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.taxiapp.client.data.model.TaxiService
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.ui.ServicesAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServicesActivity : BaseActivity() {

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

        @Suppress("DEPRECATION")
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

        updateButtonState()
        loadServices()
    }

    private fun updateButtonState() {
        btnApply.isEnabled = true
        val isDark = sessionManager.isDarkMode()
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = sessionManager.fetchAuthToken()
                if (token.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@ServicesActivity, "Помилка авторизації", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val response = ApiClient.instance.getServices()

                if (response.isSuccessful && response.body() != null) {
                    val services = response.body()!!

                    services.forEach { service ->
                        if (preSelectedIds.contains(service.id)) {
                            service.isSelected = true
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        servicesList.clear()
                        servicesList.addAll(services)

                        adapter = ServicesAdapter(servicesList) { clickedService ->
                            clickedService.isSelected = !clickedService.isSelected
                            val idx = servicesList.indexOf(clickedService)
                            if (idx != -1) {
                                adapter.notifyItemChanged(idx)
                            }
                        }
                        recyclerView.adapter = adapter
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@ServicesActivity, "Помилка сервера", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@ServicesActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
                    }
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