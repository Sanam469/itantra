package com.itantra.app.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson

/**
 * Device-to-device text transport via Google Nearby Connections.
 *
 * Strategy: P2P_CLUSTER — both phones advertise + discover simultaneously.
 * Payload: JSON text (not audio) — a few bytes per sentence.
 *
 * Flow:
 *   1. startDiscovery() → advertises + discovers with service ID
 *   2. On discovery → auto-request connection
 *   3. On connection accepted → CONNECTED state
 *   4. sendMessage() → serializes text + lang + type to JSON, sends as BYTES payload
 *   5. On payload received → deserializes, calls listener
 */
class NearbyTransportManager(private val context: Context) {

    companion object {
        private const val TAG = "NearbyTransport"
        private const val SERVICE_ID = "com.itantra.walkie"
    }

    enum class ConnectionState {
        IDLE, DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED
    }

    interface TransportListener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onMessageReceived(text: String, language: String, isAlert: Boolean)
        fun onError(error: String)
    }

    /** Wire format for text payloads */
    data class WalkiePayload(
        val text: String,
        val lang: String,       // language the text is in: "en" or "hi"
        val sourceLang: String = "",  // language the speaker spoke in (before translation)
        val type: String,       // "message" or "alert"
        val ts: Long            // epoch ms
    )

    private var listener: TransportListener? = null
    private var state = ConnectionState.IDLE
    private var connectedEndpointId: String? = null
    private var connectedEndpointName: String? = null
    private val gson = Gson()
    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    // Unique device name for discovery
    private val deviceName = "iTantra-${android.os.Build.MODEL.take(10)}-${(1000..9999).random()}"

    fun setListener(l: TransportListener) { listener = l }
    fun getState(): ConnectionState = state
    fun getConnectedPeerName(): String? = connectedEndpointName

    /** Start advertising + discovering simultaneously */
    fun startDiscovery() {
        if (state == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connected")
            return
        }

        setState(ConnectionState.DISCOVERING)

        // Start advertising
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Advertising started as: $deviceName")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed", e)
            listener?.onError("Advertising failed: ${e.message}")
        }

        // Start discovering
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Discovery started")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed", e)
            listener?.onError("Discovery failed: ${e.message}")
        }
    }

    /** Stop advertising + discovery */
    fun stopDiscovery() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        if (state == ConnectionState.DISCOVERING) {
            setState(ConnectionState.IDLE)
        }
    }

    /** Send a text message to the connected device */
    fun sendMessage(text: String, language: String, sourceLang: String = language, isAlert: Boolean = false) {
        val endpointId = connectedEndpointId
        if (endpointId == null || state != ConnectionState.CONNECTED) {
            Log.w(TAG, "Not connected, can't send: $text")
            listener?.onError("Not connected to any device")
            return
        }

        val payload = WalkiePayload(
            text = text,
            lang = language,
            sourceLang = sourceLang,
            type = if (isAlert) "alert" else "message",
            ts = System.currentTimeMillis()
        )

        val json = gson.toJson(payload)
        val bytesPayload = Payload.fromBytes(json.toByteArray(Charsets.UTF_8))

        connectionsClient.sendPayload(endpointId, bytesPayload)
            .addOnSuccessListener {
                Log.i(TAG, "Sent: $text (${json.length} bytes)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Send failed", e)
                listener?.onError("Send failed: ${e.message}")
            }
    }

    /** Disconnect from all endpoints */
    fun disconnect() {
        connectedEndpointId?.let {
            connectionsClient.disconnectFromEndpoint(it)
        }
        connectedEndpointId = null
        connectedEndpointName = null
        setState(ConnectionState.DISCONNECTED)
    }

    fun destroy() {
        stopDiscovery()
        disconnect()
        connectionsClient.stopAllEndpoints()
    }

    // --- Discovery callback: found a nearby device ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Found device: ${info.endpointName} ($endpointId)")

            // Auto-connect to the first discovered iTantra device
            setState(ConnectionState.CONNECTING)
            connectionsClient.requestConnection(deviceName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    Log.i(TAG, "Connection requested to: ${info.endpointName}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Connection request failed", e)
                    setState(ConnectionState.DISCOVERING)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.w(TAG, "Lost endpoint: $endpointId")
        }
    }

    // --- Connection lifecycle: request → accept → connected ---

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated with: ${info.endpointName}")
            connectedEndpointName = info.endpointName
            // Auto-accept all connections (hackathon — no auth needed)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "Connected to: $endpointId")
                    connectedEndpointId = endpointId
                    // connectedEndpointName was set in onConnectionInitiated
                    setState(ConnectionState.CONNECTED)
                    // Stop discovery — we're connected
                    stopDiscovery()
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by: $endpointId")
                    setState(ConnectionState.DISCOVERING)
                }
                else -> {
                    Log.e(TAG, "Connection failed: ${result.status}")
                    setState(ConnectionState.DISCOVERING)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.w(TAG, "Disconnected from: $endpointId")
            connectedEndpointId = null
            connectedEndpointName = null
            setState(ConnectionState.DISCONNECTED)

            // Auto-reconnect: restart discovery
            Log.i(TAG, "Auto-reconnecting...")
            startDiscovery()
        }
    }

    // --- Payload callback: receive messages ---

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val json = String(bytes, Charsets.UTF_8)

                try {
                    val msg = gson.fromJson(json, WalkiePayload::class.java)
                    Log.i(TAG, "Received [${msg.lang}]: ${msg.text}")
                    listener?.onMessageReceived(msg.text, msg.lang, msg.type == "alert")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse payload: $json", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not needed for BYTES payloads (they transfer instantly)
        }
    }

    // --- Helpers ---

    private fun setState(newState: ConnectionState) {
        state = newState
        listener?.onConnectionStateChanged(newState)
        Log.d(TAG, "State → $newState")
    }
}
