package com.taxiapp.client

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.ui.HistoryAdapter
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HistoryActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageView
    private lateinit var adapter: HistoryAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var tabLayout: TabLayout

    // --- ПАРАМЕТРИ ПАГІНАЦІЇ ---
    private var currentPage = 0
    private var isLoading = false
    private var isLastPage = false
    private val pageSize = 30
    private val allLoadedOrders = mutableListOf<TaxiOrderDto>()
    private var fullOrderList: List<TaxiOrderDto> = emptyList()

    private var activeHistoryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_history)

        sessionManager = SessionManager(this)
        initUI()
    }

    override fun onResume() {
        super.onResume()
        loadHistory(page = 0, isInitial = true)
    }

    override fun onDestroy() {
        activeHistoryJob?.cancel()
        super.onDestroy()
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tabView?.tooltipText = null
                }
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
            // Ігноруємо відсутність табів у розмітці
        }

        btnBack.setOnClickListener { finish() }

        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !isLoading && !isLastPage) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if (totalItemCount >= 5 &&
                        (visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 4 &&
                        firstVisibleItemPosition >= 0
                    ) {
                        loadHistory(page = currentPage + 1, isInitial = false)
                    }
                }
            }
        })

        adapter = HistoryAdapter(
            orders = emptyList(),
            onItemClick = { orderId ->
                val currentTab = try { tabLayout.selectedTabPosition } catch (e: Exception) { 0 }
                if (currentTab == 0) {
                    sessionManager.saveActiveOrderId(orderId)
                    val intent = Intent(this@HistoryActivity, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@HistoryActivity, "Це замовлення знаходиться в архіві", Toast.LENGTH_SHORT).show()
                }
            },
            onCancelClick = { orderId ->
                showCancelReasonDialog(orderId)
            }
        )
        recyclerView.adapter = adapter
    }

    private fun loadHistory(page: Int = 0, isInitial: Boolean = true) {
        if (isLoading && !isInitial) return
        if (isInitial) {
            activeHistoryJob?.cancel()
            currentPage = 0
            isLastPage = false
            progressBar.visibility = View.VISIBLE
        }
        isLoading = true

        activeHistoryJob = lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getHistory(page = page, size = pageSize)
                if (isFinishing || isDestroyed) return@launch
                isLoading = false
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val fetched = response.body() ?: emptyList()
                    if (fetched.size < pageSize) {
                        isLastPage = true
                    }

                    if (isInitial) {
                        allLoadedOrders.clear()
                        allLoadedOrders.addAll(fetched)
                    } else {
                        val existingIds = allLoadedOrders.mapTo(HashSet()) { it.id }
                        fetched.forEach { order ->
                            if (existingIds.add(order.id)) {
                                allLoadedOrders.add(order)
                            }
                        }
                        currentPage = page
                    }
                    fullOrderList = allLoadedOrders.toList()

                    val currentTab = try { tabLayout.selectedTabPosition } catch (e: Exception) { 0 }
                    filterList(currentTab)
                } else if (isInitial && allLoadedOrders.isEmpty()) {
                    showEmpty()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                isLoading = false
                progressBar.visibility = View.GONE
                if (isInitial && allLoadedOrders.isEmpty()) {
                    showEmpty()
                }
            }
        }
    }

    private fun filterList(tabIndex: Int) {
        val filtered = if (tabIndex == 0) {
            fullOrderList.filter {
                it.status == "SCHEDULED" ||
                        it.status == "REQUESTED" ||
                        it.status == "OFFERING" ||
                        it.status == "ACCEPTED" ||
                        it.status == "DRIVER_ARRIVED" ||
                        it.status == "IN_PROGRESS" ||
                        it.status == "ARRIVED_AT_WAYPOINT"
            }
        } else {
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
            adapter.submitList(emptyList())
            showEmpty()
        }
    }

    private fun showEmpty() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    private fun showCancelReasonDialog(orderId: String) {
        if (isFinishing || isDestroyed) return
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_cancel_reason)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        val rgReasons = dialog.findViewById<RadioGroup>(R.id.rg_cancel_reasons)
        val btnConfirm = dialog.findViewById<Button>(R.id.btn_confirm_cancel)

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getCancellationReasons("CLIENT")
                if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
                if (response.isSuccessful) {
                    val reasons = response.body()?.filter { it.isActive } ?: emptyList()
                    rgReasons.removeAllViews()

                    reasons.forEach { reason ->
                        val radioButton = RadioButton(this@HistoryActivity).apply {
                            id = View.generateViewId()
                            text = reason.reasonText
                            tag = reason.reasonText
                            textSize = 16f
                            setPadding(16, 24, 16, 24)
                            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_primary))

                            val taxiYellowStateList = ContextCompat.getColorStateList(this@HistoryActivity, R.color.taxi_yellow)
                            CompoundButtonCompat.setButtonTintList(this, taxiYellowStateList)
                            background = null
                        }
                        rgReasons.addView(radioButton)
                    }
                } else {
                    Toast.makeText(this@HistoryActivity, "Помилка завантаження причин скасування", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@HistoryActivity, "Помилка мережі при завантаженні причин", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnConfirm.setOnClickListener {
            val checkedId = rgReasons.checkedRadioButtonId
            if (checkedId == -1) {
                Toast.makeText(this, "Будь ласка, виберіть причину скасування", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedRb = rgReasons.findViewById<RadioButton>(checkedId)
            val selectedReasonText = selectedRb.tag as? String

            dialog.dismiss()
            cancelOrder(orderId, selectedReasonText)
        }

        dialog.show()
    }

    private fun cancelOrder(orderId: String, reasonText: String? = null) {
        Toast.makeText(this, "Скасування...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.cancelOrder(orderId, reasonText)
                if (isFinishing || isDestroyed) return@launch
                if (response.isSuccessful) {
                    Toast.makeText(this@HistoryActivity, "Замовлення скасовано", Toast.LENGTH_SHORT).show()
                    if (sessionManager.fetchActiveOrderId() == orderId) {
                        sessionManager.clearActiveOrderId()
                    }
                    loadHistory(page = 0, isInitial = true)
                } else {
                    val msg = try { response.errorBody()?.string() } catch (e: Exception) { response.message() }
                    Toast.makeText(this@HistoryActivity, "Помилка: $msg", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@HistoryActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}