package com.taxiapp.client.controllers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.TaxiOrderDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * Модульный контроллер таймеров активного заказа:
 * 1. Таймер бесплатного / платного ожидания водителя (startWaitingTimer / stopWaitingTimer)
 * 2. Таймер предложения ускорить поиск (checkAndScheduleBoostPrompt / stopBoostTimer)
 *
 * Архитектура синхронизации:
 * - Источник истины — сервер (waitingElapsedSeconds / serverTime / waitingStartTime / arrivedAt).
 * - На клиенте отсчет ведется через монотонный системный таймер ядра ОС (SystemClock.elapsedRealtime()),
 *   который невозможно обмануть переводом часов в настройках телефона или очисткой кэша.
 */
class OrderTimersController(private val context: Context) {

    private val waitingTimerHandler = Handler(Looper.getMainLooper())
    private var waitingTimerRunnable: Runnable? = null

    private var activeTimerOrderId: String? = null
    private var activeTimerTimeSource: String? = null
    private var activeBaseElapsedRealtimeMs: Long = 0L

    private val boostTimerHandler = Handler(Looper.getMainLooper())
    private var boostRunnable: Runnable? = null
    var boostDialog: BottomSheetDialog? = null

    companion object {
        /**
         * Универсальный парсер серверного времени в миллисекунды эпохи (Epoch Millis).
         * Корректно обрабатывает:
         * - Числовой epoch-timestamp (секунды или миллисекунды)
         * - ISO Instant с 'Z' (например, 2026-10-22T17:22:00Z)
         * - OffsetDateTime со смещением (например, 2026-10-22T20:22:00+03:00)
         * - LocalDateTime без смещения (автоматически определяет, прислал ли сервер время
         *   в локальной зоне устройства/сервера или в UTC, исключая сдвиг в будущее на +2/+3 часа).
         */
        fun parseServerUtcToEpochMs(rawTime: String?): Long? {
            if (rawTime.isNullOrBlank()) return null
            val trimmed = rawTime.trim()
            return try {
                when {
                    trimmed.all { it.isDigit() } -> {
                        val num = trimmed.toLong()
                        if (trimmed.length <= 10) num * 1000L else num
                    }
                    trimmed.endsWith("Z", ignoreCase = true) ->
                        Instant.parse(trimmed).toEpochMilli()
                    trimmed.contains("+") || (trimmed.length > 19 && trimmed.substring(19).contains("-")) ->
                        OffsetDateTime.parse(trimmed.replace(" ", "T")).toInstant().toEpochMilli()
                    else -> {
                        val normalized = trimmed.replace(" ", "T")
                        val cleanSec = normalized.substringBefore(".").take(19)
                        val paddedSec = if (cleanSec.length == 16) "$cleanSec:00" else cleanSec
                        val ldt = LocalDateTime.parse(paddedSec)

                        val millisFraction = normalized
                            .substringAfter(".", "")
                            .takeWhile { it.isDigit() }
                            .padEnd(3, '0')
                            .take(3)
                            .toLongOrNull() ?: 0L

                        val asLocalMs = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + millisFraction
                        val asUtcMs = ldt.toInstant(ZoneOffset.UTC).toEpochMilli() + millisFraction

                        val now = System.currentTimeMillis()
                        val maxFutureSkewMs = 120_000L // 2 минуты допуска на рассинхрон часов

                        val localValid = asLocalMs <= now + maxFutureSkewMs
                        val utcValid = asUtcMs <= now + maxFutureSkewMs

                        when {
                            localValid && !utcValid -> asLocalMs
                            utcValid && !localValid -> asUtcMs
                            else -> if (abs(now - asLocalMs) <= abs(now - asUtcMs)) asLocalMs else asUtcMs
                        }
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
        val timeSource = order.waitingStartTime?.takeIf { it.isNotBlank() }
            ?: order.arrivedAt?.takeIf { it.isNotBlank() }

        // Если таймер уже запущен для этого же заказа и сервер не прислал новую дельту/метку времени —
        // не перезапускаем его заново при каждом обновлении UI, чтобы не сбивать секундный шаг.
        if (waitingTimerRunnable != null &&
            activeTimerOrderId == order.id &&
            order.waitingElapsedSeconds == null &&
            (activeTimerTimeSource == timeSource || timeSource == null)
        ) {
            cardWaitingTimer.visibility = View.VISIBLE
            return
        }

        waitingTimerRunnable?.let { waitingTimerHandler.removeCallbacks(it) }
        waitingTimerRunnable = null

        val nowWallMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val parsedServerStartMs = parseServerUtcToEpochMs(timeSource)
        val parsedServerNowMs = parseServerUtcToEpochMs(order.serverTime)

        // Приоритет 1: прямая серверная дельта (waitingElapsedSeconds) — 100% независимость от часов телефона.
        // Приоритет 2: разница между serverTime и waitingStartTime/arrivedAt с сервера.
        // Приоритет 3: разница между текущим временем и серверным waitingStartTime/arrivedAt.
        val initialElapsedMs = when {
            order.waitingElapsedSeconds != null ->
                (order.waitingElapsedSeconds * 1000L).coerceAtLeast(0L)
            parsedServerStartMs != null && parsedServerNowMs != null ->
                (parsedServerNowMs - parsedServerStartMs).coerceAtLeast(0L)
            parsedServerStartMs != null ->
                (nowWallMs - parsedServerStartMs).coerceAtLeast(0L)
            else -> 0L
        }

        activeTimerOrderId = order.id
        activeTimerTimeSource = timeSource
        activeBaseElapsedRealtimeMs = nowElapsedMs - initialElapsedMs

        cardWaitingTimer.visibility = View.VISIBLE

        val freeMins = if (order.freeWaitingMinutes > 0) order.freeWaitingMinutes else 3
        val freeLimitMs = freeMins * 60 * 1000L

        val runnable = object : Runnable {
            override fun run() {
                val diffMs = (SystemClock.elapsedRealtime() - activeBaseElapsedRealtimeMs).coerceAtLeast(0L)

                if (diffMs <= freeLimitMs) {
                    // 1. БЕСПЛАТНОЕ ОЖИДАНИЕ (Обратный отсчет: 03:00 -> 02:59 -> ... -> 00:00)
                    val remainingMs = (freeLimitMs - diffMs).coerceAtLeast(0L)
                    val totalRemSec = (remainingMs / 1000L).toInt()
                    val remMin = totalRemSec / 60
                    val remSec = totalRemSec % 60

                    tvNewWaitingTimer.text = String.format(Locale.US, "%02d:%02d", remMin, remSec)
                    tvNewWaitingTimer.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                } else {
                    // 2. ПЛАТНОЕ ОЖИДАНИЕ (+00:01, +00:02...)
                    val paidMs = diffMs - freeLimitMs
                    val totalPaidSec = (paidMs / 1000L).toInt()
                    val paidMin = totalPaidSec / 60
                    val paidSec = totalPaidSec % 60

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
        activeTimerOrderId = null
        activeTimerTimeSource = null
        activeBaseElapsedRealtimeMs = 0L
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