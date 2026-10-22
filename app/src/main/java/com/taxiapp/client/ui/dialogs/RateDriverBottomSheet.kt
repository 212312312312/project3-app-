package com.taxiapp.client.ui.dialogs

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.taxiapp.client.R
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.RateDriverRequest
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class RateDriverBottomSheet(
    private val orderId: String,
    private val driverName: String?,
    private val onRatingFinished: () -> Unit,
    private val onCancelledByBack: () -> Unit,
    private val onMessage: (String) -> Unit
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.setCancelable(true)
        dialog?.setCanceledOnTouchOutside(false)

        val wrapper = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        inflater.inflate(R.layout.dialog_rate_driver, wrapper, true)

        val marginPx = (16 * resources.displayMetrics.density).toInt()
        (wrapper.getChildAt(0)?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            leftMargin = marginPx
            rightMargin = marginPx
            marginStart = marginPx
            marginEnd = marginPx
            bottomMargin = marginPx
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

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onCancelledByBack()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etComment = view.findViewById<EditText>(R.id.et_comment)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_rating)

        val stars = listOf<ImageView>(
            view.findViewById(R.id.star_1),
            view.findViewById(R.id.star_2),
            view.findViewById(R.id.star_3),
            view.findViewById(R.id.star_4),
            view.findViewById(R.id.star_5)
        )

        var currentRating = 0
        val activeStarColor = ContextCompat.getColor(requireContext(), R.color.taxi_yellow)
        val inactiveStarColor = Color.parseColor("#808080")

        stars.forEachIndexed { index, imageView ->
            imageView.setOnClickListener {
                currentRating = index + 1
                stars.forEachIndexed { i, star ->
                    star.setColorFilter(if (i < currentRating) activeStarColor else inactiveStarColor)
                }
            }
        }

        btnSubmit.setOnClickListener {
            if (currentRating == 0) {
                onMessage("Будь ласка, поставте оцінку")
                return@setOnClickListener
            }

            val token = SessionManager(requireContext()).fetchAuthToken()
            if (token == null) {
                onMessage("Помилка авторизації")
                dismissAllowingStateLoss()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = "Відправка..."

            val comment = etComment.text.toString()
            val request = RateDriverRequest(orderId, currentRating, comment)

            viewLifecycleOwner.lifecycleScope.launch {
                fun completeAndDismiss() {
                    onMessage("Дякуємо за відгук!")
                    onRatingFinished()
                    dismissAllowingStateLoss()
                }

                try {
                    val response = ApiClient.instance.rateDriver(request)
                    if (!isAdded) return@launch

                    if (response.isSuccessful) {
                        completeAndDismiss()
                    } else {
                        val code = response.code()
                        val errorBody = try { response.errorBody()?.string() ?: "" } catch (_: Exception) { "" }
                        if ((code == 500 || code == 400) &&
                            (errorBody.contains("вже оцінили") || errorBody.contains("RuntimeException"))
                        ) {
                            completeAndDismiss()
                            return@launch
                        }
                        onMessage("Помилка: $code")
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Відправити"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (!isAdded) return@launch
                    onMessage("Помилка мережі")
                    btnSubmit.isEnabled = true
                    btnSubmit.text = "Відправити"
                }
            }
        }
    }
}