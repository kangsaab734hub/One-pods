package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

@SuppressLint("MissingPermission")
object AirPodsController {
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

        Thread {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter ?: return@Thread report("Error: No BT Adapter")
                val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
                    ?: return@Thread report("Error: BT Permission denied")

                val device = bonded.firstOrNull { d ->
                    try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch (e: Exception) { false }
                } ?: bonded.firstOrNull() ?: return@Thread report("Error: No Earbuds found")

                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                val sock = m.invoke(device, 10) as BluetoothSocket
                sock.connect()
                val out = sock.outputStream
                val inp = sock.inputStream

                val jlMode: Byte = when (mode) {
                    "Off" -> 0x00
                    "ANC" -> 0x01
                    "Transparency" -> 0x02
                    else -> 0x00
                }

                // JieLi RCSP Command + Action Confirm Byte (0x01 to apply immediately)
                val packet = byteArrayOf(
                    0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(),
                    0x09, 0x00, 0x05, 0x02, 0xFF.toByte(), 0xFF.toByte(), 
                    0x19, jlMode, 0x01, 0xEF.toByte()
                )

                out.write(packet)
                out.flush()
                Thread.sleep(200)

                sock.close()
                report("Applied $mode with Confirm Byte!")
            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }
}
