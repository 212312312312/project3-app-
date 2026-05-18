package com.taxiapp.client

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ChatMessageDto
import com.taxiapp.client.network.dto.SendMessageRequest
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ChatActivity : BaseActivity() {

    private var orderId: Long = -1L
    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnBack: ImageView

    private val chatAdapter = ChatAdapter(mutableListOf())
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // 1. ПОВНОЕКРАННИЙ РЕЖИМ (Immersive)
        try {
            ViewUtils.makeImmersive(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. ВИРІШЕННЯ БАГУ З КЛАВІАТУРОЮ
        // Знаходимо кореневий шар та динамічно змінюємо його відступ залежно від клавіатури
        val rootLayout = findViewById<View>(R.id.root_chat_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // Піднімаємо весь контент на висоту клавіатури
            // Зберігаємо верхній відступ 48dp (переводимо в пікселі приблизно або беремо з XML)
            val paddingTop = (48 * resources.displayMetrics.density).toInt()

            view.setPadding(view.paddingLeft, paddingTop, view.paddingRight, imeHeight)

            // Якщо клавіатура відкрилася — скролимо чат до останнього повідомлення
            if (imeHeight > 0) {
                scrollToBottom()
            }
            insets
        }

        orderId = intent.getLongExtra("ORDER_ID", -1L)
        if (orderId == -1L) {
            finish()
            return
        }

        // Авторизація
        val sessionManager = SessionManager(this)
        val rawToken = sessionManager.fetchAuthToken() ?: ""
        token = if (rawToken.startsWith("Bearer ")) rawToken else "Bearer $rawToken"

        initUI()
        loadMessageHistory()

        // Real-time оновлення
        lifecycleScope.launch {
            ChatEventBus.newMessages.collect {
                delay(800)
                loadMessageHistory(silent = true)
            }
        }
    }

    private fun initUI() {
        rvChat = findViewById(R.id.rv_chat)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)
        btnBack = findViewById(R.id.btn_back)

        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChat.adapter = chatAdapter

        btnBack.setOnClickListener { finish() }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }
    }

    private fun loadMessageHistory(silent: Boolean = false) {
        // Вызов очищен от token
        ApiClient.instance.getChatMessages(orderId).enqueue(object : Callback<List<ChatMessageDto>> {
            override fun onResponse(call: Call<List<ChatMessageDto>>, response: Response<List<ChatMessageDto>>) {
                if (response.isSuccessful) {
                    val messages = response.body() ?: emptyList()
                    // Предполагаю, что у тебя в адаптере есть метод вроде updateMessages или submitList
                    chatAdapter.updateMessages(messages)
                    if (!silent) scrollToBottom()
                } else {
                    if (!silent) Toast.makeText(this@ChatActivity, "Помилка завантаження чату", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<ChatMessageDto>>, t: Throwable) {
                if (!silent) Toast.makeText(this@ChatActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun sendMessage(text: String) {
        val request = SendMessageRequest(text)
        etMessage.text.clear()

        // Вызов очищен от token
        ApiClient.instance.sendChatMessage(orderId, request).enqueue(object : Callback<ChatMessageDto> {
            override fun onResponse(call: Call<ChatMessageDto>, response: Response<ChatMessageDto>) {
                if (response.isSuccessful) {
                    response.body()?.let {
                        chatAdapter.addMessage(it)
                        scrollToBottom()
                    }
                } else {
                    Toast.makeText(this@ChatActivity, "Помилка відправки", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<ChatMessageDto>, t: Throwable) {
                Toast.makeText(this@ChatActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        ChatEventBus.isChatScreenOpen = true
    }

    override fun onResume() {
        super.onResume()
        loadMessageHistory(silent = true)
    }

    override fun onStop() {
        super.onStop()
        ChatEventBus.isChatScreenOpen = false
    }

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) {
            rvChat.post {
                rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }
}