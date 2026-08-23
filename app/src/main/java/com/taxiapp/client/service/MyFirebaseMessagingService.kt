package com.taxiapp.client.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.taxiapp.client.ChatEventBus
import com.google.firebase.messaging.RemoteMessage
import com.taxiapp.client.MainActivity
import com.taxiapp.client.R
import kotlin.random.Random

class MyFirebaseMessagingService : FirebaseMessagingService() {

    // Цей метод викликається, коли приходить повідомлення, а додаток відкритий
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val type = remoteMessage.data["type"]

        // 1. Обробка повідомлень чату
        if (type == "CHAT_MESSAGE") {
            if (com.taxiapp.client.ChatEventBus.isChatScreenOpen) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    com.taxiapp.client.ChatEventBus.triggerUpdate()
                }
                return
            }
        }

        // =======================================================================
        // 2. ДОДАНО: Обробка тихого пуша зі зміною статусу замовлення
        // =======================================================================
        if (type == "ORDER_STATUS_UPDATE") {
            val orderIdStr = remoteMessage.data["orderId"]
            val status = remoteMessage.data["status"]
            val title = remoteMessage.data["title"] ?: "Оновлення статусу"
            val body = remoteMessage.data["body"] ?: ""

            if (orderIdStr != null && status != null) {
                val orderId = orderIdStr.toLongOrNull() ?: return

                // Если заказ закрыт или отменен — останавливаем виджет шторки и очищаем локальную сессию
                if (status == "COMPLETED" || status == "CANCELLED") {
                    val stopIntent = Intent(this, OrderStatusService::class.java).apply {
                        action = OrderStatusService.ACTION_STOP
                        putExtra(OrderStatusService.EXTRA_ORDER_ID, orderIdStr) // Передаем строковый UUID
                    }
                    startService(stopIntent)

                    if (status == "COMPLETED") {
                        // Передаем сигнал обновления, чтобы экран показал завершение поездки и оценку
                        com.taxiapp.client.network.OrderStatusBus.notifyOrderUpdated(orderIdStr)
                    } else {
                        val sessionManager = com.taxiapp.client.utils.SessionManager(applicationContext)
                        sessionManager.clearActiveOrderId()
                        com.taxiapp.client.network.OrderStatusBus.notifyOrderCanceled(orderIdStr)
                    }
                } else {
                    // Если заказ активен — обновляем Foreground Service
                    val updateIntent = Intent(this, OrderStatusService::class.java).apply {
                        putExtra(OrderStatusService.EXTRA_ORDER_ID, orderId)
                        putExtra(OrderStatusService.EXTRA_STATUS, status)
                        putExtra("custom_title", title)
                        putExtra("custom_body", body)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(updateIntent)
                    } else {
                        startService(updateIntent)
                    }

                    // 🟢 ДОБАВЛЕНО: Мгновенно триггерим обновление UI на открытом экране
                    com.taxiapp.client.network.OrderStatusBus.notifyOrderUpdated(orderIdStr)
                }
            }
            return
        }
        // =======================================================================

        // 3. Якщо є стандартна нотифікація (наприклад, Новини)
        if (remoteMessage.notification != null) {
            showNotification(remoteMessage.notification?.title ?: "Сповіщення", remoteMessage.notification?.body ?: "")
        }
        // Якщо це якийсь інший data payload (не статус і не чат)
        else if (remoteMessage.data.isNotEmpty() && type != "CHAT_MESSAGE") {
            val title = remoteMessage.data["title"] ?: "Сповіщення"
            val body = remoteMessage.data["body"]
            if (body != null) {
                showNotification(title, body)
            }
        }
    }

    // Цей метод викликається, коли Firebase оновлює токен (рідко)
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Тут ми можемо зберегти токен локально, щоб потім відправити на сервер
        // Але краще ми будемо примусово відправляти його при кожному запуску App
    }

    private fun showNotification(title: String, message: String) {
        val channelId = "taxi_news_channel"

        // Що відкривати при кліку на сповіщення (Головний екран)
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIconBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIconBitmap)
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.notification_icon_bg))
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Для Android 8+ (Oreo) потрібен канал
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Новини та Акції",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(Random.nextInt(), notificationBuilder.build())
    }
}