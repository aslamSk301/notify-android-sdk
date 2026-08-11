package com.notifymvp.example

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class NotifEvent(val title: String, val body: String, val time: Long = System.currentTimeMillis())

/**
 * Simple in-process event bus using Kotlin SharedFlow.
 * Delivers foreground push notifications to any active observer.
 */
object NotifyEventBus {
    private val _events = MutableSharedFlow<NotifEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun emit(title: String, body: String) {
        _events.tryEmit(NotifEvent(title, body))
    }
}
