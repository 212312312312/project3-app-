package com.taxiapp.client.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderStatusBus {
    private val _orderCanceledEvent = MutableLiveData<String?>()
    val orderCanceledEvent: LiveData<String?> get() = _orderCanceledEvent

    fun notifyOrderCanceled(orderId: String) {
        _orderCanceledEvent.postValue(orderId)
    }

    fun resetEvent() {
        _orderCanceledEvent.postValue(null)
    }
}