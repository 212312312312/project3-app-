package com.taxiapp.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ChatMessageDto
import com.taxiapp.client.network.dto.SendMessageRequest
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ChatActivity : AppCompatActivity() {

    private var orderId: Long = -1L
    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnBack: ImageButton

    private val chatAdapter = ChatAdapter(mutableListOf())
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        orderId = intent.getLongExtra("ORDER_ID", -1L)
        if (orderId == -1L) {
            finish()
            return
        }

        // --- УМНОЕ ПОЛУЧЕНИЕ ТОКЕНА ---
        val sessionManager = SessionManager(this)
        val rawToken = sessionManager.fetchAuthToken() ?: ""

        // Предотвращаем дублирование "Bearer Bearer"
        token = if (rawToken.startsWith("Bearer ")) {
            rawToken
        } else {
            "Bearer $rawToken"
        }

        Log.d("CHAT_DEBUG", "Форматований токен: $token")
        // ------------------------------

        initUI()
        loadMessageHistory()

        // --- СИСТЕМА REAL-TIME ЧЕРЕЗ EVENT BUS ---
        lifecycleScope.launch {
            ChatEventBus.newMessages.collect {
                // 🛑 РІШЕННЯ RACE CONDITION:
                // Даємо серверу 800 мілісекунд, щоб він встиг завершити транзакцію (commit)
                // в базі даних ПЕРЕД тим, як ми зробимо GET запит.
                kotlinx.coroutines.delay(800)
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
        ApiClient.instance.getChatMessages(token, orderId).enqueue(object : Callback<List<ChatMessageDto>> {
            override fun onResponse(call: Call<List<ChatMessageDto>>, response: Response<List<ChatMessageDto>>) {
                if (response.isSuccessful) {
                    response.body()?.let { newMessages ->
                        val isUpdated = chatAdapter.updateMessages(newMessages)
                        if (isUpdated) {
                            scrollToBottom()
                        }
                    }
                } else if (!silent) {
                    Log.e("CHAT_ERROR", "Помилка завантаження: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<List<ChatMessageDto>>, t: Throwable) {
                if (!silent) {
                    Toast.makeText(this@ChatActivity, "Помилка завантаження чату", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun sendMessage(text: String) {
        val request = SendMessageRequest(text)
        etMessage.text.clear()

        ApiClient.instance.sendChatMessage(token, orderId, request).enqueue(object : Callback<ChatMessageDto> {
            override fun onResponse(call: Call<ChatMessageDto>, response: Response<ChatMessageDto>) {
                if (response.isSuccessful) {
                    response.body()?.let {
                        chatAdapter.addMessage(it)
                        scrollToBottom()
                    }
                } else {
                    // Теперь мы увидим точный код ошибки, если она произойдет
                    Toast.makeText(this@ChatActivity, "Помилка відправки: ${response.code()}", Toast.LENGTH_SHORT).show()
                    Log.e("CHAT_ERROR", "Помилка відправки: ${response.code()} - ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<ChatMessageDto>, t: Throwable) {
                Toast.makeText(this@ChatActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // Кажемо системі, що екран відкрито (глушимо пуші)
        ChatEventBus.isChatScreenOpen = true
    }

    override fun onResume() {
        super.onResume()
        // НАЙГОЛОВНІШЕ РІШЕННЯ ДЛЯ ТВОГО ТЕСТУ:
        // Щоразу, коли ми повертаємося в додаток (розгортаємо його),
        // автоматично і тихо підтягуємо свіжі повідомлення.
        loadMessageHistory(silent = true)
    }

    override fun onStop() {
        super.onStop()
        // Коли згортаємо додаток - дозволяємо Firebase показувати пуші
        ChatEventBus.isChatScreenOpen = false
    }

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) {
            rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }
}