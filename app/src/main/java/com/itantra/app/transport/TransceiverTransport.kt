package com.itantra.app.transport

import com.itantra.app.model.SpeechAck
import com.itantra.app.model.SpeechPacket

enum class ConnectionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

interface TransceiverTransportListener {
    fun onConnectionStateChanged(state: ConnectionState, peerInfo: String?)
    fun onPacketReceived(packet: SpeechPacket, rawByteCount: Int)
    fun onAckReceived(ack: SpeechAck)
    fun onError(errorMessage: String)
}

interface TransceiverTransport {
    fun initialize()
    fun startDiscovery()
    fun stopDiscovery()
    fun sendPacket(packet: SpeechPacket, onSent: ((Boolean, Int) -> Unit)? = null)
    fun sendAck(ack: SpeechAck)
    fun disconnect()
    fun getState(): ConnectionState
    fun setListener(listener: TransceiverTransportListener)
}
