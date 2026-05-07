package com.taxiapp.client

import android.app.Dialog
import android.content.Intent
import com.taxiapp.client.network.dto.MessageResponseDto
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.InitBindCardResponse
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.network.ClientProfileResponse
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PaymentActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager

    private lateinit var tvCardTitle: TextView
    private lateinit var ivUnbindCard: ImageView
    private lateinit var ivCheckCash: ImageView
    private lateinit var ivCheckCard: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        sessionManager = SessionManager(this)

        tvCardTitle = findViewById(R.id.tv_card_title)
        ivUnbindCard = findViewById(R.id.iv_unbind_card)
        ivCheckCash = findViewById(R.id.iv_check_cash)
        ivCheckCard = findViewById(R.id.iv_check_card)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        updateUI()

        // Клик по "Наличные"
        findViewById<RelativeLayout>(R.id.btn_cash).setOnClickListener {
            sessionManager.savePaymentMethod("CASH")
            updateUI()
        }

        // Клик по "Карта"
        findViewById<RelativeLayout>(R.id.btn_card).setOnClickListener {
            val mask = sessionManager.getCardMask()
            if (mask.isNullOrEmpty()) {
                bindNewCard()
            } else {
                sessionManager.savePaymentMethod("CARD")
                updateUI()
            }
        }

        // Клик по крестику (Отвязать карту)
        ivUnbindCard.setOnClickListener {
            showUnbindCardDialog()
        }
    }

    // Показываем диалог подтверждения
    private fun showUnbindCardDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_delete_card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)
        val btnConfirm = dialog.findViewById<Button>(R.id.btn_confirm_delete)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            // Блокируем кнопку, чтобы не нажали дважды
            btnConfirm.isEnabled = false
            btnConfirm.text = "Видалення..."

            val token = "Bearer ${sessionManager.fetchAuthToken()}"

            // Отправляем запрос на сервер
            ApiClient.instance.unbindCard(token).enqueue(object : Callback<MessageResponseDto> {
                override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
                    dialog.dismiss()
                    if (response.isSuccessful) {
                        // Только если сервер подтвердил удаление, удаляем локально
                        sessionManager.saveCardMask(null)
                        sessionManager.savePaymentMethod("CASH")
                        updateUI()
                        Toast.makeText(this@PaymentActivity, "Картку видалено", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@PaymentActivity, "Помилка при видаленні", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<MessageResponseDto>, t: Throwable) {
                    dialog.dismiss()
                    Toast.makeText(this@PaymentActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
                }
            })
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            fetchClientProfile()
        }, 2000)
    }

    private fun fetchClientProfile() {
        val token = "Bearer ${sessionManager.fetchAuthToken()}"

        ApiClient.instance.getClientProfile(token).enqueue(object : Callback<ClientProfileResponse> {
            override fun onResponse(call: Call<ClientProfileResponse>, response: Response<ClientProfileResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val profile = response.body()!!
                    sessionManager.saveCardMask(profile.cardMask)

                    if (profile.cardMask.isNullOrEmpty() && sessionManager.fetchPaymentMethod() == "CARD") {
                        sessionManager.savePaymentMethod("CASH")
                    }
                    updateUI()
                }
            }

            override fun onFailure(call: Call<ClientProfileResponse>, t: Throwable) {
                t.printStackTrace()
            }
        })
    }

    private fun updateUI() {
        val mask = sessionManager.getCardMask()
        val method = sessionManager.fetchPaymentMethod()

        // Используем INVISIBLE вместо GONE для галочек, чтобы ширина не скакала
        if (!mask.isNullOrEmpty()) {
            tvCardTitle.text = getString(R.string.saved_card, mask)
            ivUnbindCard.visibility = View.VISIBLE
        } else {
            tvCardTitle.text = getString(R.string.bind_card)
            ivUnbindCard.visibility = View.GONE

            if (method == "CARD") {
                sessionManager.savePaymentMethod("CASH")
            }
        }

        val currentMethod = sessionManager.fetchPaymentMethod()
        ivCheckCash.visibility = if (currentMethod == "CASH") View.VISIBLE else View.INVISIBLE
        ivCheckCard.visibility = if (currentMethod == "CARD" && !mask.isNullOrEmpty()) View.VISIBLE else View.INVISIBLE
    }

    private fun bindNewCard() {
        val token = "Bearer ${sessionManager.fetchAuthToken()}"

        ApiClient.instance.initBindCard(token).enqueue(object : Callback<InitBindCardResponse> {
            override fun onResponse(call: Call<InitBindCardResponse>, response: Response<InitBindCardResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val url = response.body()!!.paymentUrl
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } else {
                    Toast.makeText(this@PaymentActivity, getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<InitBindCardResponse>, t: Throwable) {
                Toast.makeText(this@PaymentActivity, getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
            }
        })
    }
}