package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.ui.NewsAdapter
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class NewsActivity : BaseActivity() {

    private lateinit var adapter: NewsAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: LinearLayout
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_news)

        recyclerView = findViewById(R.id.rv_news_list)
        progressBar = findViewById(R.id.pb_loading)
        emptyState = findViewById(R.id.ll_empty_state)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        adapter = NewsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadNews()
    }

    private fun loadNews() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getClientNews()
                if (isFinishing || isDestroyed) return@launch
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                progressBar.visibility = View.GONE
                Toast.makeText(this@NewsActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
            }
        }
    }
}