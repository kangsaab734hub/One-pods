package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    // Apple Accessory Protocol (AAP) Official Secret UUID & Standard SPP UUID
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    // Exact LibrePods / Apple AAP Packets
    private val HANDSHAKE = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    private val SET_FEATURES = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xFF.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    private val REQ_NOTIFICATIONS = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
    )

    private fun getAncPacket(mode: String): ByteArray {
        val modeByte: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x01
        }
        // Exact 11-byte AAP Control Command (Opcode 0x09, Subtype 0x0D)
        return byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, modeByte, 0x00, 0x00, 0x00)
    }

    // Keep persistent socket if possible so we don't reconnect every tap
    private var activeSocket: BluetoothSocket? = null

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
            } ?: bonded.firstOrNull() ?: return report("Error: No Paired Earbuds found")

            when (engine) {
                1 -> runEngine1L2cap(device, mode, ::report)
                2 -> runEngine2RfcommScan(device, mode, ::report)
                3 -> runEngine3BleBruteforce(context, device, mode, ::report)
            }
        } catch (e: Exception) {
            report("Error: ${e.message}")
        }
    }

    private fun writeAapSequence(out: OutputStream, mode: String, isNewSocket: Boolean) {
        if (isNewSocket) {
            out.write(HANDSHAKE)
            out.flush()
            Thread.sleep(120)

            out.write(SET_FEATURES)
            out.flush()
            Thread.sleep(80)

            out.write(REQ_NOTIFICATIONS)
            out.flush()
            Thread.sleep(80)
        }
        val ancPacket = getAncPacket(mode)
        out.write(ancPacket)
        out.flush()
    }

    // ENGINE 1: LibrePods Native L2CAP Socket (PSM 0x1001 = 4097)
    private fun runEngine1L2cap(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        Thread {
            try {
                // First try reusing open socket
                activeSocket?.let { sock ->
                    if (sock.isConnected) {
                        try {
                            writeAapSequence(sock.outputStream, mode, isNewSocket = false)
                            report("E1: Sent $mode on active L2CAP!")
                            return@Thread
                        } catch (e: Exception) {
                            try { sock.close() } catch (_: Exception) {}
                            activeSocket = null
                        }
                    }
                }

                report("E1: Opening L2CAP 0x1001 on ${device.name}...")
                val psm = 0x1001 // 4097 - Apple AAP PSM
                val methodNames = listOf("createInsecureL2capSocket", "createL2capSocket")
                var connectedSock: BluetoothSocket? = null
                var lastErr = ""

                for (mName in methodNames) {
                    try {
                        val method = device.javaClass.getMethod(mName, Int::class.javaPrimitiveType)
                        val sock = method.invoke(device, psm) as BluetoothSocket
                        sock.connect()
                        connectedSock = sock
                        break
                    } catch (e: Exception) {
                        lastErr = e.cause?.message ?: e.message ?: "failed"
                    }
                }

                if (connectedSock != null) {
                    activeSocket = connectedSock
                    writeAapSequence(connectedSock.outputStream, mode, isNewSocket = true)
                    report("E1 SUCCESS: Handshake + $mode sent via L2CAP!")
                } else {
                    report("E1 L2CAP blocked ($lastErr). Try Engine 2!")
                }
            } catch (e: Exception) {
                report("E1 Failed: ${e.message}")
            }
        }.start()
    }

    // ENGINE 2: Apple AAP UUID + RFCOMM Channel 1..15 Scanner with Handshake
    private fun runEngine2RfcommScan(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        Thread {
            try {
                report("E2: Trying Apple AAP UUID...")
                val uuids = listOf(AAP_UUID, SPP_UUID)
                for (uuid in uuids) {
                    try {
                        val sock = device.createInsecureRfcommSocketToServiceRecord(uuid)
                        sock.connect()
                        writeAapSequence(sock.outputStream, mode, isNewSocket = true)
                        Thread.sleep(150)
                        sock.close()
                        report("E2 SUCCESS: Sent $mode via UUID ${uuid.toString().take(8)}!")
                        return@Thread
                    } catch (_: Exception) {}
                }

                report("E2: Scanning RFCOMM Channels 1-12...")
                for (ch in 1..12) {
                    try {
                        val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                        val sock = m.invoke(device, ch) as BluetoothSocket
                        sock.connect()
                        // Send both AAP Handshake+ANC and Clone raw packets
                        writeAapSequence(sock.outputStream, mode, isNewSocket = true)
                        Thread.sleep(150)
                        sock.close()
                        report("E2 SUCCESS: Connected & Sent $mode on Channel $ch!")
                        return@Thread
                    } catch (_: Exception) {}
                }
                report("E2: All RFCOMM channels closed. Try Engine 3!")
            } catch (e: Exception) {
                report("E2 Error: ${e.message}")
            }
        }.start()
    }

    // ENGINE 3: BLE GATT All-Characteristic Writer (AAP + Clone Payloads)
    private fun runEngine3BleBruteforce(context: Context, device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E3: Connecting BLE GATT to ${device.name}...")
        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        report("E3: BLE Connected! Discovering services...")
                        try { gatt.discoverServices() } catch (e: Exception) { gatt.close() }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    Thread {
                        try {
                            var writeCount = 0
                            val aapAnc = getAncPacket(mode)
                            val modeByte: Byte = when (mode) {
                                "Off" -> 0x01
                                "ANC" -> 0x02
                                "Transparency" -> 0x03
                                else -> 0x01
                            }
                            // Common clone BLE ANC commands (Airoha / JieLi / BES)
                            val cloneCmd1 = byteArrayOf(0x09, 0x0D, modeByte)
                            val cloneCmd2 = byteArrayOf(0xAA.toByte(), 0x0D, modeByte, 0x00)

                            for (service in gatt.services) {
                                for (char in service.characteristics) {
                                    val props = char.properties
                                    val isWritable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                                     (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                                    if (isWritable) {
                                        for (pkt in listOf(HANDSHAKE, aapAnc, cloneCmd1, cloneCmd2)) {
                                            try {
                                                char.value = pkt
                                                gatt.writeCharacteristic(char)
                                                Thread.sleep(60)
                                            } catch (_: Exception) {}
                                        }
                                        writeCount++
                                    }
                                }
                            }
                            report("E3 Done: Wrote $mode packets to $writeCount BLE characteristics!")
                            Thread.sleep(300)
                            gatt.close()
                        } catch (e: Exception) {
                            report("E3 Error: ${e.message}")
                            gatt.close()
                        }
                    }.start()
                }
            })
        } catch (e: Exception) {
            report("E3 Failed: ${e.message}")
        }
    }
}
