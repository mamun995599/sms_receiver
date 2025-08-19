package com.example.sms_receiver

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.java_websocket.server.WebSocketServer
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.WebSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.nio.charset.StandardCharsets
import android.net.wifi.WifiManager

class WebSocketService : Service() {
    private lateinit var server: MyWebSocketServer
    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduledFuture: ScheduledFuture<*>? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var currentPort = 8060
    private var lastNetworkChangeTime = 0L
    private var isServerRunning = false
    private var httpServer: ServerSocket? = null
    private var httpServerThread: Thread? = null
    private var shouldStopHttpServer = false

    companion object {
        var instance: WebSocketService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceNotification()
        acquireWakeLock()
        registerNetworkCallback()
        startHeartbeat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentPort = intent?.getIntExtra("port", 8060) ?: 8060
        Log.d("WebSocketService", "Starting WebSocket server on port $currentPort")

        try {
            // Start WebSocket server
            server = MyWebSocketServer(InetSocketAddress(currentPort))
            server.start()
            isServerRunning = true
            Log.d("WebSocketService", "WebSocket server started successfully on port $currentPort")

            // Start HTTP server on port + 1
            startHttpServer()
        } catch (e: Exception) {
            Log.e("WebSocketService", "Failed to start servers", e)
            isServerRunning = false
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::server.isInitialized) {
            server.stop()
            isServerRunning = false
        }
        stopHttpServer()
        instance = null
        releaseWakeLock()
        unregisterNetworkCallback()
        stopHeartbeat()
        Log.d("WebSocketService", "Servers stopped")
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

    private fun startHttpServer() {
        try {
            shouldStopHttpServer = false
            httpServer = ServerSocket(currentPort + 1)
            // Set a timeout to prevent indefinite blocking
            httpServer?.soTimeout = 5000 // 5 seconds timeout

            httpServerThread = Thread {
                while (!shouldStopHttpServer && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = httpServer?.accept()
                        if (client != null) {
                            Thread {
                                handleHttpRequest(client)
                            }.start()
                        }
                    } catch (e: SocketTimeoutException) {
                        // This is expected when the socket times out, continue the loop
                        if (!shouldStopHttpServer) {
                            Log.d("WebSocketService", "HTTP server accept timeout, continuing...")
                        }
                    } catch (e: Exception) {
                        if (!shouldStopHttpServer && !Thread.currentThread().isInterrupted) {
                            Log.e("WebSocketService", "HTTP server error", e)
                            // Wait a bit before retrying
                            try {
                                Thread.sleep(1000)
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }
                }
                Log.d("WebSocketService", "HTTP server thread stopped")
            }
            httpServerThread?.start()
            Log.d("WebSocketService", "HTTP server started on port ${currentPort + 1}")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Failed to start HTTP server", e)
        }
    }

    private fun stopHttpServer() {
        try {
            shouldStopHttpServer = true
            httpServer?.close()
            httpServerThread?.interrupt()
            // Wait for the thread to finish
            if (httpServerThread != null) {
                httpServerThread?.join(1000) // Wait up to 1 second
            }
            httpServer = null
            httpServerThread = null
            Log.d("WebSocketService", "HTTP server stopped")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error stopping HTTP server", e)
        }
    }

    private fun handleHttpRequest(client: java.net.Socket) {
        try {
            // Set socket timeout
            client.soTimeout = 10000 // 10 seconds timeout

            val input = client.getInputStream().bufferedReader()
            val output = client.getOutputStream()

            // Read the first line of the request
            val requestLine = input.readLine()
            Log.d("WebSocketService", "HTTP request: $requestLine")

            // Read headers
            var line: String?
            while (input.readLine().also { line = it } != null && line?.isNotEmpty() == true) {
                // Skip headers
            }

            // Get local IP address
            val ipAddress = getLocalIpAddress()
            // Generate HTML response with HTML entities for emojis
            val htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>SMS Receiver</title>
                    <style>
                        body { font-family: Arial, sans-serif; margin: 20px; }
                        .container { max-width: 600px; margin: 0 auto; }
                        .status { padding: 10px; background-color: #f0f0f0; border-radius: 5px; margin-bottom: 20px; }
                        .message { padding: 10px; border-left: 4px solid #2196F3; margin-bottom: 10px; background-color: #f9f9f9; }
                        .sender { font-weight: bold; color: #2196F3; }
                        .timestamp { color: #888; font-size: 0.8em; }
                        .info { background-color: #e3f2fd; padding: 10px; border-radius: 5px; margin-bottom: 20px; }
                        .btn { background-color: #4CAF50; color: white; padding: 10px 15px; border: none; border-radius: 4px; cursor: pointer; }
                        .btn:hover { background-color: #45a049; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>&#128241; SMS Receiver</h1>
                        <div class="info">
                            <p>This is a WebSocket server for receiving SMS messages.</p>
                            <p>WebSocket URL: <strong>ws://${ipAddress}:${currentPort}</strong></p>
                            <p>Connected clients: ${activeConnections.size}</p>
                        </div>
                        <div id="messages">
                            <div class="status">Waiting for SMS messages...</div>
                        </div>
                        <script>
                            // Connect to WebSocket
                            const ws = new WebSocket("ws://${ipAddress}:${currentPort}");
                            
                            ws.onopen = function(event) {
                                console.log("Connected to WebSocket server");
                                document.getElementById("messages").innerHTML += '<div class="status">&#9989; Connected to SMS Receiver</div>';
                            };
                            
                            ws.onmessage = function(event) {
                                console.log("Received message: " + event.data);
                                
                                try {
                                    const data = JSON.parse(event.data);
                                    if (data.type === "sms") {
                                        const timestamp = new Date(data.timestamp).toLocaleString();
                                        const messageHtml = 
                                            '<div class="message">' +
                                            '<div class="sender">From: ' + data.sender + '</div>' +
                                            '<div>' + data.message + '</div>' +
                                            '<div class="timestamp">' + timestamp + '</div>' +
                                            '</div>';
                                        document.getElementById("messages").innerHTML += messageHtml;
                                    } else {
                                        document.getElementById("messages").innerHTML += '<div class="status">' + event.data + '</div>';
                                    }
                                } catch (e) {
                                    document.getElementById("messages").innerHTML += '<div class="status">' + event.data + '</div>';
                                }
                            };
                            
                            ws.onclose = function(event) {
                                console.log("Disconnected from WebSocket server");
                                document.getElementById("messages").innerHTML += '<div class="status">&#10060; Disconnected from server</div>';
                            };
                            
                            ws.onerror = function(error) {
                                console.error("WebSocket error:", error);
                                document.getElementById("messages").innerHTML += '<div class="status">&#10060; Error: ' + error.message + '</div>';
                            };
                        </script>
                    </div>
                </body>
                </html>
            """.trimIndent()

            // Convert HTML content to byte array using UTF-8
            val htmlBytes = htmlContent.toByteArray(StandardCharsets.UTF_8)

            // Send HTTP response
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${htmlBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    htmlContent

            // Convert response to byte array
            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)

            // Write response
            output.write(responseBytes)
            output.flush()
            client.close()
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error handling HTTP request", e)
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                android.text.format.Formatter.formatIpAddress(wm.connectionInfo?.ipAddress ?: 0)
            } else {
                "127.0.0.1"
            }
        } catch (e: Exception) {
            "127.0.0.1"
        }
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

            val connectionsCopy = activeConnections.values.toList()

            for (conn in connectionsCopy) {
                if (conn.isOpen) {
                    try {
                        conn.send(jsonString)
                        sentCount++
                    } catch (e: Exception) {
                        Log.e("WebSocketService", "Error sending message to client", e)
                        removeConnection(conn)
                    }
                } else {
                    removeConnection(conn)
                }
            }

            Log.d("WebSocketService", "Broadcasted SMS from $sender to $sentCount connections")
        } catch (e: Exception) {
            Log.e("WebSocketService", "Error broadcasting SMS: ${e.message}")
        }
    }

    private fun removeConnection(conn: WebSocket) {
        val entry = activeConnections.entries.find { it.value == conn }
        if (entry != null) {
            activeConnections.remove(entry.key)
            Log.d("WebSocketService", "Removed closed connection: ${entry.key}")
        }
    }

    // Acquire a wake lock to keep the CPU running
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsReceiver::WebSocketWakeLock")
        wakeLock?.acquire()
        Log.d("WebSocketService", "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d("WebSocketService", "Wake lock released")
            }
        }
    }

    // Register network callback to monitor network changes
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    val now = System.currentTimeMillis()
                    // Throttle network change events to avoid too many restarts
                    if (now - lastNetworkChangeTime > 5000) { // 5 seconds throttle
                        lastNetworkChangeTime = now
                        Log.d("WebSocketService", "Network available, restarting server if needed")
                        restartServerIfNeeded()
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d("WebSocketService", "Network lost")
                }
            })
        } else {
            // For older versions, use a broadcast receiver
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkChangeTime > 5000) {
                        lastNetworkChangeTime = now
                        Log.d("WebSocketService", "Network state changed, restarting server if needed")
                        restartServerIfNeeded()
                    }
                }
            }, filter)
        }
    }

    private fun unregisterNetworkCallback() {
        // Unregister any registered receivers or callbacks here
    }

    // Restart the server if it's not running
    private fun restartServerIfNeeded() {
        if (!isServerRunning || !::server.isInitialized) {
            Log.d("WebSocketService", "Server is not running, restarting...")
            try {
                if (::server.isInitialized) {
                    server.stop()
                }
                stopHttpServer()
                server = MyWebSocketServer(InetSocketAddress(currentPort))
                server.start()
                startHttpServer()
                isServerRunning = true
                Log.d("WebSocketService", "Servers restarted successfully")
            } catch (e: Exception) {
                Log.e("WebSocketService", "Failed to restart servers", e)
                isServerRunning = false
            }
        }
    }

    // Start a heartbeat to check server health
    private fun startHeartbeat() {
        scheduledFuture = executor.scheduleAtFixedRate({
            try {
                // Check if we can bind to the port (a simple health check)
                if (!isServerRunning) {
                    Log.w("WebSocketService", "Heartbeat: Server is not running, attempting restart")
                    restartServerIfNeeded()
                } else {
                    Log.d("WebSocketService", "Heartbeat: Servers are running")
                }
            } catch (e: Exception) {
                Log.e("WebSocketService", "Heartbeat error", e)
                restartServerIfNeeded()
            }
        }, 1, 1, TimeUnit.MINUTES) // Check every minute
    }

    private fun stopHeartbeat() {
        scheduledFuture?.cancel(false)
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