package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment // <-- ЗМІНИЛИ СПАДКУВАННЯ
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PromoDetailsBottomSheet(
    private val promo: ClientPromoProgressDto
) : DialogFragment() { // <-- Тепер це DialogFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Прибираємо стандартний заголовок діалогу
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return inflater.inflate(R.layout.bottom_sheet_promo_details, container, false)
    }

    // Налаштовуємо розміри та прозорість фону, щоб вікно було по центру
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            // Ширина на весь екран (відступи задані в XML через margin/padding)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Прозорий фон, щоб було видно наші закруглені кути
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tv_sheet_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_sheet_desc)
        val tvPercent = view.findViewById<TextView>(R.id.tv_sheet_percent)
        val tvLimit = view.findViewById<TextView>(R.id.tv_sheet_limit)
        val tvExpires = view.findViewById<TextView>(R.id.tv_sheet_expires)
        val btnClose = view.findViewById<View>(R.id.btn_sheet_close) // View, бо це може бути Button або MaterialButton

        tvTitle.text = promo.title

        var desc = "Завдання виконано! Ви отримали знижку."
        if (promo.requiredTariffName != null) desc += "\n(Тільки для тарифу ${promo.requiredTariffName})"
        tvDesc.text = desc

        tvPercent.text = "${promo.discountPercent.toInt()}%"

        if (promo.maxDiscountAmount != null) {
            tvLimit.text = "${promo.maxDiscountAmount.toInt()} грн"
        } else {
            tvLimit.text = "∞"
        }

        if (promo.rewardExpiresAt != null) {
            try {
                val date = LocalDateTime.parse(promo.rewardExpiresAt)
                val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                tvExpires.text = "Діє до: ${date.format(formatter)}"
            } catch (e: Exception) {
                tvExpires.text = "Діє до: ${promo.rewardExpiresAt}"
            }
        } else {
            tvExpires.text = "Термін дії: Необмежений"
            tvExpires.setTextColor(Color.GRAY)
        }

        btnClose.setOnClickListener { dismiss() }
    }
}