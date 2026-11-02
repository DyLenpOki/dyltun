package com.dylenpoki.dyltun

import java.io.Closeable
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface DyltunSession : Closeable {
    val state: StateFlow<DyltunState>
    val events: SharedFlow<DyltunEvent>
    override fun close()
    suspend fun awaitClosed()
}

sealed interface DyltunState {
    data object Starting : DyltunState
    data object Running : DyltunState
    data object Stopping : DyltunState
    data object Stopped : DyltunState
    data class Failed(val cause: Throwable) : DyltunState
}

sealed interface DyltunEvent {
    data class ConnectionError(val connection: ConnectionInfo?, val cause: Throwable) : DyltunEvent
    data class PacketRejected(val reason: String) : DyltunEvent
}
