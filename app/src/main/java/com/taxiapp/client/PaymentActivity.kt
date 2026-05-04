package com.taxiapp.client

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
        findViewById<LinearLayout>(R.id.btn_cash).setOnClickListener {
            sessionManager.savePaymentMethod("CASH")
            updateUI()
        }

        // Клик по "Карта"
        findViewById<LinearLayout>(R.id.btn_card).setOnClickListener {
            val mask = sessionManager.getCardMask()
            if (mask.isNullOrEmpty()) {
                // Карты нет - инициируем процесс привязки
                bindNewCard()
            } else {
                // Карта есть - выбираем её как способ оплаты
                sessionManager.savePaymentMethod("CARD")
                updateUI()
            }
        }

        // Клик по крестику (Отвязать карту)
        ivUnbindCard.setOnClickListener {
            // В идеале тут нужен запрос к серверу на удаление токена.
            // Пока просто удаляем локально и переключаем на наличные.
            sessionManager.saveCardMask(null)
            sessionManager.savePaymentMethod("CASH")
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        // Даем серверу 2 секунды на то, чтобы обработать callback от LiqPay
        // перед тем, как запрашивать обновленный профиль.
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

                    // Обновляем маску карты в локальном хранилище
                    sessionManager.saveCardMask(profile.cardMask)

                    // Если карта отвязана, но был выбран метод CARD - сбрасываем на CASH
                    if (profile.cardMask.isNullOrEmpty() && sessionManager.fetchPaymentMethod() == "CARD") {
                        sessionManager.savePaymentMethod("CASH")
                    }

                    // Обновляем экран
                    updateUI()
                }
            }

            override fun onFailure(call: Call<ClientProfileResponse>, t: Throwable) {
                // Если произошла ошибка (например, нет интернета), просто ничего не делаем.
                // Пользователь останется с теми данными, которые были закэшированы локально.
                t.printStackTrace()
            }
        })
    }

    private fun updateUI() {
        val mask = sessionManager.getCardMask()
        val method = sessionManager.fetchPaymentMethod()

        // Текст и крестик отвязки
        if (!mask.isNullOrEmpty()) {
            tvCardTitle.text = getString(R.string.saved_card, mask)
            ivUnbindCard.visibility = View.VISIBLE
        } else {
            tvCardTitle.text = getString(R.string.bind_card)
            ivUnbindCard.visibility = View.GONE

            // Если карты нет, но она выбрана как метод - принудительно ставим наличные
            if (method == "CARD") {
                sessionManager.savePaymentMethod("CASH")
            }
        }

        // Галочки
        val currentMethod = sessionManager.fetchPaymentMethod()
        ivCheckCash.visibility = if (currentMethod == "CASH") View.VISIBLE else View.GONE
        ivCheckCard.visibility = if (currentMethod == "CARD" && !mask.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindNewCard() {
        val token = "Bearer ${sessionManager.fetchAuthToken()}"

        ApiClient.instance.initBindCard(token).enqueue(object : Callback<InitBindCardResponse> {
            override fun onResponse(call: Call<InitBindCardResponse>, response: Response<InitBindCardResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val url = response.body()!!.paymentUrl

                    // Открываем форму LiqPay в браузере устройства
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)

                    // TODO: После возврата из браузера нужно будет обновить профиль пользователя
                    // чтобы получить с сервера маску привязанной карты и сохранить её в SessionManager.
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