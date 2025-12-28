package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.ui.HistoryAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageView
    private lateinit var adapter: HistoryAdapter
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_history)

        sessionManager = SessionManager(this)

        initUI()
        loadHistory()
    }

    private fun initUI() {
        recyclerView = findViewById(R.id.history_recycler_view)
        emptyView = findViewById(R.id.tv_empty_history)
        progressBar = findViewById(R.id.progress_bar)
        btnBack = findViewById(R.id.btn_back)

        btnBack.setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter(emptyList())
        recyclerView.adapter = adapter
    }

    private fun loadHistory() {
        val token = sessionManager.fetchAuthToken()
        if (token == null) {
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE

        ApiClient.instance.getHistory("Bearer $token").enqueue(object : Callback<List<TaxiOrderDto>> {
            override fun onResponse(call: Call<List<TaxiOrderDto>>, response: Response<List<TaxiOrderDto>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val allOrders = response.body()!!

                    // ФИЛЬТР: ТОЛЬКО УСПЕШНЫЕ (COMPLETED)
                    val successfulOrders = allOrders.filter { it.status == "COMPLETED" }

                    if (successfulOrders.isNotEmpty()) {
                        adapter.submitList(successfulOrders)
                        emptyView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    } else {
                        showEmpty()
                    }
                } else {
                    showEmpty()
                }
            }

            override fun onFailure(call: Call<List<TaxiOrderDto>>, t: Throwable) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@HistoryActivity, "Помилка завантаження", Toast.LENGTH_SHORT).show()
                showEmpty()
            }
        })
    }

    private fun showEmpty() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }
}