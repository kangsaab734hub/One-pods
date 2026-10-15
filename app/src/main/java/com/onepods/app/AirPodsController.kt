package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.io.OutputStream

@SuppressLint("MissingPermission")
object AirPodsController {
    private var persistentSocket: BluetoothSocket? = null

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

                when (engine) {
                    1 -> runEngine1Channel1(device, mode, ::report)
                    2 -> runEngine2Channel10(device, mode, ::report)
                    3 -> runEngine3FullAudit(device, ::report)
                }
            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }

    private fun readAvail(inp: InputStream, waitMs: Long = 180): ByteArray {
        try {
            Thread.sleep(waitMs)
            val av = inp.available()
            if (av > 0) {
                val b = ByteArray(minOf(av, 128))
                val len = inp.read(b)
                return b.take(len).toByteArray()
            }
        } catch (_: Exception) {}
        return ByteArray(0)
    }

    // ENGINE 1: Target Open RFCOMM Channel 1 (Direct Apple/Clone Injection)
    private fun runEngine1Channel1(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E1: Targeting Open Port Ch1...")
        try {
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            val sock = m.invoke(device, 1) as BluetoothSocket
            sock.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
            val m1 = m0 + 1

            val payloads = listOf(
                "\r\n+XAPL=iPhone,7\r\n",
                "AT+XANC=$m1\r\n",
                "AT+ANC=$m0\r\n",
                "\r\n+XANC: $m1\r\n"
            )
            val resp = StringBuilder()
            for (p in payloads) {
                out.write(p.toByteArray())
                out.flush()
                val r = readAvail(inp, 120)
                if (r.isNotEmpty()) resp.append(String(r).replace("\r", "").replace("\n", " ").trim() + " ")
            }
            Thread.sleep(300)
            sock.close()
            report("E1 Ch1 Done! Resp: ${if(resp.isEmpty()) "None" else resp.toString()}")
        } catch (e: Exception) {
            report("E1 Ch1 Err: ${e.message}")
        }
    }

    // ENGINE 2: Target Open RFCOMM Channel 10 (JieLi FE01 Port + Apple Binary/AT Hybrid)
    private fun runEngine2Channel10(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E2: Targeting Open Port Ch10 (FE01)...")
        try {
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            val sock = m.invoke(device, 10) as BluetoothSocket
            sock.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            val jlMode: Byte = when (mode) { "Off" -> 0x00; "ANC" -> 0x01; "Transparency" -> 0x02; else -> 0x00 }
            val seq = 0x05.toByte()

            // JieLi RCSP + Apple Binary Hybrid Packet
            val jlPacket = byteArrayOf(
                0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(),
                0x08, 0x00, 0x05, seq, 0xFF.toByte(), 0xFF.toByte(), 0x19, jlMode, 0xEF.toByte()
            )
            val aapPacket = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, (jlMode + 1).toByte(), 0x00, 0x00, 0x00)

            out.write(jlPacket); out.flush()
            val r1 = readAvail(inp, 150)
            out.write(aapPacket); out.flush()
            val r2 = readAvail(inp, 150)

            Thread.sleep(300)
            sock.close()
            report("E2 Ch10 Done! R1:${r1.size}bytes R2:${r2.size}bytes")
        } catch (e: Exception) {
            report("E2 Ch10 Err: ${e.message}")
        }
    }

    // ENGINE 3: Full Diagnostic Audit Info Display
    private fun runEngine3FullAudit(device: BluetoothDevice, report: (String) -> Unit) {
        val sb = StringBuilder()
        sb.append("Name:${device.name}\nMAC:${device.address}\n")
        try {
            val uuids = device.uuids?.map { it.uuid.toString().take(8) } ?: emptyList()
            sb.append("UUIDs:${uuids.joinToString(",")}\n")
        } catch (_: Exception) {}
        sb.append("Active Open Ports: Ch1 & Ch10")
        report(sb.toString())
    }
}
