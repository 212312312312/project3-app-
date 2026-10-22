package com.taxiapp.client.ui.dialogs

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.taxiapp.client.R

/**
 * Единая нижняя шторка изменения цены / надбавки (как перед созданием заказа, так и для активного заказа в поиске).
 */
class ChangePriceBottomSheet(
    private val basePrice: Double,
    private val currentAddedValue: Double,
    private val onPriceSaved: (newAddedValue: Double) -> Unit
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_change_price, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT

            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (isNightMode) {
                window.navigationBarColor = ContextCompat.getColor(requireContext(), android.R.color.black)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    var flags = window.decorView.systemUiVisibility
                    @Suppress("DEPRECATION")
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    @Suppress("DEPRECATION")
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = flags
                }
            } else {
                window.navigationBarColor = ContextCompat.getColor(requireContext(), android.R.color.white)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    var flags = window.decorView.systemUiVisibility
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = flags
                }
            }
        }

        val tvPrice = view.findViewById<TextView>(R.id.tv_dialog_price)
        val btnMinus = view.findViewById<View>(R.id.btn_price_minus)
        val btnPlus = view.findViewById<View>(R.id.btn_price_plus)
        val seekBar = view.findViewById<SeekBar>(R.id.seekbar_price)
        val btnSave = view.findViewById<Button>(R.id.btn_save_price)
        val btnClose = view.findViewById<View>(R.id.btn_close_dialog)

        val minPrice = basePrice.toInt()
        val maxPrice = (basePrice * 3).toInt().coerceAtLeast(minPrice + 10)
        var currentPrice = (minPrice + currentAddedValue.toInt()).coerceIn(minPrice, maxPrice)

        seekBar.max = maxPrice - minPrice

        fun updateUI() {
            tvPrice.text = "$currentPrice ₴"
            val progress = currentPrice - minPrice
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                seekBar.setProgress(progress, true)
            } else {
                seekBar.progress = progress
            }
        }

        updateUI()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentPrice = minPrice + progress
                    tvPrice.text = "$currentPrice ₴"
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        btnPlus.setOnClickListener {
            currentPrice = (currentPrice + 10).coerceAtMost(maxPrice)
            updateUI()
        }

        btnMinus.setOnClickListener {
            currentPrice = (currentPrice - 10).coerceAtLeast(minPrice)
            updateUI()
        }

        btnSave.setOnClickListener {
            val newAddedValue = (currentPrice - minPrice).toDouble().coerceAtLeast(0.0)
            onPriceSaved(newAddedValue)
            dismissAllowingStateLoss()
        }

        btnClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }
}