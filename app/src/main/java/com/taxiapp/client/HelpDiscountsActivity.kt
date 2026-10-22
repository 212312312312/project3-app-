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
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import com.taxiapp.client.ui.DiscountAdapter
import com.taxiapp.client.ui.EnterPromoDialog
import com.taxiapp.client.ui.PromoDetailsBottomSheet
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class HelpDiscountsActivity : BaseActivity() {

    private lateinit var adapter: DiscountAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: LinearLayout
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_help_discounts)

        recyclerView = findViewById(R.id.rv_discounts_list)
        progressBar = findViewById(R.id.progressBar)
        emptyState = findViewById(R.id.ll_empty_state)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.btn_add_promo).setOnClickListener {
            val dialog = EnterPromoDialog {
                loadDiscounts()
            }
            dialog.show(supportFragmentManager, "PromoDialog")
        }

        adapter = DiscountAdapter { promoItem ->
            val bottomSheet = PromoDetailsBottomSheet(promoItem)
            bottomSheet.show(supportFragmentManager, "PromoDetails")
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadDiscounts()
    }

    private fun loadDiscounts() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getClientPromos()
                if (isFinishing || isDestroyed) return@launch
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val allPromos = response.body() ?: emptyList()
                    val activeDiscounts = allPromos.filter { it.isRewardAvailable }

                    if (activeDiscounts.isEmpty()) {
                        showEmptyState()
                    } else {
                        showList(activeDiscounts)
                    }
                } else {
                    Toast.makeText(this@HelpDiscountsActivity, "Помилка завантаження", Toast.LENGTH_SHORT).show()
                    showEmptyState()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                progressBar.visibility = View.GONE
                Toast.makeText(this@HelpDiscountsActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
                showEmptyState()
            }
        }
    }

    private fun showList(list: List<ClientPromoProgressDto>) {
        recyclerView.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        adapter.submitList(list)
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        adapter.submitList(emptyList())
    }
}