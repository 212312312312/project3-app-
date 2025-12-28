package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.utils.ViewUtils

class AgreementActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agreement)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

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