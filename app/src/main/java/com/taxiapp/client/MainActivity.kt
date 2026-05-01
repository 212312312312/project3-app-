package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.messaging.FirebaseMessaging
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.GoogleAuthRequestDto
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.network.dto.LoginResponseDto
import com.taxiapp.client.network.dto.SmsRequestDto
import com.taxiapp.client.network.dto.SmsVerifyDto
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager

    // UI Секции
    private lateinit var layoutPhone: LinearLayout
    private lateinit var layoutSms: LinearLayout
    private lateinit var layoutLinkPhone: LinearLayout // <-- НОВЫЙ ЭКРАН ДОЗАПОЛНЕНИЯ
    private lateinit var progressBar: ProgressBar

    // UI Элементы
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnGetCode: Button
    private lateinit var btnVerify: Button
    private lateinit var btnGoogleSignIn: Button

    // UI Элементы для дозаполнения
    private lateinit var etLinkPhoneNumber: EditText
    private lateinit var btnLinkPhone: Button

    private lateinit var tvSmsSubtitle: TextView
    private lateinit var tvResendCode: TextView
    private lateinit var otpEdits: List<EditText>

    private lateinit var googleSignInClient: GoogleSignInClient

    // Toast
    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    private var fullPhoneNumber: String = ""
    private var resendTimer: CountDownTimer? = null

    // Флаг, который показывает, что мы сейчас привязываем номер, а не просто логинимся
    private var isLinkingPhoneState = false

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    verifyGoogleToken(idToken)
                } else {
                    showToast("Помилка: Google не повернув токен")
                }
            } catch (e: ApiException) {
                // <--- ВОТ ТУТ МЫ ЛОВИМ ОШИБКУ --->
                Log.e("GoogleAuth", "Помилка входу Google. Код: ${e.statusCode}", e)
                showToast("Помилка Google: ${e.statusCode}")
            }
        } else {
            // <--- И ВОТ ТУТ МЫ ЛОВИМ ОШИБКУ --->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                task.getResult(ApiException::class.java)
            } catch (e: ApiException) {
                Log.e("GoogleAuth", "Авторизація скасована або помилка. Код: ${e.statusCode}")
                showToast("Помилка: ${e.statusCode}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager(applicationContext)
        ApiClient.sessionManager = sessionManager

        if (sessionManager.fetchAuthToken() != null) {
            updateFcmTokenOnServer()
            goToHomeActivity()
            return
        }

        setContentView(R.layout.activity_main)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        // НАСТРОЙКА GOOGLE SIGN IN
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("359347918144-au70gopljkd5gnfheu3kjbhg96qcbek0.apps.googleusercontent.com") // <--- ВАЖНО: ВЕРНИ СЮДА СВОЙ КЛЮЧ
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        initUI()

        // Кнопка стандартного логина
        btnGetCode.setOnClickListener {
            val rawNumber = etPhoneNumber.text.toString()
            if (rawNumber.length != 9) {
                showToast("Номер має містити 9 цифр")
                return@setOnClickListener
            }
            fullPhoneNumber = "0$rawNumber"
            isLinkingPhoneState = false
            requestSms(fullPhoneNumber)
        }

        // Кнопка привязки номера (после Google)
        btnLinkPhone.setOnClickListener {
            val rawNumber = etLinkPhoneNumber.text.toString()
            if (rawNumber.length != 9) {
                showToast("Номер має містити 9 цифр")
                return@setOnClickListener
            }
            fullPhoneNumber = "0$rawNumber"
            isLinkingPhoneState = true
            requestSms(fullPhoneNumber)
        }

        btnVerify.setOnClickListener {
            val code = otpEdits.joinToString("") { it.text.toString() }
            if (code.length != 6) {
                showToast("Введіть всі 6 цифр коду")
                return@setOnClickListener
            }
            verifySms(fullPhoneNumber, code)
        }

        btnGoogleSignIn.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }

        tvResendCode.setOnClickListener { requestSms(fullPhoneNumber) }

        setupOtpInputs()
    }

    private fun verifyGoogleToken(idToken: String) {
        setLoading(true)
        val request = GoogleAuthRequestDto(idToken)

        ApiClient.instance.loginWithGoogle(request).enqueue(object : Callback<LoginResponseDto> {
            override fun onResponse(call: Call<LoginResponseDto>, response: Response<LoginResponseDto>) {
                setLoading(false)
                if (response.isSuccessful) {
                    val body = response.body()
                    val token = body?.token
                    val phone = body?.phoneNumber

                    if (token != null) {
                        // Сохраняем токен сразу (он нужен для привязки номера)
                        sessionManager.saveAuthToken(token)

                        if (phone.isNullOrEmpty()) {
                            // НЕТ НОМЕРА -> Показываем новый экран привязки
                            showLinkPhoneScreen()
                        } else {
                            // НОМЕР ЕСТЬ -> Пускаем дальше
                            sessionManager.saveUserInfo(body.fullName, phone)
                            updateFcmTokenOnServer()
                            checkWhereToGo(body.isNewUser)
                        }
                    } else {
                        showToast("Помилка сервера при Google авторизації")
                    }
                } else {
                    showToast("Помилка авторизації Google на сервері")
                }
            }
            override fun onFailure(call: Call<LoginResponseDto>, t: Throwable) {
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
        })
    }

    private fun verifySms(phone: String, code: String) {
        setLoading(true)
        val request = SmsVerifyDto(phoneNumber = phone, code = code)

        if (isLinkingPhoneState) {
            // ЛОГИКА ПРИВЯЗКИ НОМЕРА
            val token = sessionManager.fetchAuthToken() ?: return
            ApiClient.instance.linkPhone("Bearer $token", request).enqueue(object : Callback<LoginResponseDto> {
                override fun onResponse(call: Call<LoginResponseDto>, response: Response<LoginResponseDto>) {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body != null) {
                            sessionManager.saveAuthToken(body.token)
                            sessionManager.saveUserInfo(body.fullName, body.phoneNumber)
                            resendTimer?.cancel()
                            updateFcmTokenOnServer()
                            goToHomeActivity() // Привязка всегда ведет на главный экран
                        }
                    } else {
                        handleSmsError(response.code())
                    }
                }
                override fun onFailure(call: Call<LoginResponseDto>, t: Throwable) {
                    setLoading(false)
                    showToast("Помилка мережі: ${t.message}")
                }
            })
        } else {
            // ОБЫЧНАЯ ЛОГИКА ЛОГИНА
            ApiClient.instance.verifySmsCode(request).enqueue(object : Callback<LoginResponseDto> {
                override fun onResponse(call: Call<LoginResponseDto>, response: Response<LoginResponseDto>) {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body != null) {
                            sessionManager.saveAuthToken(body.token)
                            sessionManager.saveUserInfo(body.fullName, body.phoneNumber)
                            resendTimer?.cancel()
                            updateFcmTokenOnServer()
                            checkWhereToGo(body.isNewUser)
                        }
                    } else {
                        handleSmsError(response.code())
                    }
                }
                override fun onFailure(call: Call<LoginResponseDto>, t: Throwable) {
                    setLoading(false)
                    showToast("Помилка мережі: ${t.message}")
                }
            })
        }
    }

    private fun handleSmsError(code: Int) {
        if (code == 409) showToast("Цей номер вже зайнятий")
        else showToast("Невірний код")
        otpEdits.forEach { it.text.clear() }
        otpEdits[0].requestFocus()
    }

    private fun showLinkPhoneScreen() {
        layoutPhone.visibility = View.GONE
        layoutSms.visibility = View.GONE
        layoutLinkPhone.visibility = View.VISIBLE
    }

    private fun showSmsScreen(phone: String) {
        layoutPhone.visibility = View.GONE
        layoutLinkPhone.visibility = View.GONE
        layoutSms.visibility = View.VISIBLE
        tvSmsSubtitle.text = "Код надіслано на +38$phone"
        otpEdits[0].requestFocus()
    }

    // ... остальной код (initUI, onDestroy, startResendTimer, setupOtpInputs, requestSms, updateFcmTokenOnServer, setLoading, goToHomeActivity, goToAgreementActivity, showTopMessage, hideTopMessage, showToast)

    private fun initUI() {
        layoutPhone = findViewById(R.id.layout_phone_input)
        layoutSms = findViewById(R.id.layout_sms_verify)
        layoutLinkPhone = findViewById(R.id.layout_link_phone) // Инициализация нового экрана
        progressBar = findViewById(R.id.progress_bar)
        etPhoneNumber = findViewById(R.id.et_phone_number)
        etLinkPhoneNumber = findViewById(R.id.et_link_phone_number) // Инициализация нового ввода
        btnGetCode = findViewById(R.id.btn_get_code)
        btnLinkPhone = findViewById(R.id.btn_link_phone) // Инициализация новой кнопки
        btnVerify = findViewById(R.id.btn_verify)
        btnGoogleSignIn = findViewById(R.id.btn_google_sign_in)
        tvSmsSubtitle = findViewById(R.id.tv_sms_subtitle)
        tvResendCode = findViewById(R.id.tv_resend_code)
        otpEdits = listOf(
            findViewById(R.id.otp_1), findViewById(R.id.otp_2), findViewById(R.id.otp_3),
            findViewById(R.id.otp_4), findViewById(R.id.otp_5), findViewById(R.id.otp_6)
        )
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
                    if (response.code() == 403) showToast("Доступ заборонено (Блок/Бан)")
                    else showToast("Помилка: ${response.message()}")
                }
            }
            override fun onFailure(call: Call<MessageResponse>, t: Throwable) {
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
        })
    }

    private fun updateFcmTokenOnServer() {
        val token = sessionManager.fetchAuthToken() ?: return
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener
            val fcmToken = task.result
            val body = mapOf("token" to fcmToken)
            ApiClient.instance.updateFcmToken("Bearer $token", body).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {}
                override fun onFailure(call: Call<Void>, t: Throwable) {}
            })
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            progressBar.visibility = View.VISIBLE
            btnGetCode.isEnabled = false
            btnLinkPhone.isEnabled = false
            btnVerify.isEnabled = false
            btnGoogleSignIn.isEnabled = false
        } else {
            progressBar.visibility = View.GONE
            btnGetCode.isEnabled = true
            btnLinkPhone.isEnabled = true
            btnVerify.isEnabled = true
            btnGoogleSignIn.isEnabled = true
        }
    }

    private fun checkWhereToGo(isNewUser: Boolean) {
        if (isNewUser) {
            goToAgreementActivity()
        } else {
            goToHomeActivity()
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

    private fun showTopMessage(message: String, isError: Boolean = false) {
        toastHandler.removeCallbacks(hideToastRunnable)
        tvToastMessage.text = message
        if (isError) ivToastIcon.setColorFilter(Color.parseColor("#FF5252"))
        else ivToastIcon.setColorFilter(Color.parseColor("#FFD600"))

        customToastContainer.visibility = View.VISIBLE
        customToastContainer.animate().translationY(0f).alpha(1f).setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        toastHandler.postDelayed(hideToastRunnable, 3500)
    }

    private fun hideTopMessage() {
        customToastContainer.animate().translationY(-200f).alpha(0f).setDuration(300)
            .withEndAction { customToastContainer.visibility = View.INVISIBLE }.start()
    }

    private fun showToast(msg: String) {
        val isError = msg.contains("Помилка", true) || msg.contains("Error", true) || msg.contains("заборонено") || msg.contains("Невірний") || msg.contains("зайнятий")
        showTopMessage(msg, isError)
    }
}