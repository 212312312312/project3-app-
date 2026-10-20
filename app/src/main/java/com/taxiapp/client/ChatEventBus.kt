package com.taxiapp.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ChatEventBus {
    private val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var isChatScreenOpen: Boolean = false
        set(value) {
            field = value
            if (value) {
                clearUnreadCount()
            }
        }

    private val _newMessages = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val newMessages = _newMessages.asSharedFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    suspend fun triggerUpdate() {
        if (!isChatScreenOpen) {
            _unreadCount.value = _unreadCount.value + 1
        }
        _newMessages.emit(Unit)
    }

    fun triggerUpdateAsync() {
        busScope.launch {
            triggerUpdate()
        }
    }

    fun clearUnreadCount() {
        _unreadCount.value = 0
    }
}