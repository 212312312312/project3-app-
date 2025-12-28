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
import com.taxiapp.client.network.dto.NewsDto
import com.taxiapp.client.ui.NewsAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import android.widget.LinearLayout
import retrofit2.Response

class NewsActivity : AppCompatActivity() {

    private lateinit var adapter: NewsAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: LinearLayout
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_news)

        // Init Views
        recyclerView = findViewById(R.id.rv_news_list)
        progressBar = findViewById(R.id.pb_loading)
        emptyState = findViewById(R.id.ll_empty_state)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // Setup RecyclerView
        adapter = NewsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadNews()
    }

    private fun loadNews() {
        val sessionManager = SessionManager(this)
        val token = sessionManager.fetchAuthToken()

        if (token == null) {
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE

        ApiClient.instance.getClientNews("Bearer $token").enqueue(object : Callback<List<NewsDto>> {
            override fun onResponse(call: Call<List<NewsDto>>, response: Response<List<NewsDto>>) {
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val newsList = response.body() ?: emptyList()
                    if (newsList.isEmpty()) {
                        emptyState.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        adapter.submitList(newsList)
                    }
                } else {
                    Toast.makeText(this@NewsActivity, "Помилка завантаження", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<NewsDto>>, t: Throwable) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@NewsActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
            }
        })
    }
}