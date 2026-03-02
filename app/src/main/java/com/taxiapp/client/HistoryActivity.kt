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
import com.google.android.material.tabs.TabLayout
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
    private lateinit var tabLayout: TabLayout

    private var fullOrderList: List<TaxiOrderDto> = emptyList()

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

        try {
            tabLayout = findViewById(R.id.history_tabs)

            if (tabLayout.tabCount == 0) {
                tabLayout.addTab(tabLayout.newTab().setText("Активні"))
                tabLayout.addTab(tabLayout.newTab().setText("Архів"))
            }

            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    filterList(tab?.position ?: 0)
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        } catch (e: Exception) {
            // Игнорируем ошибки UI если табов нет
        }

        btnBack.setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)

        // --- ВАЖНО: Передаем функцию отмены заказа в адаптер ---
        adapter = HistoryAdapter(
            orders = emptyList(),
            onItemClick = { orderId ->
                val currentTab = try { tabLayout.selectedTabPosition } catch (e: Exception) { 0 }
                if (currentTab == 0) { // Вкладка "Активні"
                    sessionManager.saveActiveOrderId(orderId)
                    val intent = android.content.Intent(this@HistoryActivity, HomeActivity::class.java)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    finish()
                } else {
                    android.widget.Toast.makeText(this@HistoryActivity, "Це замовлення знаходиться в архіві", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onCancelClick = { orderId ->
                // Вызываем твою старую добрую функцию отмены!
                cancelOrder(orderId)
            }
        )
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
                    fullOrderList = response.body()!!

                    val currentTab = try { tabLayout.selectedTabPosition } catch (e: Exception) { 0 }
                    filterList(if (currentTab < 0) 0 else currentTab)
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

    private fun filterList(tabIndex: Int) {
        val filtered = if (tabIndex == 0) {
            // АКТИВНІ
            fullOrderList.filter {
                it.status == "SCHEDULED" ||
                        it.status == "REQUESTED" ||
                        it.status == "OFFERING" ||
                        it.status == "ACCEPTED" ||
                        it.status == "DRIVER_ARRIVED" ||
                        it.status == "IN_PROGRESS"
            }
        } else {
            // АРХІВ
            fullOrderList.filter {
                it.status == "COMPLETED" ||
                        it.status == "CANCELLED"
            }
        }

        if (filtered.isNotEmpty()) {
            adapter.submitList(filtered)
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        } else {
            showEmpty()
        }
    }

    private fun showEmpty() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    // --- НОВЫЙ МЕТОД: Отмена заказа ---
    private fun cancelOrder(orderId: Long) {
        val token = sessionManager.fetchAuthToken() ?: return

        // Показываем простой Toast, что процесс пошел
        Toast.makeText(this, "Скасування...", Toast.LENGTH_SHORT).show()

        ApiClient.instance.cancelOrder("Bearer $token", orderId).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                if (response.isSuccessful) {
                    Toast.makeText(this@HistoryActivity, "Замовлення скасовано", Toast.LENGTH_SHORT).show()
                    // Перезагружаем список, чтобы заказ улетел в архив
                    loadHistory()
                } else {
                    val msg = try { response.errorBody()?.string() } catch (e: Exception) { response.message() }
                    Toast.makeText(this@HistoryActivity, "Помилка: $msg", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                Toast.makeText(this@HistoryActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
            }
        })
    }
}