package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.ui.CityAdapter
import com.taxiapp.client.utils.CityDatabase
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils

class CityPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_CITY_NAME = "city_name"
    }

    private lateinit var rvCities: RecyclerView
    private lateinit var adapter: CityAdapter
    private lateinit var etSearch: EditText
    private lateinit var btnBack: ImageView

    private var allCities: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_city_picker)

        val sessionManager = SessionManager(applicationContext)

        // 1. ОБЪЯВЛЯЕМ ПЕРЕМЕННУЮ ТОЛЬКО ОДИН РАЗ ЗДЕСЬ
        val currentCityName = sessionManager.fetchUserCity()?.name

        rvCities = findViewById(R.id.city_recycler_view)
        etSearch = findViewById(R.id.et_search_city)
        btnBack = findViewById(R.id.btn_back)

        btnBack.setOnClickListener { finish() }

        // 2. ЛОГИКА СОРТИРОВКИ (Текущий город — первый)
        val sortedList = CityDatabase.regions.keys.toList().sorted()

        allCities = if (currentCityName != null && sortedList.contains(currentCityName)) {
            // Создаем новый список: [Текущий] + [Все остальные без Текущего]
            listOf(currentCityName) + (sortedList - currentCityName)
        } else {
            sortedList
        }

        rvCities.layoutManager = LinearLayoutManager(this)

        // 3. ПЕРЕДАЕМ currentCityName В АДАПТЕР
        adapter = CityAdapter(allCities, currentCityName) { selectedCity ->
            val intent = Intent()
            intent.putExtra(RESULT_CITY_NAME, selectedCity)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
        rvCities.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCities(s.toString())
            }
        })
    }
    private fun filterCities(query: String) {
        val filtered = if (query.isEmpty()) {
            allCities
        } else {
            allCities.filter { it.contains(query, ignoreCase = true) }
        }
        adapter.updateList(filtered)
    }
}