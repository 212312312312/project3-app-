package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.taxiapp.client.utils.CityData
import com.taxiapp.client.utils.CityDatabase
import com.taxiapp.client.utils.SessionManager

class ProfileActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var tvCity: TextView
    private lateinit var tvName: TextView
    private lateinit var themeSwitch: SwitchCompat

    private val detailsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            tvName.text = sessionManager.getUserName()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        sessionManager = SessionManager(applicationContext)

        // --- ІНІЦІАЛІЗАЦІЯ ---
        tvCity = findViewById(R.id.tv_current_city)      // Є в XML
        tvName = findViewById(R.id.tv_user_name_preview) // Є в XML
        themeSwitch = findViewById(R.id.switch_theme)    // Є в XML

        // --- ДАНІ ---
        val city = sessionManager.fetchUserCity()
        tvCity.text = city?.name ?: "Не обрано"
        tvName.text = sessionManager.getUserName()

        // --- ТЕМА ---
        themeSwitch.isChecked = sessionManager.isDarkMode()
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.saveThemeMode(isChecked)
            if (isChecked) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        // --- КЛІКИ ---

        // 1. Профіль (відкриває UserDetailsActivity)
        findViewById<CardView>(R.id.btn_open_profile_details).setOnClickListener {
            detailsLauncher.launch(Intent(this, UserDetailsActivity::class.java))
        }

        // 2. Регіон
        findViewById<CardView>(R.id.btn_change_city).setOnClickListener {
            showCitySelectorDialog()
        }

        // 3. Статистика
        findViewById<Button>(R.id.btn_statistics).setOnClickListener {
            Toast.makeText(this, "Скоро буде доступно", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCitySelectorDialog() {
        val regionNames = CityDatabase.regions.keys.toTypedArray()
        regionNames.sort()
        AlertDialog.Builder(this)
            .setTitle("Ваш регіон")
            .setItems(regionNames) { _, which ->
                val selectedRegionName = regionNames[which]
                val regionData = CityDatabase.regions[selectedRegionName]!!
                val newCity = CityData(selectedRegionName, regionData.center.latitude, regionData.center.longitude, regionData.zoom)
                sessionManager.saveUserCity(newCity)

                tvCity.text = selectedRegionName
                setResult(RESULT_OK)
            }
            .show()
    }
}