package com.taxiapp.client.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object ServerStatusBus {

    // Для ошибок сервера (502/503)
    private val _serverError = MutableLiveData<Boolean>()
    val serverError: LiveData<Boolean> get() = _serverError

    // Для просроченной сессии (разлогин)
    private val _sessionExpired = MutableLiveData<Boolean>()
    val sessionExpired: LiveData<Boolean> get() = _sessionExpired

    // НОВОЕ: Для уведомления об успешном рефреше токена (нужно для WebSockets)
    private val _tokenRefreshed = MutableLiveData<String>()
    val tokenRefreshed: LiveData<String> get() = _tokenRefreshed

    fun triggerServerError() {
        _serverError.postValue(true)
    }

    fun resetServerError() {
        _serverError.postValue(false)
    }

    fun triggerSessionExpired() {
        _sessionExpired.postValue(true)
    }

    fun resetSessionExpired() {
        _sessionExpired.postValue(false)
    }

    // НОВОЕ: Триггер обновления токена
    fun triggerTokenRefreshed(newToken: String) {
        _tokenRefreshed.postValue(newToken)
    }
}