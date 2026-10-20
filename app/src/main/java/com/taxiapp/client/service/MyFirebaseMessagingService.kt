package com.taxiapp.client.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.taxiapp.client.ChatEventBus
import com.taxiapp.client.MainActivity
import com.taxiapp.client.R
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.OrderStatusBus
import com.taxiapp.client.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.random.Random

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val type = remoteMessage.data["type"]

        // 1. Трекинг маркетинговых и триггерных пушей скидок
        val triggerType = remoteMessage.data["trigger_type"] ?: type
        if (triggerType == "push_discount_sent" || triggerType == "48h_after_first_ride" || triggerType == "DISCOUNT_PROMO") {
            val sessionManager = SessionManager(applicationContext)
            AnalyticsManager.trackPushDiscountSent(
                userId = sessionManager.fetchUserId(),
                triggerType = triggerType
            )
        }

        // 2. Обработка сообщений чата (в реальном времени + счетчик непрочитанных + системное уведомление вне чата)
        if (type == "CHAT_MESSAGE") {
            ChatEventBus.triggerUpdateAsync()
            if (!ChatEventBus.isChatScreenOpen) {
                val title = remoteMessage.data["title"]
                    ?: remoteMessage.notification?.title
                    ?: "Нове повідомлення від водія"
                val body = remoteMessage.data["body"]
                    ?: remoteMessage.data["message"]
                    ?: remoteMessage.notification?.body
                    ?: "Відкрийте чат замовлення"
                showNotification(title, body)
            }
            return
        }

        // 3. Обработка тихого пуша со сменой статуса заказа
        if (type == "ORDER_STATUS_UPDATE") {
            val orderIdStr = remoteMessage.data["orderId"]
            val status = remoteMessage.data["status"]
            val title = remoteMessage.data["title"] ?: "Оновлення статусу"
            val body = remoteMessage.data["body"] ?: ""

            if (!orderIdStr.isNullOrEmpty() && !status.isNullOrEmpty()) {
                if (status == "COMPLETED" || status == "CANCELLED") {
                    val stopIntent = Intent(this, OrderStatusService::class.java).apply {
                        action = OrderStatusService.ACTION_STOP
                        putExtra(OrderStatusService.EXTRA_ORDER_ID, orderIdStr)
                    }
                    startService(stopIntent)

                    if (status == "COMPLETED") {
                        OrderStatusBus.notifyOrderUpdated(orderIdStr)
                    } else {
                        val sessionManager = SessionManager(applicationContext)
                        sessionManager.clearActiveOrderId()
                        OrderStatusBus.notifyOrderCanceled(orderIdStr)
                    }
                } else {
                    val updateIntent = Intent(this, OrderStatusService::class.java).apply {
                        putExtra(OrderStatusService.EXTRA_ORDER_ID, orderIdStr)
                        putExtra(OrderStatusService.EXTRA_STATUS, status)
                        putExtra("custom_title", title)
                        putExtra("custom_body", body)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(updateIntent)
                    } else {
                        startService(updateIntent)
                    }

                    OrderStatusBus.notifyOrderUpdated(orderIdStr)
                }
            }
            return
        }

        // 4. Стандартная нотификация (например, Новости и Акции)
        if (remoteMessage.notification != null) {
            showNotification(
                remoteMessage.notification?.title ?: "Сповіщення",
                remoteMessage.notification?.body ?: ""
            )
        } else if (remoteMessage.data.isNotEmpty()) {
            val title = remoteMessage.data["title"] ?: "Сповіщення"
            val body = remoteMessage.data["body"]
            if (body != null) {
                showNotification(title, body)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val sessionManager = SessionManager(applicationContext)
        if (!sessionManager.fetchAuthToken().isNullOrEmpty()) {
            try {
                val body = mapOf("fcmToken" to token)
                ApiClient.instance.updateFcmToken(body).enqueue(
                    object : Callback<Void> {
                        override fun onResponse(call: Call<Void>, response: Response<Void>) {}
                        override fun onFailure(call: Call<Void>, t: Throwable) {}
                    }
                )
            } catch (_: Exception) {}
        }
    }

    private fun showNotification(title: String, message: String) {
        val channelId = "taxi_news_channel"

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIconBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIconBitmap)
            .setColor(ContextCompat.getColor(this, R.color.notification_icon_bg))
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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