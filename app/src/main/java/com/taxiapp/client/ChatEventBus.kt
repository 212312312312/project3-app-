package com.taxiapp.client // (Або com.taxiapp.driver для водія)

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ChatEventBus {
    // Додаємо прапорець, щоб знати, чи ми зараз в екрані чату
    var isChatScreenOpen = false

    private val _newMessages = MutableSharedFlow<Unit>()
    val newMessages = _newMessages.asSharedFlow()

    suspend fun triggerUpdate() {
        _newMessages.emit(Unit)
    }
}