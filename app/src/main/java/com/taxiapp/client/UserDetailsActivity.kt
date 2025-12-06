package com.taxiapp.client

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog // Важливо!
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class UserDetailsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var etName: EditText
    private lateinit var tvPhone: TextView

    // Змінні для повідомлення
    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_user_details)

        sessionManager = SessionManager(applicationContext)

        // Ініціалізація UI
        etName = findViewById(R.id.et_user_name)
        tvPhone = findViewById(R.id.tv_user_phone)

        // Ініціалізація тоста
        try {
            customToastContainer = findViewById(R.id.custom_toast_container)
            tvToastMessage = findViewById(R.id.tv_toast_message)
            ivToastIcon = findViewById(R.id.iv_toast_icon)
        } catch (e: Exception) {}

        // Дані
        etName.setText(sessionManager.getUserName())
        val phone = sessionManager.getUserPhone()
        tvPhone.text = if (phone.isNotEmpty()) "+38$phone" else "+380 XX XXX XX XX"

        // --- КНОПКИ ---

        // 1. Назад
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { saveAndExit() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { saveAndExit() }
        })

        // 2. Вихід
        findViewById<View>(R.id.btn_logout).setOnClickListener { logoutAndExit() }

        // 3. Видалення (Кнопка, на яку ви натискаєте)
        val btnDelete = findViewById<View>(R.id.btn_delete_account)
        btnDelete.setOnClickListener {
            showCustomDeleteDialog()
        }
    }

    // --- ФУНКЦІЯ ВІДОБРАЖЕННЯ ДІАЛОГУ ---
    private fun showCustomDeleteDialog() {
        // 1. Загружаем наш XML с кнопками
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_account, null)

        // 2. Создаем диалог
        val dialog = AlertDialog.Builder(this, R.style.DeleteAccountDialog)
            .setView(dialogView)
            .setCancelable(true) // Можно закрыть, нажав мимо
            .create()

        // 3. Находим кнопки ВНУТРИ нашего макета
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_dialog_cancel)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_dialog_delete)

        // 4. Настраиваем клики
        btnCancel.setOnClickListener {
            dialog.dismiss() // Просто закрыть
        }

        btnDelete.setOnClickListener {
            dialog.dismiss() // Закрыть окно
            deleteAccountOnServer() // Выполнить удаление
        }

        dialog.show()
    }

    private fun saveAndExit() {
        val newName = etName.text.toString().trim()
        if (newName.isNotEmpty()) {
            val currentName = sessionManager.getUserName()
            if (newName != currentName) {
                sessionManager.saveUserInfo(newName, sessionManager.getUserPhone())
            }
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun deleteAccountOnServer() {
        val token = sessionManager.fetchAuthToken() ?: return

        ApiClient.instance.deleteAccount("Bearer $token").enqueue(object : Callback<MessageResponse> {
            override fun onResponse(call: Call<MessageResponse>, response: Response<MessageResponse>) {
                if (response.isSuccessful) {
                    showTopMessage("Акаунт успішно видалено", false)
                    sessionManager.clearSession()
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = Intent(this@UserDetailsActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }, 1500)
                } else {
                    showTopMessage("Помилка: ${response.code()}", true)
                }
            }
            override fun onFailure(call: Call<MessageResponse>, t: Throwable) {
                showTopMessage("Помилка мережі", true)
            }
        })
    }

    private fun logoutAndExit() {
        sessionManager.clearSession()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showTopMessage(message: String, isError: Boolean = false) {
        if (!::customToastContainer.isInitialized) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        toastHandler.removeCallbacks(hideToastRunnable)
        tvToastMessage.text = message
        if (isError) ivToastIcon.setColorFilter(Color.parseColor("#FF5252"))
        else ivToastIcon.setColorFilter(Color.parseColor("#FFD600"))

        customToastContainer.visibility = View.VISIBLE
        customToastContainer.animate()
            .translationY(0f).alpha(1f)
            .setDuration(400).setInterpolator(AccelerateDecelerateInterpolator()).start()
        toastHandler.postDelayed(hideToastRunnable, 3500)
    }

    private fun hideTopMessage() {
        if (!::customToastContainer.isInitialized) return
        customToastContainer.animate()
            .translationY(-200f).alpha(0f)
            .setDuration(300).withEndAction { customToastContainer.visibility = View.INVISIBLE }.start()
    }
}