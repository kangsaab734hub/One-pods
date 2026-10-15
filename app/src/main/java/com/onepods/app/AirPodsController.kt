package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    // Official Apple Accessory Protocol (AAP) BLE Service & Characteristic UUIDs
    private val APPLE_AAP_SERVICE: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val APPLE_AAP_CHAR: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722b")

    fun sendNoiseControlCommand(
        context: Context,
        mode: String,
        engine: Int,
        onStatus: (String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        fun report(msg: String) {
            Log.d("AirPodsController", msg)
            mainHandler.post { onStatus(msg) }
        }

        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter ?: return report("Error: No BT Adapter")
            val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
                ?: return report("Error: BT Permission denied")

            val device = bonded.firstOrNull { d ->
                try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch (e: Exception) { false }
            } ?: bonded.firstOrNull() ?: return report("Error: No Earbuds found")

            report("Connecting BLE AAP to ${device.name}...")

            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        report("BLE Connected! Discovering Apple Services...")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close()
                    }
                }

                @Suppress("DEPRECATED_RESOLUTION")
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    try {
                        val service = gatt.getService(APPLE_AAP_SERVICE)
                        if (service != null) {
                            val characteristic = service.getCharacteristic(APPLE_AAP_CHAR)
                            if (characteristic != null) {
                                val modeByte: Byte = when (mode) {
                                    "Off" -> 0x01
                                    "ANC" -> 0x02
                                    "Transparency" -> 0x03
                                    else -> 0x01
                                }

                                // Exact OpenPods/LibrePods Apple AAP Payload Structure
                                val payload = byteArrayOf(
                                    0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, modeByte, 0x00, 0x00, 0x00
                                )

                                characteristic.value = payload
                                val success = gatt.writeCharacteristic(characteristic)
                                report("Apple AAP BLE Write for $mode: ${if (success) "SUCCESS!" else "Failed"}")
                            } else {
                                report("Error: Apple AAP Characteristic not found")
                            }
                        } else {
                            // Fallback: Write to any writable characteristic found in any service
                            var written = false
                            for (s in gatt.services) {
                                for (c in s.characteristics) {
                                    val props = c.properties
                                    if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                        
                                        val mByte: Byte = when(mode) { "Off" -> 0x01; "ANC" -> 0x02; "Transparency" -> 0x03; else -> 0x01 }
                                        c.value = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, mByte, 0x00, 0x00, 0x00)
                                        if (gatt.writeCharacteristic(c)) {
                                            written = true
                                            report("Fallback BLE Write Success for $mode!")
                                            break
                                        }
                                    }
                                }
                                if (written) break
                            }
                            if (!written) report("Error: Apple BLE Service missing on this clone.")
                        }
                    } catch (e: Exception) {
                        report("BLE Error: {e.message}")
                    } finally {
                        Thread.sleep(400)
                        gatt.close()
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)

        } catch (e: Exception) {
            report("Error: ${e.message}")
        }
    }
}
