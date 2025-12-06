package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import com.taxiapp.client.ui.PromoAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PromoActivity : AppCompatActivity() {

    private lateinit var adapter: PromoAdapter
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_promo)

        val rvList = findViewById<RecyclerView>(R.id.rv_promo_list)
        progressBar = findViewById(R.id.pb_loading)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        adapter = PromoAdapter()
        rvList.adapter = adapter
        rvList.layoutManager = LinearLayoutManager(this)

        loadPromos()
    }

    private fun loadPromos() {
        val sessionManager = SessionManager(this)
        val token = sessionManager.fetchAuthToken()

        if (token == null) {
            Toast.makeText(this, "Помилка авторизації", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE

        // Лог для перевірки, що запит пішов
        android.util.Log.d("PromoDebug", "Sending request to /client/promos")

        ApiClient.instance.getClientPromos("Bearer $token").enqueue(object : Callback<List<ClientPromoProgressDto>> {
            override fun onResponse(call: Call<List<ClientPromoProgressDto>>, response: Response<List<ClientPromoProgressDto>>) {
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val list = response.body() ?: emptyList()

                    // ЛОГ: Скільки прийшло завдань?
                    android.util.Log.d("PromoDebug", "Success! Items count: ${list.size}")

                    if (list.isEmpty()) {
                        Toast.makeText(this@PromoActivity, "Список акцій порожній (додайте їх в БД)", Toast.LENGTH_LONG).show()
                    } else {
                        adapter.submitList(list)
                    }
                } else {
                    // Якщо помилка сервера (403, 500)
                    val error = "Error: ${response.code()} ${response.message()}"
                    android.util.Log.e("PromoDebug", error)
                    Toast.makeText(this@PromoActivity, error, Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<List<ClientPromoProgressDto>>, t: Throwable) {
                progressBar.visibility = View.GONE
                android.util.Log.e("PromoDebug", "Network Failure: ${t.message}")
                Toast.makeText(this@PromoActivity, "Немає зв'язку з сервером", Toast.LENGTH_SHORT).show()
            }
        })
    }
}