package com.taxiapp.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
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
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.firebase.messaging.FirebaseMessaging
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.GoogleAuthRequestDto
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.network.dto.LoginResponseDto
import com.taxiapp.client.network.dto.SmsRequestDto
import com.taxiapp.client.network.dto.SmsVerifyDto
import com.taxiapp.client.utils.AppSignatureHelper
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager

    private var marketingSource: String? = null
    private var lastGoogleClickTime: Long = 0
    private lateinit var tvPrivacyDisclaimer: TextView

    private lateinit var layoutPhone: LinearLayout
    private lateinit var layoutSms: LinearLayout
    private lateinit var layoutLinkPhone: LinearLayout
    private lateinit var progressBar: ProgressBar

    private lateinit var etPhoneNumber: EditText
    private lateinit var btnGetCode: Button
    private lateinit var btnVerify: Button
    private lateinit var btnGoogleSignIn: Button

    private lateinit var etLinkPhoneNumber: EditText
    private lateinit var btnLinkPhone: Button

    private lateinit var tvSmsSubtitle: TextView
    private lateinit var tvResendCode: TextView
    private lateinit var otpEdits: List<EditText>

    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    private var fullPhoneNumber: String = ""
    private var resendTimer: CountDownTimer? = null
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
                if (e.statusCode == 12501) {
                    Log.d("GoogleAuth", "Вход отменен пользователем (12501)")
                } else {
                    Log.e("GoogleAuth", "Помилка входу Google. Код: ${e.statusCode}", e)
                    showToast("Помилка Google: ${e.statusCode}")
                }
            }
        } else {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                task.getResult(ApiException::class.java)
            } catch (e: ApiException) {
                if (e.statusCode == 12501) {
                    Log.d("GoogleAuth", "Пользователь закрыл шторку выбора аккаунта Google (12501)")
                } else {
                    Log.e("GoogleAuth", "Авторизація скасована або помилка. Код: ${e.statusCode}")
                    showToast("Помилка: ${e.statusCode}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager(applicationContext)
        marketingSource = intent.getStringExtra("EXTRA_ACQUISITION_SOURCE")

        val token = sessionManager.fetchAuthToken()
        val phone = sessionManager.getUserPhone()
        if (token != null && phone.isNotEmpty()) {
            updateFcmTokenOnServer()
            goToHomeActivity()
            return
        } else if (token != null) {
            if (savedInstanceState == null) {
                sessionManager.clearSession()
            }
        }
        setContentView(R.layout.activity_main)

        if (token != null && phone.isEmpty() && savedInstanceState != null) {
            showLinkPhoneScreen()
        }
        try { ViewUtils.setupEdgeToEdge(this) } catch (e: Exception) { e.printStackTrace() }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        if (BuildConfig.DEBUG) {
            val appSignatureHelper = AppSignatureHelper(this)
            for (signature in appSignatureHelper.appSignatures) {
                Log.d("MY_APP_HASH", "ТВОЙ ХЭШ ДЛЯ СМС: $signature")
            }
        }

        // Безпечна реєстрація SMS Retriever з системним дозволом SmsRetriever.SEND_PERMISSION
        val smsFilter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                smsRetrieverReceiver,
                smsFilter,
                SmsRetriever.SEND_PERMISSION,
                null,
                RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                smsRetrieverReceiver,
                smsFilter,
                SmsRetriever.SEND_PERMISSION,
                null
            )
        }

        initUI()

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
            if (System.currentTimeMillis() - lastGoogleClickTime < 1500) return@setOnClickListener
            lastGoogleClickTime = System.currentTimeMillis()

            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            }
        }

        tvResendCode.setOnClickListener { requestSms(fullPhoneNumber) }

        setupOtpInputs()
    }

    private fun saveLoginSessionData(body: LoginResponseDto) {
        sessionManager.saveAuthToken(body.token)
        sessionManager.saveUserId(body.userId)
        if (!body.refreshToken.isNullOrEmpty()) {
            sessionManager.saveRefreshToken(body.refreshToken)
        }
        if (!body.cardMask.isNullOrEmpty()) {
            sessionManager.saveCardMask(body.cardMask)
        }
        if (!body.phoneNumber.isNullOrBlank()) {
            sessionManager.saveUserInfo(body.fullName, body.phoneNumber)
        }
    }

    private fun verifyGoogleToken(idToken: String) {
        setLoading(true)
        val request = GoogleAuthRequestDto(idToken, marketingSource)

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.loginWithGoogle(request)
                if (isFinishing || isDestroyed) return@launch
                setLoading(false)
                if (response.isSuccessful) {
                    val body = response.body()
                    val token = body?.token
                    val phone = body?.phoneNumber

                    if (body != null && token != null) {
                        saveLoginSessionData(body)

                        if (body.isNewUser) {
                            AnalyticsManager.trackUserRegistered(
                                userId = body.userId,
                                source = sessionManager.getUtmSource() ?: marketingSource ?: "google_auth"
                            )
                        }

                        if (body.isNewUser || phone.isNullOrBlank()) {
                            showLinkPhoneScreen()
                        } else {
                            updateFcmTokenOnServer()
                            checkWhereToGo(false)
                        }
                    } else {
                        showToast("Помилка сервера при Google авторизації")
                    }
                } else {
                    showToast("Помилка авторизації Google на сервері")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                if (isFinishing || isDestroyed) return@launch
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
        }
    }

    private fun verifySms(phone: String, code: String) {
        setLoading(true)
        val request = SmsVerifyDto(phoneNumber = phone, code = code, acquisitionSource = marketingSource)

        lifecycleScope.launch {
            try {
                if (isLinkingPhoneState) {
                    val response = ApiClient.instance.linkPhone(request)
                    if (isFinishing || isDestroyed) return@launch
                    setLoading(false)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body != null) {
                            saveLoginSessionData(body)
                            resendTimer?.cancel()
                            updateFcmTokenOnServer()
                            goToHomeActivity()
                        }
                    } else {
                        handleSmsError(response.code())
                    }
                } else {
                    val response = ApiClient.instance.verifySmsCode(request)
                    if (isFinishing || isDestroyed) return@launch
                    setLoading(false)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body != null) {
                            saveLoginSessionData(body)
                            if (body.isNewUser) {
                                AnalyticsManager.trackUserRegistered(
                                    userId = body.userId,
                                    source = sessionManager.getUtmSource() ?: marketingSource ?: "sms_auth"
                                )
                            }
                            resendTimer?.cancel()
                            updateFcmTokenOnServer()
                            checkWhereToGo(body.isNewUser)
                        }
                    } else {
                        handleSmsError(response.code())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                if (isFinishing || isDestroyed) return@launch
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
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

    private fun initUI() {
        layoutPhone = findViewById(R.id.layout_phone_input)
        layoutSms = findViewById(R.id.layout_sms_verify)
        layoutLinkPhone = findViewById(R.id.layout_link_phone)
        progressBar = findViewById(R.id.progress_bar)
        etPhoneNumber = findViewById(R.id.et_phone_number)
        etLinkPhoneNumber = findViewById(R.id.et_link_phone_number)
        btnGetCode = findViewById(R.id.btn_get_code)
        btnLinkPhone = findViewById(R.id.btn_link_phone)
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
        setupPhoneAutoformat(etPhoneNumber)
        setupPhoneAutoformat(etLinkPhoneNumber)

        tvPrivacyDisclaimer = findViewById(R.id.tv_privacy_disclaimer)
        tvPrivacyDisclaimer.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        toastHandler.removeCallbacks(hideToastRunnable)
        try {
            unregisterReceiver(smsRetrieverReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startResendTimer() {
        resendTimer?.cancel()
        tvResendCode.isClickable = false
        tvResendCode.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        resendTimer = object : CountDownTimer(60000, 1000) {
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
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.requestSmsCode(request)
                if (isFinishing || isDestroyed) return@launch
                setLoading(false)
                if (response.isSuccessful) {
                    showSmsScreen(phone)
                    showToast("Код надіслано!")
                    startResendTimer()
                    startSmsRetriever()
                } else {
                    if (response.code() == 403) showToast("Доступ заборонено (Блок/Бан)")
                    else showToast("Помилка: ${response.message()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                if (isFinishing || isDestroyed) return@launch
                setLoading(false)
                showToast("Помилка мережі: ${t.message}")
            }
        }
    }

    private val smsRetrieverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (SmsRetriever.SMS_RETRIEVED_ACTION == intent?.action) {
                val extras = intent.extras
                @Suppress("DEPRECATION")
                val status = extras?.get(SmsRetriever.EXTRA_STATUS) as? Status
                when (status?.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)
                        if (!message.isNullOrEmpty()) {
                            parseAndSubmitSmsCode(message)
                        }
                    }
                    CommonStatusCodes.TIMEOUT -> {
                        Log.d("SmsRetriever", "Таймаут ожидания СМС (5 минут)")
                    }
                }
            }
        }
    }

    private fun startSmsRetriever() {
        try {
            val client = SmsRetriever.getClient(this)
            val task = client.startSmsRetriever()
            task.addOnSuccessListener {
                Log.d("SmsRetriever", "SMS Retriever успешно запущен")
            }
            task.addOnFailureListener { e ->
                Log.e("SmsRetriever", "Ошибка запуска SMS Retriever", e)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseAndSubmitSmsCode(message: String) {
        val regex = Regex("\\b\\d{6}\\b")
        val matchResult = regex.find(message)
        val code = matchResult?.value

        if (!code.isNullOrEmpty() && code.length == 6) {
            code.forEachIndexed { index, char ->
                if (index < otpEdits.size) {
                    otpEdits[index].setText(char.toString())
                }
            }
            showToast("Код автопідставлено!")
            verifySms(fullPhoneNumber, code)
        }
    }

    private fun updateFcmTokenOnServer() {
        sessionManager.fetchAuthToken() ?: return
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener
            val fcmToken = task.result
            val body = mapOf("token" to fcmToken)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    ApiClient.instance.updateFcmToken(body)
                } catch (_: Exception) {}
            }
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

    private fun setupPhoneAutoformat(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            var isUpdating = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isUpdating || s == null) return
                isUpdating = true

                var text = s.toString().replace(Regex("[^0-9]"), "")

                if (text.startsWith("380")) {
                    text = text.removePrefix("380")
                } else if (text.startsWith("80")) {
                    text = text.removePrefix("80")
                } else if (text.startsWith("0")) {
                    text = text.removePrefix("0")
                }

                if (text.length > 9) {
                    text = text.substring(0, 9)
                }

                if (s.toString() != text) {
                    s.replace(0, s.length, text)
                }

                isUpdating = false
            }
        })
    }

    private fun showToast(msg: String) {
        val isError = msg.contains("Помилка", true) ||
                msg.contains("Error", true) ||
                msg.contains("заборонено") ||
                msg.contains("Невірний") ||
                msg.contains("зайнятий")
        showTopMessage(msg, isError)
    }
}