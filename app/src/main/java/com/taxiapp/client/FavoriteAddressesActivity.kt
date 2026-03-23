package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.Place
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils

class FavoriteAddressesActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    // UI
    private lateinit var tvHomeTitle: TextView
    private lateinit var tvHomeAddress: TextView
    private lateinit var tvWorkTitle: TextView
    private lateinit var tvWorkAddress: TextView

    // Режим вибору (щоб знати, що ми зараз редагуємо)
    private var isEditingHome = true

    private val addressPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val action = data.getStringExtra(AddressPickerActivity.RESULT_ACTION)

            if (action == "place") {
                val name = data.getStringExtra(AddressPickerActivity.RESULT_NAME)
                val lat = data.getDoubleExtra(AddressPickerActivity.RESULT_LAT, 0.0)
                val lng = data.getDoubleExtra(AddressPickerActivity.RESULT_LNG, 0.0)

                // Створюємо об'єкт Place
                val place = Place.builder()
                    .setName(name)
                    .setLatLng(LatLng(lat, lng))
                    .build()

                // Зберігаємо
                if (isEditingHome) {
                    sessionManager.saveHomeAddress(place)
                } else {
                    sessionManager.saveWorkAddress(place)
                }

                // Оновлюємо екран
                updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_favorite_addresses)

        sessionManager = SessionManager(applicationContext)

        tvHomeTitle = findViewById(R.id.tv_home_title)
        tvHomeAddress = findViewById(R.id.tv_home_address)
        tvWorkTitle = findViewById(R.id.tv_work_title)
        tvWorkAddress = findViewById(R.id.tv_work_address)

        // Кнопка Назад
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // Клік ДІМ
        findViewById<View>(R.id.card_home).setOnClickListener {
            isEditingHome = true
            openPicker()
        }

        // Клік РОБОТА
        findViewById<View>(R.id.card_work).setOnClickListener {
            isEditingHome = false
            openPicker()
        }

        updateUI()
    }

    private fun updateUI() {
        val home = sessionManager.getHomeAddress()
        val work = sessionManager.getWorkAddress()

        // --- ДІМ ---
        if (home != null) {
            tvHomeTitle.text = "Дім"
            tvHomeAddress.text = cleanAddress(home.name ?: "")
            // Если адрес уже добавлен, пусть будет text_secondary (он хорошо читается)
            tvHomeAddress.setTextColor(getColor(R.color.text_secondary))
        } else {
            tvHomeTitle.text = "Додати дім"
            tvHomeAddress.text = "Вкажіть адресу"
            // МЕНЯЕМ ЦВЕТ ПОДСКАЗКИ НА text_hint
            tvHomeAddress.setTextColor(getColor(R.color.text_hint))
        }

        // --- РОБОТА ---
        if (work != null) {
            tvWorkTitle.text = "Робота"
            tvWorkAddress.text = cleanAddress(work.name ?: "")
            // Если адрес уже добавлен
            tvWorkAddress.setTextColor(getColor(R.color.text_secondary))
        } else {
            tvWorkTitle.text = "Додати роботу"
            tvWorkAddress.text = "Вкажіть адресу"
            // МЕНЯЕМ ЦВЕТ ПОДСКАЗКИ НА text_hint
            tvWorkAddress.setTextColor(getColor(R.color.text_hint))
        }
    }

    private fun openPicker() {
        val intent = Intent(this, AddressPickerActivity::class.java)

        // --- ИЗМЕНЕНИЯ ЗДЕСЬ ---
        // Передаем правильный режим
        if (isEditingHome) {
            intent.putExtra(AddressPickerActivity.EXTRA_PICKER_MODE, AddressPickerActivity.MODE_SAVE_HOME)
        } else {
            intent.putExtra(AddressPickerActivity.EXTRA_PICKER_MODE, AddressPickerActivity.MODE_SAVE_WORK)
        }

        intent.putExtra(AddressPickerActivity.EXTRA_HIDE_MY_LOCATION, true)

        val currentCity = sessionManager.fetchUserCity()
        if (currentCity != null) {
            intent.putExtra("city_lat", currentCity.lat)
            intent.putExtra("city_lng", currentCity.lng)
        }

        addressPickerLauncher.launch(intent)
    }

    // Функція очищення (дубльована, але це найнадійніше)
    private fun cleanAddress(fullAddress: String): String {
        var result = fullAddress
        val removeList = listOf(", Україна", "Україна", ", Ukraine", "Ukraine", ", UA", "UA", ", Київ", "Київ", ", Львів", "Львів", ", Одеса", "Одеса", ", 01000", ", 02000")
        for (word in removeList) { result = result.replace(word, "", ignoreCase = true) }
        result = result.replace(Regex(",\\s?\\d{5}"), "")
        return result.trim().removeSuffix(",").trim()
    }
}