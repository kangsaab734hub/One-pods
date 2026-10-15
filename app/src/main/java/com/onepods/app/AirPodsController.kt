package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    fun sendNoiseControlCommand(context: Context, mode: String, engine: Int) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        
        val device = adapter.bondedDevices.firstOrNull { d ->
            try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch(e: Exception){false}
        } ?: adapter.bondedDevices.firstOrNull() ?: return

        Log.d("AirPodsController", "Using Engine $engine to send $mode")

        when (engine) {
            1 -> sendRfcommHex(device, mode)
            2 -> sendBleGatt(context, device, mode)
            3 -> sendRfcommAT(device, mode)
        }
    }

    // ENGINE 1: Old Apple Hex Bytes
    private fun sendRfcommHex(device: BluetoothDevice, mode: String) {
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                val payload = when (mode) {
                    "ANC" -> byteArrayOf(0x01, 0x02, 0x03)
                    "Transparency" -> byteArrayOf(0x01, 0x02, 0x04)
                    else -> byteArrayOf(0x01, 0x02, 0x01)
                }
                socket.outputStream.write(payload)
                socket.outputStream.flush()
                socket.close()
            } catch (e: Exception) { Log.e("AirPodsController", "Engine 1 Failed: ${e.message}") }
        }.start()
    }

    // ENGINE 3: Clone AT Commands (Hidden Text)
    private fun sendRfcommAT(device: BluetoothDevice, mode: String) {
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                val command = when (mode) {
                    "ANC" -> "AT+ANC=1\r\n"
                    "Transparency" -> "AT+ANC=2\r\n"
                    else -> "AT+ANC=0\r\n"
                }
                socket.outputStream.write(command.toByteArray())
                socket.outputStream.flush()
                socket.close()
            } catch (e: Exception) { Log.e("AirPodsController", "Engine 3 Failed: ${e.message}") }
        }.start()
    }

    // ENGINE 2: iPhone Style BLE GATT
    private fun sendBleGatt(context: Context, device: BluetoothDevice, mode: String) {
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else {
                    gatt.close()
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                try {
                    val services = gatt.services
                    if(services.isNotEmpty()) {
                        Log.d("AirPodsController", "BLE Services Discovered. Testing connection...")
                    }
                    gatt.close()
                } catch (e: Exception) { gatt.close() }
            }
        })
    }
}
