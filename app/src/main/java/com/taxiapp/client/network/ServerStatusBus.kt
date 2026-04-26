package com.taxiapp.client.network

import android.os.Handler
import android.os.Looper

object ServerStatusBus {
    private var listener: (() -> Unit)? = null

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    fun triggerServerError() {
        // Переключаемся на главный UI поток, чтобы безопасно показать диалог
        Handler(Looper.getMainLooper()).post {
            listener?.invoke()
        }
    }
}