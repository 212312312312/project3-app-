package com.taxiapp.client.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderStatusBus {
    private val _orderCanceledEvent = MutableLiveData<String?>()
    val orderCanceledEvent: LiveData<String?> get() = _orderCanceledEvent

    private val _orderUpdatedEvent = MutableLiveData<String?>()
    val orderUpdatedEvent: LiveData<String?> get() = _orderUpdatedEvent

    fun notifyOrderCanceled(orderId: String) {
        if (orderId.isNotBlank()) {
            _orderCanceledEvent.postValue(orderId)
        }
    }

    fun notifyOrderUpdated(orderId: String) {
        if (orderId.isNotBlank()) {
            _orderUpdatedEvent.postValue(orderId)
        }
    }

    fun resetCanceledEvent() {
        _orderCanceledEvent.postValue(null)
    }

    fun resetUpdatedEvent() {
        _orderUpdatedEvent.postValue(null)
    }

    fun resetEvent() {
        _orderCanceledEvent.postValue(null)
        _orderUpdatedEvent.postValue(null)
    }
}