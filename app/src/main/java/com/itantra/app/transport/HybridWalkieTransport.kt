package com.itantra.app.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.itantra.app.model.SpeechAck
import com.itantra.app.model.SpeechPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hybrid Walkie-Talkie Transport combining:
 * 1. Instant Hotspot / Local Wi-Fi TCP/UDP Sockets (Sub-second connection on Hotspot)
 * 2. Google Nearby Connections (Offline Bluetooth, BLE & Wi-Fi P2P fallback)
 *
 * Implements TransceiverTransport for seamless two-phone walkie-talkie communication.
 */
class HybridWalkieTransport(private val context: Context) : TransceiverTransport {

    companion object {
        private const val TAG = "HybridWalkieTransport"
        private const val SERVICE_ID = "com.itantra.walkie"
        private const val TCP_PORT = 8888
        private const val UDP_PORT = 8889
        private const val MAGIC_PACKET: Byte = 0x53 // 'S'
        private const val MAGIC_ACK: Byte = 0x41    // 'A'
        private const val MAX_FRAME_SIZE = 65536
    }

    private var listener: TransceiverTransportListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    private var connectionState = ConnectionState.IDLE
    private var connectedPeerName: String? = null
    private val isDiscovering = AtomicBoolean(false)

    // Unique device name
    private val deviceName = "iTantra-${Build.MODEL.take(8)}-${(100..999).random()}"

    // Duplicate packet suppression across both channels
    private val seenPacketIds = ConcurrentHashMap.newKeySet<String>()

    // ==================== CHANNEL 1: HOTSPOT / LAN SOCKETS ====================
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var activeTcpSocket: Socket? = null
    private var tcpOutStream: DataOutputStream? = null
    private val tcpWriteLock = Any()
    private var multicastLock: WifiManager.MulticastLock? = null

    // ==================== CHANNEL 2: GOOGLE NEARBY CONNECTIONS ====================
    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private var nearbyEndpointId: String? = null

    override fun setListener(l: TransceiverTransportListener) {
        this.listener = l
    }

    override fun getState(): ConnectionState = connectionState

    override fun initialize() {
        Log.i(TAG, "Initializing Hybrid Walkie Transport as: $deviceName")
        acquireMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("iTantraMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    override fun startDiscovery() {
        if (connectionState == ConnectionState.CONNECTED) {
            Log.i(TAG, "Already connected, skipping startDiscovery")
            return
        }

        isDiscovering.set(true)
        updateState(ConnectionState.DISCOVERING, null)

        // 1. Start Hotspot / LAN Wi-Fi discovery
        startLanDiscovery()

        // 2. Start Google Nearby Connections (Bluetooth / BLE / P2P)
        startNearbyDiscovery()
    }

    override fun stopDiscovery() {
        isDiscovering.set(false)
        stopLanDiscovery()
        stopNearbyDiscovery()
        if (connectionState == ConnectionState.DISCOVERING) {
            updateState(ConnectionState.IDLE, null)
        }
    }

    // ==================== HOTSPOT / LAN IMPLEMENTATION ====================

    private fun startLanDiscovery() {
        // Start TCP Server
        executor.execute {
            try {
                // Close previous server socket if lingering
                try { serverSocket?.close() } catch (e: Exception) {}
                serverSocket = ServerSocket(TCP_PORT).apply {
                    reuseAddress = true
                    soTimeout = 2000 // 2s accept timeout so we can check isDiscovering
                }
                Log.i(TAG, "Hotspot/LAN TCP Server listening on port $TCP_PORT")

                while (isDiscovering.get() && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket?.accept() ?: continue
                        if (activeTcpSocket == null || activeTcpSocket?.isClosed == true) {
                            Log.i(TAG, "Accepted TCP connection from ${client.inetAddress.hostAddress}")
                            handleNewTcpConnection(client, "Hotspot Peer (${client.inetAddress.hostAddress})")
                        } else {
                            try { client.close() } catch (e: Exception) {}
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Normal: accept() timed out, loop around to check isDiscovering
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP Server accept loop ended: ${e.message}")
            }
        }

        // Start UDP Receiver
        executor.execute {
            try {
                if (udpSocket == null || udpSocket?.isClosed == true) {
                    udpSocket = DatagramSocket(UDP_PORT).apply {
                        broadcast = true
                        reuseAddress = true
                    }
                    Log.i(TAG, "Hotspot/LAN UDP listener started on port $UDP_PORT")
                }

                val buffer = ByteArray(1024)
                while (isDiscovering.get() && udpSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    val remoteIp = packet.address.hostAddress ?: continue

                    // Ignore own broadcasts
                    if (message.startsWith("ITANTRA_BEACON:") && !message.contains(deviceName)) {
                        val parts = message.split(":")
                        val remoteDeviceName = if (parts.size >= 2) parts[1] else "Hotspot Peer"
                        Log.i(TAG, "Discovered LAN peer via UDP beacon: $remoteDeviceName at $remoteIp")

                        // Connect TCP if not yet connected
                        if (activeTcpSocket == null || activeTcpSocket?.isClosed == true) {
                            // Leader election: device with lexicographically greater name connects
                            if (deviceName > remoteDeviceName) {
                                connectTcpToPeer(remoteIp, TCP_PORT, remoteDeviceName)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP listener closed: ${e.message}")
            }
        }

        // Start UDP Beacon Broadcaster + Gateway Prober
        executor.execute {
            while (isDiscovering.get()) {
                try {
                    // Probe Wi-Fi Gateway (if this phone joined another phone's hotspot)
                    val gatewayIp = getWifiGatewayIp()
                    if (gatewayIp != null && gatewayIp != "0.0.0.0") {
                        if (activeTcpSocket == null || activeTcpSocket?.isClosed == true) {
                            connectTcpToPeer(gatewayIp, TCP_PORT, "Hotspot Host ($gatewayIp)")
                        }
                    }

                    // Send UDP Broadcast to subnet
                    val beaconMsg = "ITANTRA_BEACON:$deviceName:$TCP_PORT"
                    val bytes = beaconMsg.toByteArray(Charsets.UTF_8)

                    // 1. Broadcast address
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    val bPacket = DatagramPacket(bytes, bytes.size, broadcastAddr, UDP_PORT)
                    udpSocket?.send(bPacket)

                    // 2. Direct to gateway if known
                    if (gatewayIp != null && gatewayIp != "0.0.0.0") {
                        try {
                            val gAddr = InetAddress.getByName(gatewayIp)
                            val gPacket = DatagramPacket(bytes, bytes.size, gAddr, UDP_PORT)
                            udpSocket?.send(gPacket)
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Beacon broadcast tick error: ${e.message}")
                }

                try { Thread.sleep(1200) } catch (e: InterruptedException) { break }
            }
        }
    }

    private fun connectTcpToPeer(hostIp: String, port: Int, peerLabel: String) {
        executor.execute {
            if (activeTcpSocket != null && activeTcpSocket?.isConnected == true) return@execute
            try {
                Log.i(TAG, "Attempting TCP connection to $hostIp:$port...")
                val socket = Socket()
                socket.connect(InetSocketAddress(hostIp, port), 2000)
                handleNewTcpConnection(socket, peerLabel)
            } catch (e: Exception) {
                Log.d(TAG, "TCP connection to $hostIp:$port failed: ${e.message}")
            }
        }
    }

    private fun handleNewTcpConnection(socket: Socket, peerLabel: String) {
        synchronized(tcpWriteLock) {
            activeTcpSocket = socket
            tcpOutStream = DataOutputStream(socket.getOutputStream())
        }
        connectedPeerName = peerLabel
        updateState(ConnectionState.CONNECTED, peerLabel)

        executor.execute {
            readTcpIncomingLoop(socket)
        }
    }

    private fun readTcpIncomingLoop(socket: Socket) {
        try {
            val inStream = DataInputStream(socket.getInputStream())
            while (!socket.isClosed && socket.isConnected) {
                val magic = inStream.readByte()
                val length = inStream.readInt()

                if (length < 0 || length > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid packet length on TCP: $length")
                    break
                }

                val payload = ByteArray(length)
                inStream.readFully(payload)
                val totalWireBytes = 1 + 4 + length

                if (magic == MAGIC_PACKET) {
                    val packet = SpeechPacket.fromCbor(payload)
                    if (packet != null) {
                        dispatchPacket(packet, totalWireBytes)
                    }
                } else if (magic == MAGIC_ACK) {
                    val ack = SpeechAck.fromCbor(payload)
                    if (ack != null) {
                        mainHandler.post { listener?.onAckReceived(ack) }
                    }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "TCP socket connection closed: ${e.message}")
        } finally {
            closeTcp()
            checkDisconnected()
        }
    }

    private fun stopLanDiscovery() {
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        try { udpSocket?.close() } catch (e: Exception) {}
        udpSocket = null
    }

    private fun closeTcp() {
        synchronized(tcpWriteLock) {
            try { tcpOutStream?.close() } catch (e: Exception) {}
            try { activeTcpSocket?.close() } catch (e: Exception) {}
            activeTcpSocket = null
            tcpOutStream = null
        }
    }

    private fun getWifiGatewayIp(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp: DhcpInfo? = wifi?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val ip = dhcp.gateway
                String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== GOOGLE NEARBY CONNECTIONS IMPLEMENTATION ====================

    @SuppressLint("MissingPermission")
    private fun startNearbyDiscovery() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            nearbyLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Nearby advertising started as: $deviceName")
        }.addOnFailureListener { e ->
            Log.w(TAG, "Nearby advertising not available: ${e.message}")
        }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            nearbyDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Nearby discovery started")
        }.addOnFailureListener { e ->
            Log.w(TAG, "Nearby discovery not available: ${e.message}")
        }
    }

    private fun stopNearbyDiscovery() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
        } catch (e: Exception) {}
    }

    private val nearbyDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Nearby found endpoint: ${info.endpointName} ($endpointId)")
            if (connectionState != ConnectionState.CONNECTED) {
                updateState(ConnectionState.CONNECTING, info.endpointName)
                connectionsClient.requestConnection(deviceName, endpointId, nearbyLifecycleCallback)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Nearby requestConnection failed: ${e.message}")
                        if (connectionState == ConnectionState.CONNECTING) {
                            updateState(ConnectionState.DISCOVERING, null)
                        }
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Nearby endpoint lost: $endpointId")
        }
    }

    private val nearbyLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Nearby connection initiated with: ${info.endpointName}")
            // Auto accept connection
            connectionsClient.acceptConnection(endpointId, nearbyPayloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Log.i(TAG, "Nearby connected successfully to $endpointId")
                nearbyEndpointId = endpointId
                updateState(ConnectionState.CONNECTED, "Nearby Peer ($endpointId)")
            } else {
                Log.w(TAG, "Nearby connection resolution status: ${result.status.statusCode}")
                if (activeTcpSocket == null) {
                    updateState(ConnectionState.DISCOVERING, null)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Nearby disconnected from: $endpointId")
            if (nearbyEndpointId == endpointId) {
                nearbyEndpointId = null
            }
            checkDisconnected()
        }
    }

    private val nearbyPayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                // Check if it's SpeechPacket or SpeechAck
                val packet = SpeechPacket.fromCbor(bytes)
                if (packet != null) {
                    dispatchPacket(packet, bytes.size)
                } else {
                    val ack = SpeechAck.fromCbor(bytes)
                    if (ack != null) {
                        mainHandler.post { listener?.onAckReceived(ack) }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ==================== PACKET DISPATCH & DUPLICATE SUPPRESSION ====================

    private fun dispatchPacket(packet: SpeechPacket, wireBytes: Int) {
        val dedupKey = "${packet.messageId}_${packet.sequence}"
        if (seenPacketIds.add(dedupKey)) {
            if (seenPacketIds.size > 500) seenPacketIds.clear()

            // Auto-send delivery ACK back
            sendAck(SpeechAck(messageId = packet.messageId, sequence = packet.sequence, status = "RECEIVED"))

            mainHandler.post {
                listener?.onPacketReceived(packet, wireBytes)
            }
        }
    }

    // ==================== UNIFIED SEND ====================

    override fun sendPacket(packet: SpeechPacket, onSent: ((Boolean, Int) -> Unit)?) {
        executor.execute {
            val cborBytes = SpeechPacket.toCbor(packet)
            var sentSuccess = false
            var totalBytesSent = 0

            // 1. Try TCP Socket (Fastest on Hotspot/LAN)
            synchronized(tcpWriteLock) {
                val out = tcpOutStream
                if (out != null && activeTcpSocket?.isConnected == true) {
                    try {
                        out.writeByte(MAGIC_PACKET.toInt())
                        out.writeInt(cborBytes.size)
                        out.write(cborBytes)
                        out.flush()
                        totalBytesSent = 1 + 4 + cborBytes.size
                        sentSuccess = true
                        Log.i(TAG, "Sent packet over Hotspot TCP: ${packet.text} (${totalBytesSent} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "TCP send failed, will try Nearby: ${e.message}")
                    }
                }
            }

            // 2. Fallback / Secondary over Google Nearby Connections
            val endpoint = nearbyEndpointId
            if (endpoint != null && (!sentSuccess || activeTcpSocket == null)) {
                try {
                    connectionsClient.sendPayload(endpoint, Payload.fromBytes(cborBytes))
                    totalBytesSent = cborBytes.size
                    sentSuccess = true
                    Log.i(TAG, "Sent packet over Google Nearby: ${packet.text} (${totalBytesSent} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Nearby send failed: ${e.message}")
                }
            }

            mainHandler.post {
                onSent?.invoke(sentSuccess, totalBytesSent)
            }
        }
    }

    override fun sendAck(ack: SpeechAck) {
        executor.execute {
            val cborBytes = SpeechAck.toCbor(ack)

            // Try TCP
            synchronized(tcpWriteLock) {
                val out = tcpOutStream
                if (out != null && activeTcpSocket?.isConnected == true) {
                    try {
                        out.writeByte(MAGIC_ACK.toInt())
                        out.writeInt(cborBytes.size)
                        out.write(cborBytes)
                        out.flush()
                    } catch (e: Exception) {}
                }
            }

            // Try Nearby
            val endpoint = nearbyEndpointId
            if (endpoint != null) {
                try {
                    connectionsClient.sendPayload(endpoint, Payload.fromBytes(cborBytes))
                } catch (e: Exception) {}
            }
        }
    }

    private fun checkDisconnected() {
        if (activeTcpSocket == null && nearbyEndpointId == null) {
            updateState(ConnectionState.DISCONNECTED, null)
            // Auto-reconnect if we were discovering
            if (isDiscovering.get()) {
                mainHandler.postDelayed({
                    if (connectionState == ConnectionState.DISCONNECTED) {
                        startDiscovery()
                    }
                }, 1500)
            }
        }
    }

    override fun disconnect() {
        closeTcp()
        try {
            nearbyEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
        } catch (e: Exception) {}
        nearbyEndpointId = null
        updateState(ConnectionState.DISCONNECTED, null)
    }

    private fun updateState(newState: ConnectionState, peerInfo: String?) {
        if (connectionState != newState || connectedPeerName != peerInfo) {
            connectionState = newState
            if (peerInfo != null) connectedPeerName = peerInfo
            Log.d(TAG, "State → $newState (peer: $peerInfo)")
            mainHandler.post {
                listener?.onConnectionStateChanged(newState, connectedPeerName)
            }
        }
    }
}
