package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.utils.ViewUtils
import com.taxiapp.client.utils.SessionManager

class PaymentActivity : BaseActivity()  {

    private lateinit var sessionManager: SessionManager
    private lateinit var ivCheckCash: ImageView
    private lateinit var ivCheckCard: ImageView

    companion object {
        const val METHOD_CASH = "CASH"
        const val METHOD_CARD = "CARD"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        sessionManager = SessionManager(applicationContext)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        // Кнопка назад просто закриває екран
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        ivCheckCash = findViewById(R.id.iv_check_cash)
        ivCheckCard = findViewById(R.id.iv_check_card)

        // Завантажуємо початковий стан
        val savedMethod = sessionManager.fetchPaymentMethod()
        val currentMethod = intent.getStringExtra("EXTRA_PAYMENT_METHOD") ?: savedMethod

        updateChecks(currentMethod)
        // Встановлюємо початковий результат (на випадок, якщо користувач нічого не змінить і вийде)
        setResultData(currentMethod)

        // Обробка кліку "Готівка"
        findViewById<View>(R.id.btn_cash).setOnClickListener {
            handleSelection(METHOD_CASH)
        }

        // Обробка кліку "Водію на картку"
        findViewById<View>(R.id.btn_card).setOnClickListener {
            handleSelection(METHOD_CARD)
        }
    }

    // Основна функція обробки вибору
    private fun handleSelection(method: String) {
        // 1. Зберігаємо в пам'ять
        sessionManager.savePaymentMethod(method)

        // 2. Оновлюємо галочки візуально
        updateChecks(method)

        // 3. Оновлюємо результат для HomeActivity (але не закриваємо екран!)
        setResultData(method)
    }

    private fun setResultData(method: String) {
        val resultIntent = Intent()
        resultIntent.putExtra("EXTRA_PAYMENT_METHOD", method)
        setResult(Activity.RESULT_OK, resultIntent)
    }

    private fun updateChecks(method: String) {
        if (method == METHOD_CARD) {
            ivCheckCash.visibility = View.GONE
            ivCheckCard.visibility = View.VISIBLE
        } else {
            ivCheckCash.visibility = View.VISIBLE
            ivCheckCard.visibility = View.GONE
        }
    }
}