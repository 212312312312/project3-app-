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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.UpdateClientProfileRequestDto
import com.taxiapp.client.utils.LocaleHelper
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UserDetailsActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var etName: EditText
    private lateinit var tvPhone: TextView
    private lateinit var tvAvatarLetter: TextView
    private lateinit var tvUserRatingValue: TextView
    private lateinit var btnLanguage: ImageView

    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    companion object {
        private val profileSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_user_details)

        sessionManager = SessionManager(applicationContext)

        etName = findViewById(R.id.et_user_name)
        tvPhone = findViewById(R.id.tv_user_phone)
        tvAvatarLetter = findViewById(R.id.tv_avatar_letter_large)
        tvUserRatingValue = findViewById(R.id.tv_user_rating_value)
        btnLanguage = findViewById(R.id.btn_language)

        try {
            customToastContainer = findViewById(R.id.custom_toast_container)
            tvToastMessage = findViewById(R.id.tv_toast_message)
            ivToastIcon = findViewById(R.id.iv_toast_icon)
        } catch (e: Exception) {}

        val savedName = sessionManager.getUserName()
        etName.setText(savedName)
        updateAvatarLetter(savedName)

        val phone = sessionManager.getUserPhone()
        tvPhone.text = if (phone.isNotEmpty()) "+38$phone" else "+380 XX XXX XX XX"

        updateUserRating(sessionManager.getUserRating())
        refreshProfileFromServer()

        val currentLang = sessionManager.getLanguage()
        if (currentLang == "en") {
            btnLanguage.setImageResource(R.drawable.ic_flag_en)
        } else {
            btnLanguage.setImageResource(R.drawable.ic_flag_ua)
        }

        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateAvatarLetter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { saveAndExit() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { saveAndExit() }
        })

        findViewById<View>(R.id.btn_logout).setOnClickListener { logoutAndExit() }

        findViewById<View>(R.id.btn_delete_account).setOnClickListener {
            showCustomDeleteDialog()
        }

        btnLanguage.setOnClickListener {
            showLanguageDialog()
        }
    }

    private fun refreshProfileFromServer() {
        val token = sessionManager.fetchAuthToken()
        if (token.isNullOrEmpty()) return

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getClientProfile()
                if (isFinishing || isDestroyed) return@launch
                val profile = response.body() ?: return@launch
                if (response.isSuccessful) {
                    profile.rating?.let { rating ->
                        sessionManager.saveUserRating(rating)
                        updateUserRating(sessionManager.getUserRating())
                    }
                    if (profile.fullName.isNotBlank() && !etName.hasFocus()) {
                        val cleanPhone = if (profile.phoneNumber != "Не вказано") profile.phoneNumber else sessionManager.getUserPhone()
                        sessionManager.saveUserInfo(profile.fullName, cleanPhone)
                        etName.setText(profile.fullName)
                        updateAvatarLetter(profile.fullName)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun showLanguageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_language, null)

        val dialog = AlertDialog.Builder(this, R.style.DeleteAccountDialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_language)
        val rbLangUa = dialogView.findViewById<RadioButton>(R.id.rb_lang_ua)
        val rbLangEn = dialogView.findViewById<RadioButton>(R.id.rb_lang_en)

        val currentLang = sessionManager.getLanguage()
        if (currentLang == "en") {
            rbLangEn?.isChecked = true
        } else {
            rbLangUa?.isChecked = true
        }

        btnSave?.setOnClickListener {
            val selectedLang = if (rbLangEn?.isChecked == true) "en" else "uk"

            sessionManager.saveLanguage(selectedLang)
            LocaleHelper.applyLanguage(selectedLang)

            if (selectedLang == "en") {
                btnLanguage.setImageResource(R.drawable.ic_flag_en)
            } else {
                btnLanguage.setImageResource(R.drawable.ic_flag_ua)
            }

            showTopMessage("Мову змінено / Language changed", false)
            dialog.dismiss()
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
        if (newName.length >= 2) {
            val currentName = sessionManager.getUserName()
            if (newName != currentName) {
                sessionManager.saveUserInfo(newName, sessionManager.getUserPhone())

                val token = sessionManager.fetchAuthToken()
                if (!token.isNullOrEmpty()) {
                    val appSession = SessionManager(applicationContext)
                    profileSyncScope.launch {
                        try {
                            val response = ApiClient.instance.updateClientProfile(UpdateClientProfileRequestDto(newName))
                            val updated = response.body()
                            if (response.isSuccessful && updated != null) {
                                val cleanPhone = if (updated.phoneNumber != "Не вказано") {
                                    updated.phoneNumber
                                } else {
                                    appSession.getUserPhone()
                                }
                                appSession.saveUserInfo(updated.fullName, cleanPhone)
                                updated.rating?.let { appSession.saveUserRating(it) }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun deleteAccountOnServer() {
        sessionManager.fetchAuthToken() ?: return

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.deleteAccount()
                if (isFinishing || isDestroyed) return@launch
                if (response.isSuccessful) {
                    showTopMessage("Акаунт успішно видалено", false)
                    sessionManager.clearSession()
                    delay(1500L)
                    if (!isFinishing && !isDestroyed) {
                        val intent = Intent(this@UserDetailsActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                } else {
                    showTopMessage("Помилка: ${response.code()}", true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    showTopMessage("Помилка мережі", true)
                }
            }
        }
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