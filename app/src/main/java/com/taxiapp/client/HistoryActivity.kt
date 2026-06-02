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
import com.taxiapp.client.network.dto.CancellationReasonDto
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.ui.HistoryAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class HistoryActivity : BaseActivity() {

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

            for (i in 0 until tabLayout.tabCount) {
                val tabView = tabLayout.getTabAt(i)?.view

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    tabView?.tooltipText = null
                }

                // ГОЛОВНИЙ ФІКС: Перехоплюємо довге натискання, щоб система не показувала блок
                tabView?.setOnLongClickListener { true }
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
                showCancelReasonDialog(orderId)
            }
        )
        recyclerView.adapter = adapter
    }

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE
        ApiClient.instance.getHistory().enqueue(object : Callback<List<TaxiOrderDto>> {
            override fun onResponse(call: Call<List<TaxiOrderDto>>, response: Response<List<TaxiOrderDto>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    fullOrderList = response.body() ?: emptyList()

                    // --- ФИКС: Узнаем текущую вкладку и пропускаем список через фильтр ---
                    val currentTab = try { tabLayout.selectedTabPosition } catch (e: Exception) { 0 }
                    filterList(currentTab)
                    // filterList сам решит: показывать пустой экран или передать данные в adapter

                } else {
                    showEmpty()
                }
            }

            override fun onFailure(call: Call<List<TaxiOrderDto>>, t: Throwable) {
                progressBar.visibility = View.GONE
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
    private fun showCancelReasonDialog(orderId: Long) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_cancel_reason)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        val rgReasons = dialog.findViewById<android.widget.RadioGroup>(R.id.rg_cancel_reasons)
        val btnConfirm = dialog.findViewById<android.widget.Button>(R.id.btn_confirm_cancel)

        // Загружаем актуальные причины с сервера для CLIENT
        ApiClient.instance.getCancellationReasons("CLIENT").enqueue(object : Callback<List<CancellationReasonDto>> {
            override fun onResponse(call: Call<List<CancellationReasonDto>>, response: Response<List<CancellationReasonDto>>) {
                if (response.isSuccessful) {
                    val reasons = response.body()?.filter { it.isActive } ?: emptyList()
                    rgReasons.removeAllViews()

                    // Динамически наполняем RadioGroup причинами
                    reasons.forEach { reason ->
                        val radioButton = android.widget.RadioButton(this@HistoryActivity).apply {
                            id = View.generateViewId()
                            text = reason.reasonText
                            tag = reason.reasonText // Сохраняем текст в tag для удобного извлечения
                            textSize = 16f
                            setPadding(16, 24, 16, 24)
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@HistoryActivity, R.color.text_primary))

                            // --- Желтый цвет ---
                            val taxiYellowStateList = androidx.core.content.ContextCompat.getColorStateList(this@HistoryActivity, R.color.taxi_yellow)
                            androidx.core.widget.CompoundButtonCompat.setButtonTintList(this, taxiYellowStateList)

                            // --- НОВОЕ: Убираем системный фон/рипл при нажатии ---
                            background = null
                            // ----------------------------------------------------
                        }
                        rgReasons.addView(radioButton)
                    }
                } else {
                    Toast.makeText(this@HistoryActivity, "Помилка завантаження причин отмени", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<CancellationReasonDto>>, t: Throwable) {
                Toast.makeText(this@HistoryActivity, "Помилка мережі при завантаженні причин", Toast.LENGTH_SHORT).show()
            }
        })

        // Обработка кнопки подтверждения отмены
        btnConfirm.setOnClickListener {
            val checkedId = rgReasons.checkedRadioButtonId
            if (checkedId == -1) {
                Toast.makeText(this, "Будь ласка, виберіть причину скасування", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedRb = rgReasons.findViewById<android.widget.RadioButton>(checkedId)
            val selectedReasonText = selectedRb.tag as? String

            dialog.dismiss()
            cancelOrder(orderId, selectedReasonText) // Отправляем заказ на сервер вместе с причиной
        }

        dialog.show()
    }

    // --- ОБНОВЛЕННЫЙ МЕТОД: Отмена заказа с передачей причины ---
    private fun cancelOrder(orderId: Long, reasonText: String? = null) {
        Toast.makeText(this, "Скасування...", Toast.LENGTH_SHORT).show()

        // Используем очищенный вызов ApiService с поддержкой reasonText
        ApiClient.instance.cancelOrder(orderId, reasonText).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                if (response.isSuccessful) {
                    Toast.makeText(this@HistoryActivity, "Замовлення скасовано", Toast.LENGTH_SHORT).show()
                    loadHistory() // Перезагружаем список поездок
                } else {
                    val msg = try { response.errorBody()?.string() } catch (e: Exception) { response.message() }
                    Toast.makeText(this@HistoryActivity, "Помилка: $msg", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                Toast.makeText(this@HistoryActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
            }
        })
    }
}