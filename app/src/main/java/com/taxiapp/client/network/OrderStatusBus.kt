package com.taxiapp.client.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData


object OrderStatusBus {
    private val _orderCanceledEvent = MutableLiveData<String?>()
    val orderCanceledEvent: LiveData<String?> get() = _orderCanceledEvent

    // 🟢 ДОБАВЛЕНО: событие обновления статуса заказа
    private val _orderUpdatedEvent = MutableLiveData<String?>()
    val orderUpdatedEvent: LiveData<String?> get() = _orderUpdatedEvent

    fun notifyOrderCanceled(orderId: String) {
        _orderCanceledEvent.postValue(orderId)
    }

    // 🟢 ДОБАВЛЕНО
    fun notifyOrderUpdated(orderId: String) {
        _orderUpdatedEvent.postValue(orderId)
    }

    fun resetEvent() {
        _orderCanceledEvent.postValue(null)
        _orderUpdatedEvent.postValue(null)
    }
}