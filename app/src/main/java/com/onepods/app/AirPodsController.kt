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
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val HFP_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

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

                report("Connecting to ${device.name} Classic RFCOMM...")

                // Connect to HFP / SPP Port directly
                val sock = device.createInsecureRfcommSocketToServiceRecord(HFP_UUID)
                sock.connect()
                val out = sock.outputStream
                val inp = sock.inputStream

                val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
                val m1 = m0 + 1

                // 1. Send complete iPhone AG Handshake sequence
                val initSeq = listOf(
                    "\r\n+BRSF: 4095\r\n",
                    "\r\n+XAPL=iPhone,7\r\n",
                    "AT+XANC=$m1\r\n",
                    "AT+ANC=$m0\r\n"
                )

                for (cmd in initSeq) {
                    out.write(cmd.toByteArray())
                    out.flush()
                    Thread.sleep(100)
                }

                // 2. Read what earbuds say back
                Thread.sleep(250)
                val av = inp.available()
                val resp = if (av > 0) {
                    val b = ByteArray(av)
                    val len = inp.read(b)
                    String(b.take(len).toByteArray()).replace("\r", "").replace("\n", " ").trim()
                } else {
                    "No-Reply"
                }

                sock.close()
                report("Sent $mode via HFP! Pod Reply: $resp")

            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }
}
