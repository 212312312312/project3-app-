package com.taxiapp.client

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.InitBindCardResponse
import com.taxiapp.client.network.ClientProfileResponse
import com.taxiapp.client.network.dto.MessageResponseDto
import com.taxiapp.client.utils.SessionManager
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

    // --- ПЕРЕМЕННЫЕ ДЛЯ УМНОГО ПОЛЛИНГА ---
    private var pollingHandler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var isPolling = false

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

    private fun showUnbindCardDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_delete_card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)
        val btnConfirm = dialog.findViewById<Button>(R.id.btn_confirm_delete)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            btnConfirm.isEnabled = false
            btnConfirm.text = "Видалення..."

            // Вызов очищен от ручной передачи токена
            ApiClient.instance.unbindCard().enqueue(object : Callback<MessageResponseDto> {
                override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
                    dialog.dismiss()
                    if (response.isSuccessful) {
                        sessionManager.saveCardMask(null)
                        sessionManager.savePaymentMethod("CASH")
                        updateUI()
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
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling() // Обязательно останавливаем поллинг при выходе
    }

    private fun fetchClientProfile() {
        // Вызов очищен от ручной передачи токена
        ApiClient.instance.getClientProfile().enqueue(object : Callback<ClientProfileResponse> {
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
        // Вызов очищен от ручной передачи токена
        ApiClient.instance.initBindCard().enqueue(object : Callback<InitBindCardResponse> {
            override fun onResponse(call: Call<InitBindCardResponse>, response: Response<InitBindCardResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val url = response.body()!!.paymentUrl
                    showLiqPayWebView(url)
                } else {
                    Toast.makeText(this@PaymentActivity, getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<InitBindCardResponse>, t: Throwable) {
                Toast.makeText(this@PaymentActivity, getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showLiqPayWebView(url: String) {
        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_liqpay_webview)

        val btnClose = dialog.findViewById<ImageView>(R.id.btn_close_webview)
        val webView = dialog.findViewById<WebView>(R.id.liqpay_webview)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // --- ЗАЩИТА: Полностью блокируем доступ WebView к локальным файлам приложения на устройстве ---
            allowFileAccess = false
            allowContentAccess = false
        }

// --- ЗАЩИТА: Внедряем строгий белый список доменов (Anti-Phishing / Anti-Fraud) ---
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Разрешаем переходы только на официальный шлюз LiqPay и наш доверенный бэкенд
                val isAllowed = url.contains("liqpay.ua") || url.contains("ngrok-free.dev")

                if (!isAllowed) {
                    // Если WebView пытаются увести на левый фишинговый сайт — жестко блокируем переход
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }
        webView.webChromeClient = WebChromeClient()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        // Когда диалог закрывается (по любой причине), останавливаем опрос
        dialog.setOnDismissListener {
            stopPolling()
            fetchClientProfile()
        }

        webView.loadUrl(url)
        dialog.show()

        // ЗАПУСКАЕМ НАШ УМНЫЙ ПОЛЛИНГ!
        startPollingCardStatus(dialog)
    }

    // ==========================================
    // ЛОГИКА УМНОГО ПОЛЛИНГА
    // ==========================================
    private fun startPollingCardStatus(dialog: Dialog) {
        isPolling = true
        pollingRunnable = object : Runnable {
            override fun run() {
                if (!isPolling) return

                // Вызов очищен от ручной передачи токена
                ApiClient.instance.getClientProfile().enqueue(object : Callback<ClientProfileResponse> {
                    override fun onResponse(call: Call<ClientProfileResponse>, response: Response<ClientProfileResponse>) {
                        if (response.isSuccessful && response.body() != null) {
                            val profile = response.body()!!

                            // Если сервер вернул маску карты
                            if (!profile.cardMask.isNullOrEmpty()) {
                                stopPolling()

                                dialog.dismiss()

                                sessionManager.saveCardMask(profile.cardMask)
                                sessionManager.savePaymentMethod("CARD")
                                updateUI()
                                return // Выходим из функции, чтобы таймер точно не запустился снова
                            }
                        }

                        // ИСПРАВЛЕНИЕ: Перезапускаем таймер через переменную pollingRunnable
                        if (isPolling) {
                            pollingRunnable?.let { pollingHandler.postDelayed(it, 2000) }
                        }
                    }

                    override fun onFailure(call: Call<ClientProfileResponse>, t: Throwable) {
                        // ИСПРАВЛЕНИЕ: Перезапускаем таймер при ошибке сети
                        if (isPolling) {
                            pollingRunnable?.let { pollingHandler.postDelayed(it, 2000) }
                        }
                    }
                })
            }
        }

        // Запускаем первую проверку
        pollingRunnable?.let { pollingHandler.postDelayed(it, 2000) }
    }

    private fun stopPolling() {
        isPolling = false
        pollingRunnable?.let { pollingHandler.removeCallbacks(it) }
    }
}