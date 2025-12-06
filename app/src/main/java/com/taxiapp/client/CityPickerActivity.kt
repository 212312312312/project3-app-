package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.ui.CityAdapter
import com.taxiapp.client.utils.CityDatabase
import com.taxiapp.client.utils.ViewUtils

class CityPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_CITY_NAME = "city_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_city_picker)

        val recyclerView = findViewById<RecyclerView>(R.id.cities_recycler_view)

        // Список міст з бази
        val cityList = CityDatabase.regions.keys.toList().sorted()

        val adapter = CityAdapter(cityList) { selectedCity ->
            val intent = Intent()
            intent.putExtra(RESULT_CITY_NAME, selectedCity)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
}