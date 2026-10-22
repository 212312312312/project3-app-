package com.taxiapp.client.ui.dialogs

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ReplacementSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.taxiapp.client.R
import com.taxiapp.client.network.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ChangePaymentBottomSheet(
    private val currentMethod: String,
    private val cardMask: String?,
    private val onPaymentMethodSelected: (String) -> Unit,
    private val onOpenLiqPayUrl: (String) -> Unit,
    private val onMessage: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_change_payment, container, false)
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

        val rbCash = view.findViewById<RadioButton>(R.id.rb_cash)
        val rbCard = view.findViewById<RadioButton>(R.id.rb_card)
        val rbDriverCard = view.findViewById<RadioButton>(R.id.rb_driver_card)
        val btnSave = view.findViewById<Button>(R.id.btn_save_payment)
        val btnClose = view.findViewById<ImageView>(R.id.btn_close_dialog)

        rbCard.visibility = View.GONE
        rbDriverCard?.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getPaymentMethodsSettings()
                if (!isAdded) return@launch
                if (response.isSuccessful && response.body() != null) {
                    val settings = response.body()!!
                    val isCardEnabled = settings["enable_card_payment"] ?: true
                    val isDriverCardEnabled = settings["enable_driver_card_payment"] ?: true

                    rbCard.visibility = if (isCardEnabled) View.VISIBLE else View.GONE
                    rbDriverCard?.visibility = if (isDriverCardEnabled) View.VISIBLE else View.GONE
                } else {
                    rbCard.visibility = View.VISIBLE
                    rbDriverCard?.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isAdded) {
                    rbCard.visibility = View.VISIBLE
                    rbDriverCard?.visibility = View.VISIBLE
                }
            }
        }

        if (!cardMask.isNullOrEmpty()) {
            val fullText = getString(R.string.payment_card_format, cardMask)
            val spannable = SpannableString(fullText)
            val startIndex = fullText.indexOf(cardMask)
            if (startIndex != -1) {
                val bgColor = ContextCompat.getColor(requireContext(), R.color.menu_background_color)
                val textColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
                spannable.setSpan(
                    RoundedBackgroundSpan(
                        backgroundColor = bgColor,
                        textColor = textColor,
                        cornerRadius = 12f,
                        horizontalPadding = 20f,
                        verticalPadding = 8f
                    ),
                    startIndex,
                    startIndex + cardMask.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            rbCard.text = spannable
        } else {
            rbCard.text = getString(R.string.payment_card)
        }

        when (currentMethod) {
            "CARD" -> rbCard.isChecked = true
            "DRIVER_CARD" -> rbDriverCard?.isChecked = true
            else -> rbCash.isChecked = true
        }

        btnSave.setOnClickListener {
            val selectedMethod = when {
                rbCard.isChecked -> "CARD"
                rbDriverCard?.isChecked == true -> "DRIVER_CARD"
                else -> "CASH"
            }

            if (selectedMethod == "CARD" && cardMask.isNullOrEmpty()) {
                btnSave.isEnabled = false
                btnSave.text = "Зачекайте..."

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val response = ApiClient.instance.initBindCard()
                        if (!isAdded) return@launch
                        dismissAllowingStateLoss()
                        if (response.isSuccessful && response.body() != null) {
                            onOpenLiqPayUrl(response.body()!!.paymentUrl)
                        } else {
                            onMessage("Помилка підключення карти")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (isAdded) {
                            dismissAllowingStateLoss()
                            onMessage("Помилка мережі")
                        }
                    }
                }
                return@setOnClickListener
            }

            onPaymentMethodSelected(selectedMethod)
            dismissAllowingStateLoss()
        }

        btnClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    class RoundedBackgroundSpan(
        private val backgroundColor: Int,
        private val textColor: Int,
        private val cornerRadius: Float,
        private val horizontalPadding: Float,
        private val verticalPadding: Float
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            return (paint.measureText(text, start, end) + 2 * horizontalPadding).roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            if (text == null) return
            val textWidth = paint.measureText(text, start, end)
            val metrics = paint.fontMetrics
            val rectTop = y + metrics.ascent - verticalPadding
            val rectBottom = y + metrics.descent + verticalPadding
            val rect = RectF(x, rectTop, x + textWidth + 2 * horizontalPadding, rectBottom)

            paint.color = backgroundColor
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            paint.color = textColor
            canvas.drawText(text, start, end, x + horizontalPadding, y.toFloat(), paint)
        }
    }
}