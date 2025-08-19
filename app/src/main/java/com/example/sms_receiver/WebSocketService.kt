package com.example.sms_receiver

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.java_websocket.server.WebSocketServer
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.WebSocket
import java.net.InetSocketAddress
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class WebSocketService : Service() {
    private lateinit var server: MyWebSocketServer
    private val activeConnections = ConcurrentHashMap<String, WebSocket>()  // Renamed from connections

    companion object {
        var instance: WebSocketService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8060) ?: 8060
        Log.d("WebSocketService", "Starting WebSocket server on port $port")

        try {
            server = MyWebSocketServer(InetSocketAddress(port))
            server.start()
            Log.d("WebSocketService", "WebSocket server started successfully on port $port")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Failed to start WebSocket server", e)
            stopSelf() // Stop the service if we can't start the server
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        instance = null
        Log.d("WebSocketService", "WebSocket server stopped")
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sms_receiver_channel"
        val channelName = "SMS Receiver"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("📱 SMS Receiver is running")
            .setContentText("Waiting for incoming SMS messages")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .build()

        startForeground(1, notification)
    }

    // Function to broadcast SMS to all connected clients
    fun broadcastSms(sender: String, message: String, timestamp: Long) {
        try {
            val json = JSONObject()
            json.put("type", "sms")
            json.put("sender", sender)
            json.put("message", message)
            json.put("timestamp", timestamp)

            val jsonString = json.toString()
            var sentCount = 0

            // Create a copy of the connections to avoid concurrent modification issues
            val connectionsCopy = activeConnections.values.toList()

            for (conn in connectionsCopy) {
                if (conn.isOpen) {
                    try {
                        conn.send(jsonString)
                        sentCount++
                    } catch (e: Exception) {
                        Log.e("WebSocketService", "Error sending message to client", e)
                        // Remove closed connections
                        removeConnection(conn)
                    }
                } else {
                    // Remove closed connections
                    removeConnection(conn)
                }
            }

            Log.d("WebSocketService", "Broadcasted SMS from $sender to $sentCount connections")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error broadcasting SMS: ${e.message}")
        }
    }

    // Helper method to remove a connection
    private fun removeConnection(conn: WebSocket) {
        val entry = activeConnections.entries.find { it.value == conn }
        if (entry != null) {
            activeConnections.remove(entry.key)
            Log.d("WebSocketService", "Removed closed connection: ${entry.key}")
        }
    }

    inner class MyWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
            try {
                val connectionId = "${conn.remoteSocketAddress.address.hostAddress}:${conn.remoteSocketAddress.port}"
                activeConnections[connectionId] = conn
                Log.d("WebSocketServer", "New connection from: $connectionId")
                conn.send("✅ Connected to SMS Receiver")
            } catch (e: Exception) {
                Log.e("WebSocketServer", "Error adding connection", e)
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            Log.d("WebSocketServer", "Received message: $message")
            conn.send("ℹ️ SMS Receiver is listening for incoming messages")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            try {
                val connectionId = "${conn.remoteSocketAddress.address.hostAddress}:${conn.remoteSocketAddress.port}"
                activeConnections.remove(connectionId)
                Log.d("WebSocketServer", "Connection closed: $reason (code: $code)")
            } catch (e: Exception) {
                Log.e("WebSocketServer", "Error removing connection", e)
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            when (ex) {
                is java.net.BindException -> {
                    Log.e("WebSocketServer", "Port already in use", ex)
                }
                is java.net.SocketException -> {
                    Log.e("WebSocketServer", "Network error", ex)
                }
                is java.lang.SecurityException -> {
                    Log.e("WebSocketServer", "Security/permission error", ex)
                }
                else -> {
                    Log.e("WebSocketServer", "Unknown error", ex)
                    if (ex != null) {
                        Log.e("WebSocketServer", "Exception: ${ex.javaClass.simpleName}")
                        Log.e("WebSocketServer", "Message: ${ex.message}")
                    } else {
                        Log.e("WebSocketServer", "Unknown error (exception is null)")
                    }
                }
            }
        }

        override fun onStart() {
            Log.d("WebSocketServer", "Server started successfully on port: ${address.port}")
        }
    }
}