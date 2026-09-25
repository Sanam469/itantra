package com.itantra.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.itantra.app.model.SpeechAck
import com.itantra.app.model.SpeechPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Native Bluetooth RFCOMM Transport using Serial Port Profile (SPP).
 * Enables point-to-point wireless transmission between phones or embedded transceivers.
 * Meets ISRO 26173 specification for Bluetooth transport adapter.
 */
class BluetoothRfcommTransport(private val context: Context) : TransceiverTransport {

    companion object {
        private const val TAG = "BTRfcommTransport"
        private const val SERVICE_NAME = "iTantraTransceiver"
        // Standard SPP UUID: 00001101-0000-1000-8000-00805F9B34FB
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAGIC_PACKET: Byte = 0x53
        private const val MAGIC_ACK: Byte = 0x41
        private const val MAX_FRAME_SIZE = 65536
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var listener: TransceiverTransportListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    private var connectionState = ConnectionState.IDLE
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var outStream: DataOutputStream? = null

    private val seenPacketIds = ConcurrentHashMap.newKeySet<String>()

    override fun setListener(listener: TransceiverTransportListener) {
        this.listener = listener
    }

    override fun getState(): ConnectionState = connectionState

    override fun initialize() {
        Log.i(TAG, "Bluetooth RFCOMM adapter initialized (SPP profile)")
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        if (bluetoothAdapter?.isEnabled != true) {
            updateState(ConnectionState.ERROR, "Bluetooth is disabled")
            return
        }

        updateState(ConnectionState.DISCOVERING, null)
        startListeningServer()
    }

    override fun stopDiscovery() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun startListeningServer() {
        executor.execute {
            try {
                serverSocket?.close()
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                Log.i(TAG, "Bluetooth server listening for connections on SPP...")

                val socket = serverSocket?.accept()
                if (socket != null) {
                    serverSocket?.close()
                    handleConnectedSocket(socket, socket.remoteDevice?.name ?: "BluetoothPeer")
                }
            } catch (e: IOException) {
                Log.d(TAG, "Server socket closed or accept error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        updateState(ConnectionState.CONNECTING, device.name)
        executor.execute {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                handleConnectedSocket(socket, device.name ?: device.address)
            } catch (e: Exception) {
                Log.e(TAG, "Failed connecting to Bluetooth device: ${device.name}", e)
                updateState(ConnectionState.DISCONNECTED, null)
            }
        }
    }

    private fun handleConnectedSocket(socket: BluetoothSocket, peerName: String) {
        activeSocket = socket
        outStream = DataOutputStream(socket.outputStream)
        updateState(ConnectionState.CONNECTED, peerName)

        executor.execute {
            readIncomingLoop(socket)
        }
    }

    private fun readIncomingLoop(socket: BluetoothSocket) {
        try {
            val inStream = DataInputStream(socket.inputStream)
            while (socket.isConnected) {
                val magic = inStream.readByte()
                val length = inStream.readInt()

                if (length < 0 || length > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid Bluetooth frame size: $length")
                    break
                }

                val payload = ByteArray(length)
                inStream.readFully(payload)
                val totalBytes = 1 + 4 + length

                if (magic == MAGIC_PACKET) {
                    val packet = SpeechPacket.fromCbor(payload)
                    if (packet != null) {
                        val key = "${packet.messageId}_${packet.sequence}"
                        if (seenPacketIds.add(key)) {
                            if (seenPacketIds.size > 500) seenPacketIds.clear()

                            sendAck(SpeechAck(messageId = packet.messageId, sequence = packet.sequence, status = "RECEIVED"))

                            mainHandler.post {
                                listener?.onPacketReceived(packet, totalBytes)
                            }
                        }
                    }
                } else if (magic == MAGIC_ACK) {
                    val ack = SpeechAck.fromCbor(payload)
                    if (ack != null) {
                        mainHandler.post {
                            listener?.onAckReceived(ack)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Bluetooth connection closed: ${e.message}")
        } finally {
            closeSocket()
            updateState(ConnectionState.DISCONNECTED, null)
        }
    }

    override fun sendPacket(packet: SpeechPacket, onSent: ((Boolean, Int) -> Unit)?) {
        executor.execute {
            val stream = outStream
            if (stream == null || activeSocket?.isConnected != true) {
                mainHandler.post { onSent?.invoke(false, 0) }
                return@execute
            }

            try {
                val cborBytes = SpeechPacket.toCbor(packet)
                val totalBytes = 1 + 4 + cborBytes.size

                synchronized(stream) {
                    stream.writeByte(MAGIC_PACKET.toInt())
                    stream.writeInt(cborBytes.size)
                    stream.write(cborBytes)
                    stream.flush()
                }

                mainHandler.post { onSent?.invoke(true, totalBytes) }
            } catch (e: Exception) {
                Log.e(TAG, "BT send failed", e)
                mainHandler.post { onSent?.invoke(false, 0) }
            }
        }
    }

    override fun sendAck(ack: SpeechAck) {
        executor.execute {
            val stream = outStream ?: return@execute
            try {
                val cborBytes = SpeechAck.toCbor(ack)
                synchronized(stream) {
                    stream.writeByte(MAGIC_ACK.toInt())
                    stream.writeInt(cborBytes.size)
                    stream.write(cborBytes)
                    stream.flush()
                }
            } catch (e: Exception) {}
        }
    }

    private fun closeSocket() {
        try {
            outStream?.close()
            activeSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {}
        outStream = null
        activeSocket = null
        serverSocket = null
    }

    override fun disconnect() {
        closeSocket()
        updateState(ConnectionState.DISCONNECTED, null)
    }

    private fun updateState(newState: ConnectionState, peer: String?) {
        connectionState = newState
        mainHandler.post {
            listener?.onConnectionStateChanged(newState, peer)
        }
    }
}
