package com.taxiapp.client

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.taxiapp.client.utils.ViewUtils

class HelpActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        findViewById<MaterialCardView>(R.id.btn_support_action).setOnClickListener {
            showSupportBottomSheet()
        }



        findViewById<LinearLayout>(R.id.btn_payment)?.setOnClickListener {
            startActivity(Intent(this, HelpPaymentActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.btn_waiting)?.setOnClickListener {
            startActivity(Intent(this, HelpWaitingActivity::class.java))
        }
    }

    private fun showSupportBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_support, null)

        view.findViewById<LinearLayout>(R.id.bs_btn_telegram).setOnClickListener {
            dialog.dismiss()
            openTelegram()
        }
        view.findViewById<LinearLayout>(R.id.bs_btn_call).setOnClickListener {
            dialog.dismiss()
            makeCall()
        }

        dialog.setContentView(view)

        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        dialog.window?.let { window ->
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        dialog.show()
    }

    private fun openTelegram() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/unit_taxi_support_bot"))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun makeCall() {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+380633101265"))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}