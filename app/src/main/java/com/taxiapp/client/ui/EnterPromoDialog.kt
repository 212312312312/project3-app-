package com.taxiapp.client.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.taxiapp.client.R
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.MessageResponse // Переконайся, що імпорт є
import com.taxiapp.client.network.dto.ApplyPromoRequestDto // Наш новий DTO
import com.taxiapp.client.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class EnterPromoDialog(
    private val onSuccess: () -> Unit
) : DialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return inflater.inflate(R.layout.dialog_enter_promo, container, false)
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

        val etCode = view.findViewById<EditText>(R.id.et_promo_code)
        val btnActivate = view.findViewById<MaterialButton>(R.id.btn_activate)

        btnActivate.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(context, "Введіть код", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendPromoCode(code)
        }
    }

    private fun sendPromoCode(code: String) {
        val context = context ?: return
        val sessionManager = SessionManager(context)
        val token = sessionManager.fetchAuthToken() ?: return

        // Створюємо DTO
        val request = ApplyPromoRequestDto(code)

        // Викликаємо правильний метод applyPromo
        ApiClient.instance.applyPromo(request).enqueue(object : Callback<MessageResponse> {
            override fun onResponse(call: Call<MessageResponse>, response: Response<MessageResponse>) {
                if (response.isSuccessful) {
                    val msg = response.body()?.message ?: "Промокод успішно активовано!"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    onSuccess() // Оновлюємо список або інтерфейс
                    dismiss()
                } else {
                    val errorMsg = when (response.code()) {
                        404 -> "Промокод не знайдено"
                        409 -> "Ви вже використали цей код"
                        400 -> "Термін дії коду вичерпано"
                        else -> "Помилка активації: ${response.code()}"
                    }
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<MessageResponse>, t: Throwable) {
                Toast.makeText(context, "Помилка мережі", Toast.LENGTH_SHORT).show()
            }
        })
    }
}