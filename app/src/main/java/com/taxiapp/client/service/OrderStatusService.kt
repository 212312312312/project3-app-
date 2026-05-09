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
        val orderId = intent?.getLongExtra(EXTRA_ORDER_ID, -1L) ?: -1L

        // Коректна зупинка сервісу
        if (intent?.action == ACTION_STOP) {
            if (orderId != -1L) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(orderId.toInt())
            }
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val status = intent?.getStringExtra(EXTRA_STATUS) ?: ""
        val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: "Кінцева точка"
        val customTitle = intent?.getStringExtra("custom_title")
        val customBody = intent?.getStringExtra("custom_body")

        // Гарантований виклик startForeground рятує від крашу ForegroundServiceDidNotStartInTimeException
        val notification = buildNotification(orderId, status, address, customTitle, customBody)
        val notificationId = if (orderId != -1L) orderId.toInt() else 1
        startForeground(notificationId, notification)

        // START_NOT_STICKY - забороняє Android перестворювати сервіс з пустим інтентом
        return START_NOT_STICKY
    }

    private fun buildNotification(orderId: Long, status: String, address: String, customTitle: String?, customBody: String?): Notification {
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

        val finalTitle = customTitle ?: "Замовлення: $address"
        val finalBody = customBody ?: "Статус: $statusText"

        val pendingIntent = PendingIntent.getActivity(
            this, orderId.toInt(),
            Intent(this, HomeActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(finalTitle)
            .setContentText(finalBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(finalBody))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}