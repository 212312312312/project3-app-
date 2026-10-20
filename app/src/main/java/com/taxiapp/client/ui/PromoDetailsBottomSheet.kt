package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.gson.Gson
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.ClientPromoProgressDto
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PromoDetailsBottomSheet() : DialogFragment() {

    private var promoData: ClientPromoProgressDto? = null

    constructor(promo: ClientPromoProgressDto) : this() {
        this.promoData = promo
        arguments = Bundle().apply {
            putString(ARG_PROMO_JSON, Gson().toJson(promo))
        }
    }

    companion object {
        private const val ARG_PROMO_JSON = "arg_promo_json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (promoData == null) {
            val json = arguments?.getString(ARG_PROMO_JSON)
            if (!json.isNullOrEmpty()) {
                promoData = try {
                    Gson().fromJson(json, ClientPromoProgressDto::class.java)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return inflater.inflate(R.layout.bottom_sheet_promo_details, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val promo = promoData
        if (promo == null) {
            dismissAllowingStateLoss()
            return
        }

        val tvTitle = view.findViewById<TextView>(R.id.tv_sheet_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_sheet_desc)
        val tvPercent = view.findViewById<TextView>(R.id.tv_sheet_percent)
        val tvLimit = view.findViewById<TextView>(R.id.tv_sheet_limit)
        val tvExpires = view.findViewById<TextView>(R.id.tv_sheet_expires)
        val btnClose = view.findViewById<View>(R.id.btn_sheet_close)

        tvTitle.text = promo.title

        var desc = "Завдання виконано! Ви отримали знижку."
        if (promo.requiredTariffName != null) {
            desc += "\n(Тільки для тарифу ${promo.requiredTariffName})"
        }
        tvDesc.text = desc

        tvPercent.text = "${promo.discountPercent.toInt()}%"

        if (promo.maxDiscountAmount != null && promo.maxDiscountAmount > 0) {
            tvLimit.text = "${promo.maxDiscountAmount.toInt()} грн"
        } else {
            tvLimit.text = "∞"
        }

        if (!promo.rewardExpiresAt.isNullOrBlank()) {
            try {
                val date = LocalDateTime.parse(promo.rewardExpiresAt)
                val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                tvExpires.text = "Діє до: ${date.format(formatter)}"
            } catch (e: Exception) {
                tvExpires.text = "Діє до: ${promo.rewardExpiresAt.take(10)}"
            }
        } else {
            tvExpires.text = "Термін дії: Необмежений"
            tvExpires.setTextColor(Color.GRAY)
        }

        btnClose.setOnClickListener { dismissAllowingStateLoss() }
    }
}