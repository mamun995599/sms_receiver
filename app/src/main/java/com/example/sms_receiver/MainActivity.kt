package com.example.sms_receiver

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var switchServer: Switch
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView
    private lateinit var restartButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchServer = findViewById(R.id.switchServer)
        portInput = findViewById(R.id.editPort)
        statusText = findViewById(R.id.textStatus)
        restartButton = findViewById(R.id.restartButton)

        // Set default port
        portInput.setText("8060")

        // Request SMS permissions
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 101)
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Check SMS permissions
                if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "❌ SMS permissions required", Toast.LENGTH_SHORT).show()
                    switchServer.isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 101)
                    return@setOnCheckedChangeListener
                }

                // Check notification permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "❌ Notification permission required", Toast.LENGTH_SHORT).show()
                    switchServer.isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                    return@setOnCheckedChangeListener
                }

                val port = portInput.text.toString().toIntOrNull() ?: 8060
                val intent = Intent(this, WebSocketService::class.java)
                intent.putExtra("port", port)
                startService(intent)

                val ip = getLocalIpAddress()
                statusText.text = "✅ ws://$ip:$port"
                statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                stopService(Intent(this, WebSocketService::class.java))
                statusText.text = "❌ Receiver is OFF"
                statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            }
        }

        restartButton.setOnClickListener {
            // Stop and restart the service
            stopService(Intent(this, WebSocketService::class.java))
            Thread.sleep(1000) // Wait for service to stop

            val port = portInput.text.toString().toIntOrNull() ?: 8060
            val intent = Intent(this, WebSocketService::class.java)
            intent.putExtra("port", port)
            startService(intent)

            Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show()

            // Update status
            val ip = getLocalIpAddress()
            statusText.text = "✅ ws://$ip:$port"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
        }

        // Check battery optimization
        checkBatteryOptimization()
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent()
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    grantResults[1] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ SMS permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ SMS permissions denied", Toast.LENGTH_LONG).show()
                }
            }
            102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ Notification permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Notification permission denied. Receiver may not run properly.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}