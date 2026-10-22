package com.taxiapp.client.ui.dialogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.CancellationReasonDto

class CancelOrderBottomSheet(
    private val reasons: List<CancellationReasonDto>,
    private val onConfirmCancel: (String?) -> Unit
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val wrapper = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        inflater.inflate(R.layout.dialog_cancel_reason, wrapper, true)

        val marginPx = (24 * resources.displayMetrics.density).toInt()
        (wrapper.getChildAt(0)?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            leftMargin = marginPx
            rightMargin = marginPx
            marginStart = marginPx
            marginEnd = marginPx
        }

        return wrapper
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rgReasons = view.findViewById<RadioGroup>(R.id.rg_cancel_reasons)
        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm_cancel)

        val ctx = requireContext()
        val yellowColor = ContextCompat.getColor(ctx, R.color.taxi_yellow)
        val colorStateList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(
                Color.GRAY,
                yellowColor
            )
        )

        for ((index, reason) in reasons.withIndex()) {
            val rb = RadioButton(ctx).apply {
                id = index
                text = reason.reasonText
                textSize = 16f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                buttonTintList = colorStateList
                layoutParams = RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 12, 0, 12)
                }
                setPadding(16, 0, 0, 0)
            }
            rgReasons.addView(rb)
        }

        if (reasons.isNotEmpty()) {
            rgReasons.check(0)
        }

        btnConfirm.setOnClickListener {
            val checkedId = rgReasons.checkedRadioButtonId
            val selectedReason = if (checkedId in reasons.indices) {
                reasons[checkedId].reasonText
            } else {
                null
            }
            onConfirmCancel(selectedReason)
            dismissAllowingStateLoss()
        }
    }
}