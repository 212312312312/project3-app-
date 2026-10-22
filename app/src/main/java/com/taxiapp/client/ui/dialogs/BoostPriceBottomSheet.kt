package com.taxiapp.client.ui.dialogs

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.taxiapp.client.R

class BoostPriceBottomSheet(
    private val onApplyBoost: () -> Unit
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_double_price, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            window.navigationBarColor = ContextCompat.getColor(
                requireContext(),
                if (isNightMode) android.R.color.black else android.R.color.white
            )
        }

        val btnReject = view.findViewById<View>(R.id.btn_reject_boost)
        val btnApply = view.findViewById<View>(R.id.btn_apply_boost)

        btnReject.setOnClickListener {
            dismissAllowingStateLoss()
        }

        btnApply.setOnClickListener {
            onApplyBoost()
            dismissAllowingStateLoss()
        }
    }
}