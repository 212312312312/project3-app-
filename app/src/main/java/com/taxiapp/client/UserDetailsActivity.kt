package com.taxiapp.client

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.utils.LocaleHelper
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class UserDetailsActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var etName: EditText
    private lateinit var tvPhone: TextView

    // Змінна для великої літери аватара
    private lateinit var tvAvatarLetter: TextView

    // Змінна для відображення рейтингу
    private lateinit var tvUserRatingValue: TextView

    // НОВЕ: Змінна для кнопки зміни мови
    private lateinit var btnLanguage: ImageView

    // Змінні для повідомлення (Toast)
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

        // --- ІНІЦІАЛІЗАЦІЯ UI ---
        etName = findViewById(R.id.et_user_name)
        tvPhone = findViewById(R.id.tv_user_phone)
        tvAvatarLetter = findViewById(R.id.tv_avatar_letter_large)
        tvUserRatingValue = findViewById(R.id.tv_user_rating_value)

        // НОВЕ: Ініціалізація кнопки мови
        btnLanguage = findViewById(R.id.btn_language)

        // Ініціалізація тоста
        try {
            customToastContainer = findViewById(R.id.custom_toast_container)
            tvToastMessage = findViewById(R.id.tv_toast_message)
            ivToastIcon = findViewById(R.id.iv_toast_icon)
        } catch (e: Exception) {}

        // --- ЗАПОВНЕННЯ ДАНИМИ ---
        val savedName = sessionManager.getUserName()
        etName.setText(savedName)
        updateAvatarLetter(savedName)

        val phone = sessionManager.getUserPhone()
        tvPhone.text = if (phone.isNotEmpty()) "+38$phone" else "+380 XX XXX XX XX"

        // Встановлюємо рейтинг (Поки заглушка, пізніше можна брати з API або SessionManager)
        updateUserRating("5.0")

        // НОВЕ: Встановлюємо іконку мови при запуску екрану
        val currentLang = sessionManager.getLanguage()
        if (currentLang == "en") {
            btnLanguage.setImageResource(R.drawable.ic_flag_en)
        } else {
            btnLanguage.setImageResource(R.drawable.ic_flag_ua) // Для всех остальных (uk) ставим UA флаг
        }

        // --- ЛОГІКА ДИНАМІЧНОЇ ЗМІНИ ЛІТЕРИ ---
        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateAvatarLetter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // --- КНОПКИ ---

        // 1. Назад
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { saveAndExit() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { saveAndExit() }
        })

        // 2. Вихід
        findViewById<View>(R.id.btn_logout).setOnClickListener { logoutAndExit() }

        // 3. Видалення
        findViewById<View>(R.id.btn_delete_account).setOnClickListener {
            showCustomDeleteDialog()
        }

        // НОВЕ: 4. Зміна мови
        btnLanguage.setOnClickListener {
            showLanguageDialog()
        }
    }

    // НОВЕ: Логіка діалогу вибору мови
    private fun showLanguageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_language, null)

        val dialog = AlertDialog.Builder(this, R.style.DeleteAccountDialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_language)
        val rbLangUa = dialogView.findViewById<RadioButton>(R.id.rb_lang_ua)
        val rbLangEn = dialogView.findViewById<RadioButton>(R.id.rb_lang_en)

        // 1. Читаємо поточну мову з SessionManager і ставимо потрібну галочку
        val currentLang = sessionManager.getLanguage()
        if (currentLang == "en") {
            rbLangEn?.isChecked = true
        } else {
            rbLangUa?.isChecked = true
        }

        btnSave?.setOnClickListener {
            // 1. ИСПРАВЛЕНО: используем системный код "uk" вместо "ua"
            val selectedLang = if (rbLangEn?.isChecked == true) "en" else "uk"

            // 2. Зберігаємо нову мову в сессию
            sessionManager.saveLanguage(selectedLang)

            // 🚀 ДОБАВЛЕНО: Принудительно применяем язык на уровне системы для Карт и внешних SDK
            LocaleHelper.applyLanguage(selectedLang)

            // 3. Оновлюємо іконку в хедері (имена твоих ресурсов drawable остаются прежними)
            if (selectedLang == "en") {
                btnLanguage.setImageResource(R.drawable.ic_flag_en)
            } else {
                btnLanguage.setImageResource(R.drawable.ic_flag_ua)
            }

            showTopMessage("Мову змінено / Language changed", false)
            dialog.dismiss()

            // 4. Перезапускаємо Activity, щоб весь UI обновился
            recreate()
        }

        dialog.show()
    }

    private fun updateUserRating(rating: String) {
        tvUserRatingValue.text = rating
    }

    private fun updateAvatarLetter(name: String) {
        val letter = if (name.isNotBlank()) {
            name.trim().first().toString().uppercase()
        } else {
            "U"
        }
        tvAvatarLetter.text = letter
    }

    private fun showCustomDeleteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_account, null)

        val dialog = AlertDialog.Builder(this, R.style.DeleteAccountDialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnCancel = dialogView.findViewById<Button>(R.id.btn_dialog_cancel)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_dialog_delete)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteAccountOnServer()
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

        ApiClient.instance.deleteAccount().enqueue(object : Callback<MessageResponse> {
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