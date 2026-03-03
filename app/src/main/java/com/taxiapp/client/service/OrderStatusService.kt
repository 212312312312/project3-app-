package com.taxiapp.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.taxiapp.client.HomeActivity
import com.taxiapp.client.R
import android.content.pm.ServiceInfo

class OrderStatusService : Service() {

    companion object {
        const val CHANNEL_ID = "TaxiOrderStatusChannel"
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ADDRESS = "address"
        const val ACTION_STOP = "STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Если пришла команда на остановку (заказ завершен/отменен)
        if (intent?.action == ACTION_STOP) {
            val orderId = intent.getLongExtra(EXTRA_ORDER_ID, -1L)
            if (orderId != -1L) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(orderId.toInt()) // Убираем конкретное уведомление
            }
            stopForeground(true) // ДОДАНО: Правильна зупинка Foreground Service
            stopSelf()
            return START_NOT_STICKY
        }

        // Читаем данные заказа
        val orderId = intent?.getLongExtra(EXTRA_ORDER_ID, -1L) ?: return START_NOT_STICKY
        val status = intent.getStringExtra(EXTRA_STATUS) ?: ""
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: "Кінцева точка"

        // --- ДОДАНО: Читаємо текст із сервера ---
        val customTitle = intent.getStringExtra("custom_title")
        val customBody = intent.getStringExtra("custom_body")
        // ----------------------------------------

        val notification = buildNotification(orderId, status, address, customTitle, customBody)

        // Запускаем или обновляем уведомление.
        startForeground(orderId.toInt(), notification)

        return START_STICKY
    }

    // Змінено сигнатуру методу
    private fun buildNotification(orderId: Long, status: String, address: String, customTitle: String?, customBody: String?): Notification {
        // Маппинг статусов сервера на красивый украинский текст (fallback)
        val statusText = when (status) {
            "SCHEDULED" -> "Заплановано"
            "REQUESTED", "OFFERING" -> "Пошук водія..."
            "ACCEPTED" -> "Водій прямує до вас"
            "DRIVER_ARRIVED" -> "Водій очікує"
            "IN_PROGRESS" -> "В дорозі"
            "COMPLETED" -> "Поїздку завершено"
            "CANCELLED" -> "Скасовано"
            else -> status
        }

        // --- ДОДАНО: Використовуємо текст із сервера, якщо він є ---
        val finalTitle = customTitle ?: "Замовлення: $address"
        val finalBody = customBody ?: "Статус: $statusText"
        // -----------------------------------------------------------

        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, orderId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(finalTitle) // Використовуємо фінальний тайтл
            .setContentText(finalBody)   // Використовуємо фінальний текст
            .setStyle(NotificationCompat.BigTextStyle().bigText(finalBody)) // ДОДАНО: Щоб довгий текст влазив
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Звук тільки перший раз, далі - тихе оновлення (ЦЕ ТЕ ЩО НАМ ТРЕБА!)
            .build()
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Статус замовлення",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Відображає поточний статус вашого таксі в реальному часі"
                setSound(null, null) // Тихі оновлення
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}