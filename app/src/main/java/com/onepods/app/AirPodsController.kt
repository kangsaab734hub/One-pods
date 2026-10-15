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
    private var persistentHfpSocket: BluetoothSocket? = null

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
                    1 -> runEngine1L2capChannelMaster(device, mode, ::report)
                    2 -> runEngine2HfpAppleInject(device, mode, ::report)
                    3 -> runEngine3FullAudit(device, ::report)
                }
            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }

    private fun readAvail(inp: InputStream, waitMs: Long = 200): ByteArray {
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

    private fun toHex(b: ByteArray): String =
        if (b.isEmpty()) "None" else b.joinToString("") { "%02X".format(it) }

    // ENGINE 1: L2CAP 0x1001 Channel Master with Handshake Injection
    private fun runEngine1L2capChannelMaster(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E1: Opening createInsecureL2capChannel(0x1001)...")
        try {
            val m = device.javaClass.getMethod("createInsecureL2capChannel", Int::class.javaPrimitiveType)
            val sock = m.invoke(device, 0x1001) as BluetoothSocket
            sock.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            val m1: Byte = when (mode) { "Off" -> 0x01; "ANC" -> 0x02; "Transparency" -> 0x03; else -> 0x01 }
            val handshake = byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            val ancPkt = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00)

            out.write(handshake); out.flush()
            val r1 = toHex(readAvail(inp, 250))
            out.write(ancPkt); out.flush()
            val r2 = toHex(readAvail(inp, 300))
            Thread.sleep(400)
            sock.close()
            report("E1 L2CAP Success!\nR1:$r1\nR2:$r2")
        } catch (e: Exception) {
            report("E1 L2CAP Err: ${e.cause?.message ?: e.message}")
        }
    }

    // ENGINE 2: Ch4 HFP + Apple XAPL Injection (Triggers Clone ANC switch via Apple protocols)
    private fun runEngine2HfpAppleInject(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E2: Injecting Apple HFP & XAPL commands on Ch4...")
        try {
            var sock = persistentHfpSocket
            if (sock == null || !sock.isConnected) {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                sock = m.invoke(device, 4) as BluetoothSocket
                sock.connect()
                persistentHfpSocket = sock
            }

            val out = sock!!.outputStream
            val inp = sock.inputStream

            // Read any pending buffer
            readAvail(inp, 100)

            val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
            val m1 = m0 + 1

            // Send complete Apple Accessory packet sequence over HFP
            val applePayloads = listOf(
                "\r\n+BRSF: 4095\r\n\r\nOK\r\n",
                "\r\n+XAPL=iPhone,7\r\n",
                "AT+XANC=$m1\r\n",
                "AT+ANC=$m0\r\n",
                "\r\n+XANC: $m1\r\n",
                "\r\n+APLSIRI: 2\r\n",
                "AT+IPHONEACCEV=3,1,${if(m0>0) 2 else 1},2,${m0*50}\r\n"
            )

            val resLog = StringBuilder()
            for (p in applePayloads) {
                out.write(p.toByteArray())
                out.flush()
                val r = readAvail(inp, 140)
                if (r.isNotEmpty()) {
                    resLog.append(String(r).replace("\r", "").replace("\n", " ").trim() + " ")
                }
            }

            report("E2 Apple Inject Sent $mode!\nReplies: ${if(resLog.isEmpty()) "None" else resLog.toString()}")
        } catch (e: Exception) {
            try { persistentHfpSocket?.close() } catch (_: Exception) {}
            persistentHfpSocket = null
            report("E2 Error: ${e.message}")
        }
    }

    // ENGINE 3: Full Earbuds Audit / Diagnostic Scanner
    private fun runEngine3FullAudit(device: BluetoothDevice, report: (String) -> Unit) {
        report("E3: Running Full Audit...")
        val sb = StringBuilder()
        sb.append("Name:${device.name}\nMAC:${device.address}\n")

        try {
            val uuids = device.uuids?.map { it.uuid.toString().take(8) } ?: emptyList()
            sb.append("UUIDs:${uuids.joinToString(",")}\n")
        } catch (_: Exception) {}

        // Check open ports
        val openPorts = mutableListOf<Int>()
        for (ch in 1..15) {
            try {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                val s = m.invoke(device, ch) as BluetoothSocket
                s.connect()
                openPorts.add(ch)
                s.close()
            } catch (_: Exception) {}
        }
        sb.append("OpenRFCOMM:[${openPorts.joinToString(",")}]")
        report(sb.toString())
    }
}
