package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod // Важливий імпорт для клікабельності посилань
import android.widget.Button
import android.widget.TextView // Імпорт для TextView
import com.taxiapp.client.utils.ViewUtils

class AgreementActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agreement)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        // 1. Знаходимо наш єдиний TextView з текстом та посиланнями
        val tvAgreementText = findViewById<TextView>(R.id.tv_agreement_text)

        // 2. Цей рядок активує клікабельність та підкреслення обох посилань у тексті
        tvAgreementText.movementMethod = LinkMovementMethod.getInstance()

        val btnAgree = findViewById<Button>(R.id.btn_accept_agreement)

        btnAgree.setOnClickListener {
            // Просто переходим на главный экран
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}