package com.taxiapp.client.controllers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.TaxiOrderDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Модульный контроллер таймеров активного заказа:
 * 1. Таймер бесплатного / платного ожидания водителя (startWaitingTimer / stopWaitingTimer)
 * 2. Таймер предложения ускорить поиск (checkAndScheduleBoostPrompt / stopBoostTimer)
 *
 * Исправляет критический баг часового пояса UTC: сервер присылает время в UTC без суффикса Z,
 * поэтому парсинг через SimpleDateFormat с локальной таймзоной давал сдвиг на +2/+3 часа.
 */
class OrderTimersController(private val context: Context) {

    private val waitingTimerHandler = Handler(Looper.getMainLooper())
    private var waitingTimerRunnable: Runnable? = null

    private val boostTimerHandler = Handler(Looper.getMainLooper())
    private var boostRunnable: Runnable? = null
    var boostDialog: BottomSheetDialog? = null

    companion object {
        /**
         * Универсальный парсер серверного времени (UTC) в миллисекунды эпохи (Epoch Millis).
         */
        fun parseServerUtcToEpochMs(rawTime: String?): Long? {
            if (rawTime.isNullOrBlank()) return null
            val trimmed = rawTime.trim()
            return try {
                when {
                    trimmed.endsWith("Z", ignoreCase = true) ->
                        Instant.parse(trimmed).toEpochMilli()
                    trimmed.contains("+") || (trimmed.length > 19 && trimmed.substring(19).contains("-")) ->
                        OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
                    else -> {
                        val clean = trimmed.substringBefore(".").take(19)
                        LocalDateTime.parse(clean).toInstant(ZoneOffset.UTC).toEpochMilli()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun startWaitingTimer(
        order: TaxiOrderDto,
        cardWaitingTimer: View,
        tvNewWaitingTimer: TextView
    ) {
        stopWaitingTimer(cardWaitingTimer)

        val timeSource = order.waitingStartTime ?: order.arrivedAt
        val arrivedTimeMs = parseServerUtcToEpochMs(timeSource)
        if (arrivedTimeMs == null) {
            cardWaitingTimer.visibility = View.GONE
            return
        }

        cardWaitingTimer.visibility = View.VISIBLE

        val runnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val diffMs = if (now >= arrivedTimeMs) now - arrivedTimeMs else 0L

                val diffMinutesFull = diffMs / (1000.0 * 60.0)
                val freeMins = order.freeWaitingMinutes

                if (diffMinutesFull <= freeMins) {
                    // 1. БЕСПЛАТНОЕ ОЖИДАНИЕ (Обратный отсчет)
                    val remainingMs = (freeMins * 60 * 1000L) - diffMs
                    val remMin = (remainingMs / (1000 * 60)).toInt().coerceAtLeast(0)
                    val remSec = ((remainingMs / 1000) % 60).toInt().coerceAtLeast(0)

                    tvNewWaitingTimer.text = String.format(Locale.US, "%02d:%02d", remMin, remSec)
                    tvNewWaitingTimer.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                } else {
                    // 2. ПЛАТНОЕ ОЖИДАНИЕ (+00:01, +00:02...)
                    val paidMs = diffMs - (freeMins * 60 * 1000L)
                    val paidMin = (paidMs / (1000 * 60)).toInt()
                    val paidSec = ((paidMs / 1000) % 60).toInt()

                    tvNewWaitingTimer.text = String.format(Locale.US, "+%02d:%02d", paidMin, paidSec)
                    tvNewWaitingTimer.setTextColor(ContextCompat.getColor(context, R.color.taxi_red_cancel))
                }
                waitingTimerHandler.postDelayed(this, 1000L)
            }
        }
        waitingTimerRunnable = runnable
        waitingTimerHandler.post(runnable)
    }

    fun stopWaitingTimer(cardWaitingTimer: View? = null) {
        waitingTimerRunnable?.let { waitingTimerHandler.removeCallbacks(it) }
        waitingTimerRunnable = null
        cardWaitingTimer?.visibility = View.GONE
    }

    fun checkAndScheduleBoostPrompt(
        order: TaxiOrderDto,
        isBoostPromptShown: Boolean,
        canShowBoostNow: () -> Boolean,
        onTriggerBoostDialog: () -> Unit
    ) {
        if (isBoostPromptShown) return

        stopBoostTimer()

        val createdTimeMs = parseServerUtcToEpochMs(order.createdAt) ?: return
        val elapsedSeconds = ((System.currentTimeMillis() - createdTimeMs) / 1000L).toInt().coerceAtLeast(0)
        val targetDelaySeconds = 180 - elapsedSeconds // 3 минуты = 180 сек

        if (targetDelaySeconds <= 0) {
            if (canShowBoostNow()) {
                onTriggerBoostDialog()
            }
        } else {
            val runnable = Runnable {
                if (canShowBoostNow()) {
                    onTriggerBoostDialog()
                }
            }
            boostRunnable = runnable
            boostTimerHandler.postDelayed(runnable, targetDelaySeconds * 1000L)
        }
    }

    fun stopBoostTimer() {
        boostRunnable?.let { boostTimerHandler.removeCallbacks(it) }
        boostRunnable = null
        try {
            boostDialog?.dismiss()
        } catch (_: Exception) {}
        boostDialog = null
    }

    fun release(cardWaitingTimer: View? = null) {
        stopWaitingTimer(cardWaitingTimer)
        stopBoostTimer()
    }
}