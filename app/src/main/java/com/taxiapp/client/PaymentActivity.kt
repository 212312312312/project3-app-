package com.taxiapp.client

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.ClientProfileResponse
import com.taxiapp.client.network.InitBindCardResponse
import com.taxiapp.client.network.WebSocketManager
import com.taxiapp.client.network.dto.MessageResponseDto
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PaymentActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager
    private var webSocketManager: WebSocketManager? = null

    private lateinit var tvCardTitle: TextView
    private lateinit var ivUnbindCard: ImageView
    private lateinit var ivCheckCash: ImageView
    private lateinit var ivCheckCard: ImageView

    private var ivCheckDriverCard: ImageView? = null
    private var btnCard: RelativeLayout? = null
    private var btnDriverCard: RelativeLayout? = null

    private var activeWebViewDialog: Dialog? = null
    private var activeWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        sessionManager = SessionManager(this)
        webSocketManager = WebSocketManager(ApiClient.BASE_URL, this)

        tvCardTitle = findViewById(R.id.tv_card_title)
        ivUnbindCard = findViewById(R.id.iv_unbind_card)
        ivCheckCash = findViewById(R.id.iv_check_cash)
        ivCheckCard = findViewById(R.id.iv_check_card)

        ivCheckDriverCard = findViewById(R.id.iv_check_driver_card)
        btnCard = findViewById(R.id.btn_card)
        btnDriverCard = findViewById(R.id.btn_driver_card)

        btnCard?.visibility = View.GONE
        btnDriverCard?.visibility = View.GONE

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finishWithSelectedMethod() }

        findViewById<RelativeLayout>(R.id.btn_cash).setOnClickListener {
            selectPaymentMethod("CASH")
        }

        btnCard?.setOnClickListener {
            val mask = sessionManager.getCardMask()
            if (mask.isNullOrEmpty()) {
                bindNewCard()
            } else {
                selectPaymentMethod("CARD")
            }
        }

        btnDriverCard?.setOnClickListener {
            selectPaymentMethod("DRIVER_CARD")
        }

        ivUnbindCard.setOnClickListener {
            showUnbindCardDialog()
        }

        fetchPaymentSettings()
        fetchClientProfile()
        updateUI()
    }

    private fun selectPaymentMethod(method: String) {
        sessionManager.savePaymentMethod(method)
        syncResultIntent()
        updateUI()
    }

    private fun syncResultIntent() {
        val resultIntent = Intent().apply {
            putExtra("EXTRA_PAYMENT_METHOD", sessionManager.fetchPaymentMethod())
        }
        setResult(Activity.RESULT_OK, resultIntent)
    }

    private fun finishWithSelectedMethod() {
        syncResultIntent()
        finish()
    }

    private fun fetchPaymentSettings() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getPaymentMethodsSettings()
                if (isFinishing || isDestroyed) return@launch
                if (response.isSuccessful && response.body() != null) {
                    val settings = response.body()!!
                    val isCardEnabled = settings["enable_card_payment"] ?: true
                    val isDriverCardEnabled = settings["enable_driver_card_payment"] ?: true

                    btnCard?.visibility = if (isCardEnabled) View.VISIBLE else View.GONE
                    btnDriverCard?.visibility = if (isDriverCardEnabled) View.VISIBLE else View.GONE

                    val currentMethod = sessionManager.fetchPaymentMethod()
                    if ((currentMethod == "CARD" && !isCardEnabled) ||
                        (currentMethod == "DRIVER_CARD" && !isDriverCardEnabled)
                    ) {
                        sessionManager.savePaymentMethod("CASH")
                        syncResultIntent()
                    }

                    updateUI()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
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
                syncResultIntent()
            }
        }

        val currentMethod = sessionManager.fetchPaymentMethod()
        ivCheckCash.visibility = if (currentMethod == "CASH") View.VISIBLE else View.INVISIBLE
        ivCheckCard.visibility = if (currentMethod == "CARD" && !mask.isNullOrEmpty()) View.VISIBLE else View.INVISIBLE
        ivCheckDriverCard?.visibility = if (currentMethod == "DRIVER_CARD") View.VISIBLE else View.INVISIBLE
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

            lifecycleScope.launch {
                try {
                    val response = ApiClient.instance.unbindCard()
                    if (isFinishing || isDestroyed) return@launch
                    dialog.dismiss()
                    if (response.isSuccessful) {
                        sessionManager.saveCardMask(null)
                        selectPaymentMethod("CASH")
                    } else {
                        Toast.makeText(this@PaymentActivity, "Помилка при видаленні", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isFinishing || isDestroyed) return@launch
                    dialog.dismiss()
                    Toast.makeText(this@PaymentActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        cleanupWebViewDialog()
        webSocketManager?.unsubscribeFromCardBinding()
        webSocketManager?.destroy()
        webSocketManager = null
        super.onDestroy()
    }

    private fun fetchClientProfile() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getClientProfile()
                if (isFinishing || isDestroyed) return@launch
                if (response.isSuccessful && response.body() != null) {
                    val profile = response.body()!!
                    sessionManager.saveUserId(profile.id)
                    sessionManager.saveCardMask(profile.cardMask)

                    if (profile.cardMask.isNullOrEmpty() && sessionManager.fetchPaymentMethod() == "CARD") {
                        sessionManager.savePaymentMethod("CASH")
                    }
                    syncResultIntent()
                    updateUI()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun bindNewCard() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.initBindCard()
                if (isFinishing || isDestroyed) return@launch
                if (response.isSuccessful && response.body() != null) {
                    val url = response.body()!!.paymentUrl
                    showLiqPayWebView(url)
                } else {
                    Toast.makeText(this@PaymentActivity, getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                Toast.makeText(this@PaymentActivity, getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isSafePaymentRedirectUrl(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme != "https") return false

        // Дозволяємо LiqPay та 3D-Secure банківські шлюзи по HTTPS
        return host == "liqpay.ua" ||
                host.endsWith(".liqpay.ua") ||
                host.endsWith(".privatbank.ua") ||
                host.endsWith(".monobank.ua") ||
                (BuildConfig.DEBUG && host.endsWith(".ngrok-free.dev"))
    }

    private fun showLiqPayWebView(url: String) {
        if (!isSafePaymentRedirectUrl(url)) {
            Toast.makeText(this, "Недозволене посилання для оплати", Toast.LENGTH_SHORT).show()
            return
        }

        cleanupWebViewDialog()

        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_liqpay_webview)
        activeWebViewDialog = dialog

        val btnClose = dialog.findViewById<ImageView>(R.id.btn_close_webview)
        val webView = dialog.findViewById<WebView>(R.id.liqpay_webview)
        activeWebView = webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val overrideUrl = request?.url?.toString() ?: return false
                val uri = request.url
                if (uri.scheme?.lowercase() != "https") {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }
        webView.webChromeClient = WebChromeClient()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            webSocketManager?.unsubscribeFromCardBinding()
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {}
            if (activeWebView === webView) activeWebView = null
            if (activeWebViewDialog === dialog) activeWebViewDialog = null
            fetchClientProfile()
        }

        // Замість агресивного HTTP-поллінгу кожні 2 сек підписуємося на існуючий WebSocket-топік
        val clientId = sessionManager.fetchUserId()
        val token = sessionManager.fetchAuthToken()
        if (clientId != -1L && !token.isNullOrEmpty()) {
            webSocketManager?.connect(token)
            webSocketManager?.subscribeToCardBinding(clientId) { messageDto ->
                if (!isFinishing && !isDestroyed) {
                    if (messageDto.status == "SUCCESS" && !messageDto.cardMask.isNullOrEmpty()) {
                        sessionManager.saveCardMask(messageDto.cardMask)
                        selectPaymentMethod("CARD")
                        Toast.makeText(this@PaymentActivity, getString(R.string.card_bound_successfully), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else if (messageDto.status == "ERROR" || messageDto.status == "FAILED") {
                        Toast.makeText(this@PaymentActivity, messageDto.message ?: getString(R.string.card_bind_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        webView.loadUrl(url)
        dialog.show()
    }

    private fun cleanupWebViewDialog() {
        activeWebView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {}
        }
        activeWebView = null
        activeWebViewDialog?.let { dlg ->
            if (dlg.isShowing) {
                try { dlg.dismiss() } catch (_: Exception) {}
            }
        }
        activeWebViewDialog = null
    }
}