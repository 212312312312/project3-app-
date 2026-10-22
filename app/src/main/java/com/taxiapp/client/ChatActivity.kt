package com.taxiapp.client

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.WebSocketManager
import com.taxiapp.client.network.dto.SendMessageRequest
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ChatActivity : BaseActivity() {

    private var orderId: String = ""
    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnBack: ImageView

    private val chatAdapter = ChatAdapter(mutableListOf())
    private lateinit var sessionManager: SessionManager
    private var webSocketManager: WebSocketManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        try {
            ViewUtils.makeImmersive(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val rootLayout = findViewById<View>(R.id.root_chat_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val paddingTop = (48 * resources.displayMetrics.density).toInt()

            view.setPadding(view.paddingLeft, paddingTop, view.paddingRight, imeHeight)

            if (imeHeight > 0) {
                scrollToBottom()
            }
            insets
        }

        orderId = intent.getStringExtra("ORDER_ID") ?: ""
        if (orderId.isEmpty()) {
            finish()
            return
        }

        sessionManager = SessionManager(this)
        webSocketManager = WebSocketManager(ApiClient.BASE_URL, this)

        initUI()
        loadMessageHistory()

        // 1. Real-time оновлення по WebSocket (/topic/chat/{orderId})
        val token = sessionManager.fetchAuthToken()
        if (!token.isNullOrEmpty()) {
            webSocketManager?.connect(token)
            webSocketManager?.subscribeToChat(orderId) { incomingMessage ->
                if (!isFinishing && !isDestroyed) {
                    if (chatAdapter.addMessage(incomingMessage)) {
                        scrollToBottom()
                    }
                }
            }
        }

        // 2. Fallback-оновлення при отриманні FCM Push (коли екран чату відкритий)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatEventBus.newMessages.collect {
                    loadMessageHistory(silent = true)
                }
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
        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getChatMessages(orderId)
                if (isFinishing || isDestroyed) return@launch
                if (response.isSuccessful) {
                    val messages = response.body() ?: emptyList()
                    val updated = chatAdapter.updateMessages(messages)
                    if (!silent || updated) {
                        scrollToBottom()
                    }
                } else if (!silent) {
                    Toast.makeText(this@ChatActivity, "Помилка завантаження чату", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed && !silent) {
                    Toast.makeText(this@ChatActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendMessage(text: String) {
        val request = SendMessageRequest(text)
        btnSend.isEnabled = false
        etMessage.text.clear()

        lifecycleScope.launch {
            try {
                val response = ApiClient.instance.sendChatMessage(orderId, request)
                if (isFinishing || isDestroyed) return@launch
                btnSend.isEnabled = true

                if (response.isSuccessful) {
                    response.body()?.let {
                        if (chatAdapter.addMessage(it)) {
                            scrollToBottom()
                        }
                    }
                } else {
                    if (etMessage.text.isEmpty()) {
                        etMessage.setText(text)
                        etMessage.setSelection(etMessage.text.length)
                    }
                    Toast.makeText(this@ChatActivity, "Помилка відправки", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                btnSend.isEnabled = true
                if (etMessage.text.isEmpty()) {
                    etMessage.setText(text)
                    etMessage.setSelection(etMessage.text.length)
                }
                Toast.makeText(this@ChatActivity, "Помилка з'єднання", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ChatEventBus.isChatScreenOpen = true
    }

    override fun onResume() {
        super.onResume()
        if (webSocketManager?.isConnected() != true) {
            val token = sessionManager.fetchAuthToken()
            if (!token.isNullOrEmpty()) {
                webSocketManager?.connect(token)
            }
        }
        loadMessageHistory(silent = true)
    }

    override fun onStop() {
        super.onStop()
        ChatEventBus.isChatScreenOpen = false
    }

    override fun onDestroy() {
        webSocketManager?.unsubscribeFromChat()
        webSocketManager?.destroy()
        webSocketManager = null
        super.onDestroy()
    }

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) {
            rvChat.post {
                if (!isFinishing && !isDestroyed && chatAdapter.itemCount > 0) {
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }
            }
        }
    }
}