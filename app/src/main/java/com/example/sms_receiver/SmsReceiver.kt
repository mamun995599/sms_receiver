package com.example.sms_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                try {
                    val pdus = bundle.get("pdus") as Array<*>
                    val messages = arrayOfNulls<SmsMessage>(pdus.size)

                    for (i in pdus.indices) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val format = bundle.getString("format")
                            messages[i] = SmsMessage.createFromPdu(pdus[i] as ByteArray, format)
                        } else {
                            @Suppress("DEPRECATION")
                            messages[i] = SmsMessage.createFromPdu(pdus[i] as ByteArray)
                        }
                    }

                    if (messages.isNotEmpty() && messages[0] != null) {
                        val sender = messages[0]!!.originatingAddress ?: "Unknown"
                        val timestamp = messages[0]!!.timestampMillis

                        // Build the full message from all parts
                        val fullMessage = StringBuilder()
                        for (message in messages) {
                            if (message != null) {
                                fullMessage.append(message.messageBody)
                            }
                        }

                        Log.d("SmsReceiver", "SMS received from $sender: ${fullMessage.toString()}")

                        // Send to WebSocketService for broadcasting
                        WebSocketService.instance?.broadcastSms(sender, fullMessage.toString(), timestamp)
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error processing SMS: ${e.message}")
                }
            }
        }
    }
}