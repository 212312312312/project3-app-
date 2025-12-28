package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import com.taxiapp.client.ui.PromoAdapter
import com.taxiapp.client.ui.PromoDetailsBottomSheet
import com.taxiapp.client.ui.EnterPromoDialog // <-- Використовуємо наш клас
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PromoActivity : AppCompatActivity() {

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

        // Кнопка на порожньому екрані відкриває НАШ діалог
        emptyState.setOnClickListener { showPromoCodeDialog() }

        // Якщо в activity_promo.xml є кнопка для відкриття діалогу (наприклад, плюсик),
        // знайди її і теж додай слухач:
        // findViewById<View>(R.id.btn_add_promo)?.setOnClickListener { showPromoCodeDialog() }

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
        // Не ховаємо список одразу, щоб не блимало, якщо дані завантажаться швидко

        ApiClient.instance.getClientPromos("Bearer $token").enqueue(object : Callback<List<ClientPromoProgressDto>> {
            override fun onResponse(call: Call<List<ClientPromoProgressDto>>, response: Response<List<ClientPromoProgressDto>>) {
                progressBar.visibility = View.GONE

                if (response.isSuccessful) {
                    val list = response.body() ?: emptyList()

                    if (list.isEmpty()) {
                        showEmptyState()
                    } else {
                        showList(list)
                    }
                } else {
                    showEmptyState()
                }
            }

            override fun onFailure(call: Call<List<ClientPromoProgressDto>>, t: Throwable) {
                progressBar.visibility = View.GONE
                showEmptyState() // Або Toast про помилку
            }
        })
    }

    private fun showList(list: List<ClientPromoProgressDto>) {
        recyclerView.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        adapter.submitList(list)
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        // Очищаємо список адаптера
        adapter.submitList(emptyList())
    }

    // --- ВІДКРИТТЯ ДІАЛОГУ ---
    private fun showPromoCodeDialog() {
        val dialog = EnterPromoDialog(
            onSuccess = {
                // Коли код успішно активовано, перезавантажуємо список
                loadPromos()
            }
        )
        dialog.show(supportFragmentManager, "EnterPromoDialog")
    }
}