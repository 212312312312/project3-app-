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

        // Перевіряємо, чи це системне повідомлення чату
        val type = remoteMessage.data["type"]
        if (type == "CHAT_MESSAGE") {
            // Перевіряємо, чи відкритий екран чату
            if (com.taxiapp.client.ChatEventBus.isChatScreenOpen) {
                // Екран відкрито - тихо оновлюємо чат (без пуш-банера)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    com.taxiapp.client.ChatEventBus.triggerUpdate()
                }
                return // Блокуємо показ візуального пуша
            }
            // Якщо екран закрито - код піде нижче і покаже стандартне сповіщення!
        }

        // Якщо у повідомлення є заголовок і текст -> показуємо його
        remoteMessage.notification?.let {
            showNotification(it.title ?: "Сповіщення", it.body ?: "")
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

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Переконайтесь, що іконка існує, або замініть на вашу
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