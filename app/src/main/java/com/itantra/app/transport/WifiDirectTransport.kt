package com.itantra.app.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.itantra.app.model.SpeechAck
import com.itantra.app.model.SpeechPacket
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Native Wi-Fi Direct phone-to-phone transport using standard TCP sockets.
 * Meets ISRO 26173 requirement for independent, native offline P2P transport.
 */
class WifiDirectTransport(private val context: Context) : TransceiverTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
        private const val PORT = 8888
        private const val MAGIC_PACKET: Byte = 0x53 // 'S' for SpeechPacket
        private const val MAGIC_ACK: Byte = 0x41    // 'A' for Ack
        private const val MAX_FRAME_SIZE = 65536     // 64 KB safety bound
    }

    private val p2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var listener: TransceiverTransportListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    private var connectionState = ConnectionState.IDLE
    private var connectedPeerName: String? = null

    // Socket connections
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var outStream: DataOutputStream? = null

    // Duplicate packet suppression
    private val seenPacketIds = ConcurrentHashMap.newKeySet<String>()

    override fun setListener(l: TransceiverTransportListener) {
        this.listener = l
    }

    override fun getState(): ConnectionState = connectionState

    @SuppressLint("MissingPermission")
    override fun initialize() {
        p2pChannel = p2pManager?.initialize(context, context.mainLooper, null)
        registerReceiver()
        Log.i(TAG, "Wi-Fi Direct transport initialized")
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            ContextCompat.registerReceiver(
                context,
                wifiP2pReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error registering wifiP2pReceiver: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        if (connectionState == ConnectionState.CONNECTED) return

        updateState(ConnectionState.DISCOVERING, null)
        p2pManager?.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct peer discovery started")
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Peer discovery failed code: $reasonCode")
                updateState(ConnectionState.ERROR, "Discovery failed: $reasonCode")
            }
        })
    }

    override fun stopDiscovery() {
        p2pManager?.stopPeerDiscovery(p2pChannel, null)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        updateState(ConnectionState.CONNECTING, device.deviceName)

        p2pManager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connecting to ${device.deviceName}...")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed connecting to ${device.deviceName}: $reason")
                updateState(ConnectionState.DISCONNECTED, null)
            }
        })
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

                Log.i(TAG, "Sent CBOR SpeechPacket (${cborBytes.size} data bytes, $totalBytes wire bytes)")
                mainHandler.post { onSent?.invoke(true, totalBytes) }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet", e)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error sending ACK", e)
            }
        }
    }

    private fun startSocketServer() {
        executor.execute {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "Socket server listening on port $PORT")

                val socket = serverSocket!!.accept()
                handleNewConnection(socket, "GroupOwnerClient")
            } catch (e: Exception) {
                Log.e(TAG, "Socket server error", e)
            }
        }
    }

    private fun connectSocketClient(hostAddress: String) {
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostAddress, PORT), 5000)
                handleNewConnection(socket, hostAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Failed connecting to group owner at $hostAddress", e)
                updateState(ConnectionState.DISCONNECTED, null)
            }
        }
    }

    private fun handleNewConnection(socket: Socket, peerDesc: String) {
        activeSocket = socket
        outStream = DataOutputStream(socket.getOutputStream())
        connectedPeerName = peerDesc
        updateState(ConnectionState.CONNECTED, peerDesc)

        executor.execute {
            readIncomingLoop(socket)
        }
    }

    private fun readIncomingLoop(socket: Socket) {
        try {
            val inStream = DataInputStream(socket.getInputStream())
            while (!socket.isClosed && socket.isConnected) {
                val magic = inStream.readByte()
                val length = inStream.readInt()

                if (length < 0 || length > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid packet length received: $length")
                    break
                }

                val payload = ByteArray(length)
                inStream.readFully(payload)
                val totalWireBytes = 1 + 4 + length

                if (magic == MAGIC_PACKET) {
                    val packet = SpeechPacket.fromCbor(payload)
                    if (packet != null) {
                        val dedupKey = "${packet.messageId}_${packet.sequence}"
                        if (seenPacketIds.add(dedupKey)) {
                            // Trim dedup cache if too large
                            if (seenPacketIds.size > 500) seenPacketIds.clear()

                            // Auto-send delivery ACK
                            sendAck(SpeechAck(messageId = packet.messageId, sequence = packet.sequence, status = "RECEIVED"))

                            mainHandler.post {
                                listener?.onPacketReceived(packet, totalWireBytes)
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
            Log.i(TAG, "Socket closed or disconnected: ${e.message}")
        } finally {
            closeSocket()
            updateState(ConnectionState.DISCONNECTED, null)
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
        p2pManager?.removeGroup(p2pChannel, null)
        updateState(ConnectionState.DISCONNECTED, null)
    }

    private fun updateState(newState: ConnectionState, peer: String?) {
        connectionState = newState
        mainHandler.post {
            listener?.onConnectionStateChanged(newState, peer)
        }
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        p2pManager?.requestConnectionInfo(p2pChannel) { info ->
                            if (info.groupFormed) {
                                if (info.isGroupOwner) {
                                    startSocketServer()
                                } else {
                                    info.groupOwnerAddress?.hostAddress?.let { host ->
                                        connectSocketClient(host)
                                    }
                                }
                            }
                        }
                    } else {
                        closeSocket()
                        if (connectionState == ConnectionState.CONNECTED) {
                            updateState(ConnectionState.DISCONNECTED, null)
                        }
                    }
                }
            }
        }
    }
}
