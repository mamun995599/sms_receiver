package com.example.sms_receiver

import android.Manifest
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var switchServer: Switch
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchServer = findViewById(R.id.switchServer)
        portInput = findViewById(R.id.editPort)
        statusText = findViewById(R.id.textStatus)

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
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        } catch (e: Exception) {
            "127.0.0.1"
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