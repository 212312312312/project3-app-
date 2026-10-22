package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import com.taxiapp.client.ui.EnterPromoDialog
import com.taxiapp.client.ui.PromoAdapter
import com.taxiapp.client.ui.PromoDetailsBottomSheet
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PromoActivity : BaseActivity() {

    private lateinit var adapter: PromoAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_promo)

        sessionManager = SessionManager(this)

        recyclerView = findViewById(R.id.rv_promo_list)
        progressBar = findViewById(R.id.pb_loading)
        emptyState = findViewById(R.id.ll_empty_state)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        adapter = PromoAdapter { promoItem ->
            val bottomSheet = PromoDetailsBottomSheet(promoItem)
            bottomSheet.show(supportFragmentManager, "PromoDetails")
        }

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadPromos()
    }

    private fun loadPromos() {
        val token = sessionManager.fetchAuthToken()
        if (token == null) {
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getClientPromos()
                if (isFinishing || isDestroyed) return@launch
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val fullList = response.body() ?: emptyList()
                    val activeTasks = fullList.filter { !it.isRewardAvailable }

                    if (activeTasks.isEmpty()) {
                        showEmptyState()
                    } else {
                        showList(activeTasks)
                    }
                } else {
                    showEmptyState()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                progressBar.visibility = View.GONE
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

    private fun showPromoCodeDialog() {
        val dialog = EnterPromoDialog(
            onSuccess = {
                loadPromos()
            }
        )
        dialog.show(supportFragmentManager, "EnterPromoDialog")
    }
}