package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.taxiapp.client.utils.ViewUtils

class AgreementActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_agreement)

        // Налаштування посилань
        val tvText = findViewById<TextView>(R.id.tv_agreement_text)
        tvText.movementMethod = LinkMovementMethod.getInstance()
        // Зробимо посилання блакитними (#007AFF), як ви просили
        tvText.setLinkTextColor(android.graphics.Color.parseColor("#007AFF"))

        // Кнопка "Прийняти"
        findViewById<Button>(R.id.btn_accept_agreement).setOnClickListener {
            // Переходимо на Головну
            val intent = Intent(this, HomeActivity::class.java)
            // Очищаємо історію, щоб не можна було повернутися назад на Угоду
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}