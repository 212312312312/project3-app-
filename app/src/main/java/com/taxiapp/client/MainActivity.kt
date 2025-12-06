package com.taxiapp.client

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.network.dto.LoginResponseDto
import com.taxiapp.client.network.dto.SmsRequestDto
import com.taxiapp.client.network.dto.SmsVerifyDto
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    
    // UI Секции
    private lateinit var layoutPhone: LinearLayout
    private lateinit var layoutSms: LinearLayout
    private lateinit var progressBar: ProgressBar
    
    // UI Элементы
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnGetCode: Button
    private lateinit var btnVerify: Button
    private lateinit var tvSmsSubtitle: TextView
    private lateinit var tvResendCode: TextView
    
    // Ячейки кода
    private lateinit var otpEdits: List<EditText>

    // --- ЗМІННІ ДЛЯ КАСТОМНОГО ПОВІДОМЛЕННЯ ---
    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    private var fullPhoneNumber: String = ""
    private var resendTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }
        
        sessionManager = SessionManager(applicationContext)
        if (sessionManager.fetchAuthToken() != null) {
            goToHomeActivity()
            return
        }

        setContentView(R.layout.activity_main)

        // Инициализация View
        initUI()

        // 1. Кнопка "Продолжить"
        btnGetCode.setOnClickListener {
            val rawNumber = etPhoneNumber.text.toString()
            if (rawNumber.length != 9) {
                showToast("Номер має містити 9 цифр")
                return@setOnClickListener
            }
            fullPhoneNumber = "0$rawNumber" 
            requestSms(fullPhoneNumber)
        }
        
        // 2. Кнопка "Войти" (SMS)
        btnVerify.setOnClickListener {
            val code = otpEdits.joinToString("") { it.text.toString() }
            if (code.length != 6) {
                showToast("Введіть всі 6 цифр коду")
                return@setOnClickListener
            }
            verifySms(fullPhoneNumber, code)
        }
        
        // 3. Повторная отправка
        tvResendCode.setOnClickListener {
            requestSms(fullPhoneNumber) 
        }
        
        setupOtpInputs()
    }
    
    private fun initUI() {
        layoutPhone = findViewById(R.id.layout_phone_input)
        layoutSms = findViewById(R.id.layout_sms_verify)
        progressBar = findViewById(R.id.progress_bar)
        
        etPhoneNumber = findViewById(R.id.et_phone_number)
        btnGetCode = findViewById(R.id.btn_get_code)
        
        btnVerify = findViewById(R.id.btn_verify)
        tvSmsSubtitle = findViewById(R.id.tv_sms_subtitle)
        tvResendCode = findViewById(R.id.tv_resend_code)

        otpEdits = listOf(
            findViewById(R.id.otp_1), findViewById(R.id.otp_2), findViewById(R.id.otp_3),
            findViewById(R.id.otp_4), findViewById(R.id.otp_5), findViewById(R.id.otp_6)
        )

        // --- ІНІЦІАЛІЗАЦІЯ ПОВІДОМЛЕННЯ ---
        customToastContainer = findViewById(R.id.custom_toast_container)
        tvToastMessage = findViewById(R.id.tv_toast_message)
        ivToastIcon = findViewById(R.id.iv_toast_icon)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        resendTimer?.cancel()
    }
    
    private fun startResendTimer() {
        resendTimer?.cancel() 
        tvResendCode.isClickable = false
        tvResendCode.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        
        resendTimer = object : CountDownTimer(300000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                tvResendCode.text = "Відправити повторно через $seconds"
            }

            override fun onFinish() {
                tvResendCode.text = "Відправити повторно"
                tvResendCode.isClickable = true
                tvResendCode.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.taxi_yellow))
            }
        }.start()
    }

    private fun setupOtpInputs() {
        otpEdits.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1) {
                        val bounceAnim = AnimationUtils.loadAnimation(this@MainActivity, R.anim.bounce)
                        editText.startAnimation(bounceAnim)
                        if (index < otpEdits.size - 1) otpEdits[index + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && index > 0) {
                        otpEdits[index - 1].requestFocus()
                        otpEdits[index - 1].text.clear()
                        return@setOnKeyListener true 
                    }
                }
                false
            }
        }
    }

    private fun requestSms(phone: String) {
        setLoading(true)
        val request = SmsRequestDto(phoneNumber = phone)
        
        ApiClient.instance.requestSmsCode(request).enqueue(object : Callback<MessageResponse> {
            override fun onResponse(call: Call<MessageResponse>, response: Response<MessageResponse>) {
                setLoading(false)
                if (response.isSuccessful) {
                    showSmsScreen(phone)
                    showToast("Код надіслано!")
                    startResendTimer() 
                } else {
                    if (response.code() == 403) {
                         showToast("Доступ заборонено (Блок/Бан)")
                    } else {
                         showToast("Помилка: ${response.message()}") 
                    }
                }
            }
            override fun onFailure(call: Call<MessageResponse>, t: Throwable) {
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
        })
    }

    private fun verifySms(phone: String, code: String) {
        setLoading(true)
        val request = SmsVerifyDto(phoneNumber = phone, code = code)
        
        ApiClient.instance.verifySmsCode(request).enqueue(object : Callback<LoginResponseDto> {
            override fun onResponse(call: Call<LoginResponseDto>, response: Response<LoginResponseDto>) {
                setLoading(false)
                if (response.isSuccessful) {
                    val body = response.body()
                    val token = body?.token
                    if (token != null) {
                        sessionManager.saveAuthToken(token)
                        sessionManager.saveUserInfo(body.fullName, body.phoneNumber)
                        resendTimer?.cancel()
                        
                        if (body.isNewUser) {
                            goToAgreementActivity()
                        } else {
                            goToHomeActivity()
                        }
                    } else {
                        showToast("Помилка сервера")
                    }
                } else {
                    showToast("Невірний код")
                    otpEdits.forEach { it.text.clear() }
                    otpEdits[0].requestFocus()
                }
            }
            override fun onFailure(call: Call<LoginResponseDto>, t: Throwable) {
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
        })
    }

    private fun showSmsScreen(phone: String) {
        layoutPhone.visibility = View.GONE
        layoutSms.visibility = View.VISIBLE
        tvSmsSubtitle.text = "Код надіслано на +38$phone"
        otpEdits[0].requestFocus() 
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            progressBar.visibility = View.VISIBLE
            btnGetCode.isEnabled = false
            btnVerify.isEnabled = false
        } else {
            progressBar.visibility = View.GONE
            btnGetCode.isEnabled = true
            btnVerify.isEnabled = true
        }
    }

    private fun goToHomeActivity() {
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish() 
    }
    
    private fun goToAgreementActivity() {
        val intent = Intent(this, AgreementActivity::class.java)
        startActivity(intent)
        finish() 
    }

    // --- НОВА СТИЛЬНА ФУНКЦІЯ ПОВІДОМЛЕНЬ ---
    private fun showTopMessage(message: String, isError: Boolean = false) {
        toastHandler.removeCallbacks(hideToastRunnable)
        tvToastMessage.text = message
        
        if (isError) {
            ivToastIcon.setColorFilter(Color.parseColor("#FF5252"))
        } else {
            ivToastIcon.setColorFilter(Color.parseColor("#FFD600"))
        }

        customToastContainer.visibility = View.VISIBLE
        customToastContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        toastHandler.postDelayed(hideToastRunnable, 3500)
    }

    private fun hideTopMessage() {
        customToastContainer.animate()
            .translationY(-200f)
            .alpha(0f)
            .setDuration(300)
            .withEndAction { customToastContainer.visibility = View.INVISIBLE }
            .start()
    }

    private fun showToast(msg: String) {
        val isError = msg.contains("Помилка", true) || msg.contains("Error", true) || msg.contains("заборонено") || msg.contains("Невірний")
        showTopMessage(msg, isError)
    }
}