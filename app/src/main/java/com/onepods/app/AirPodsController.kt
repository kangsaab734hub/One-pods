package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    // Apple / Standard Bluetooth Serial Port Profile (SPP) UUID
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    fun sendNoiseControlCommand(context: Context, mode: String) {
        Thread {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter ?: return@Thread
                
                // Currently connected earbud dhoondho
                val device = adapter.bondedDevices.firstOrNull { d ->
                    try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch(e: Exception){false}
                } ?: return@Thread

                Log.d("AirPodsController", "Connecting to ${device.name} for ANC control...")

                // 1. Secret Bluetooth socket open karo
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                // 2. LibrePods style Hex Payloads (Signals)
                // Note: Yeh initial command signals hain. AirPods model ke hisaab se isme aage handshake add karna pad sakta hai.
                val payload = when (mode) {
                    "ANC" -> byteArrayOf(0x01, 0x02, 0x03)
                    "Transparency" -> byteArrayOf(0x01, 0x02, 0x04)
                    else -> byteArrayOf(0x01, 0x02, 0x01) // Off
                }

                // 3. Signal send karo
                val outputStream = socket.outputStream
                outputStream.write(payload)
                outputStream.flush()
                
                socket.close()
                Log.d("AirPodsController", "Successfully sent $mode command!")
            } catch (e: Exception) {
                Log.e("AirPodsController", "Failed to send command: ${e.message}")
            }
        }.start()
    }
}
