package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val HFP_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    private val FE01_UUID: UUID = UUID.fromString("fe010000-0000-1000-8000-00805f9b34fb")

    private var persistentFe01: BluetoothSocket? = null
    private var seqNum: Int = 1

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
                    1 -> runEngine1JieLiFe01(device, mode, ::report)
                    2 -> runEngine2StepByStepHfp(device, mode, ::report)
                    3 -> runEngine3SocketInspector(device, mode, ::report)
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
                val b = ByteArray(minOf(av, 64))
                val len = inp.read(b)
                return b.take(len).toByteArray()
            }
        } catch (_: Exception) {}
        return ByteArray(0)
    }

    private fun toHex(b: ByteArray): String =
        if (b.isEmpty()) "None" else b.joinToString("") { "%02X".format(it) }

    // ENGINE 1: Dedicated JieLi (0xFE01) RCSP & Dual-Mode Protocol on Ch10
    private fun runEngine1JieLiFe01(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E1: Connecting to JieLi FE01 (Ch10)...")
        try {
            var sock = persistentFe01
            if (sock == null || !sock.isConnected) {
                sock = try {
                    val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(device, 10) as BluetoothSocket
                } catch (_: Exception) {
                    device.createInsecureRfcommSocketToServiceRecord(FE01_UUID)
                }
                sock!!.connect()
                persistentFe01 = sock
            }

            val out = sock.outputStream
            val inp = sock.inputStream
            readAvail(inp, 80) // flush old buffer

            // JieLi ANC Modes: 0x00=Off, 0x01=ANC, 0x02=Transparency
            val jlMode: Byte = when (mode) {
                "Off" -> 0x00
                "ANC" -> 0x01
                "Transparency" -> 0x02
                else -> 0x00
            }
            // Apple AAP Modes: 0x01=Off, 0x02=ANC, 0x03=Transparency
            val aapMode: Byte = (jlMode + 1).toByte()
            val seq = (seqNum++ and 0xFF).toByte()

            // Packet A: JieLi RCSP Set-Sys-Info (Cmd 0x08, Attr 0x19 = ANC Mode)
            val jlRcsp1 = byteArrayOf(
                0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(),
                0xC0.toByte(), 0x08, 0x00, 0x05,
                seq, 0xFF.toByte(), 0xFF.toByte(), 0x19, jlMode,
                0xEF.toByte()
            )
            // Packet B: JieLi RCSP Set-ANC-Info (Cmd 0x08, Mask=0x02, Type=0x01)
            val jlRcsp2 = byteArrayOf(
                0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(),
                0xC0.toByte(), 0x08, 0x00, 0x04,
                seq, 0x01, 0x19, jlMode,
                0xEF.toByte()
            )
            // Packet C: Direct AAP ANC without Handshake (in case Ch10 expects direct AAP command)
            val directAap = byteArrayOf(
                0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, aapMode, 0x00, 0x00, 0x00
            )

            out.write(jlRcsp1); out.flush()
            val r1 = toHex(readAvail(inp, 250))

            out.write(jlRcsp2); out.flush()
            val r2 = toHex(readAvail(inp, 250))

            out.write(directAap); out.flush()
            val r3 = toHex(readAvail(inp, 250))

            report("E1 FE01 Sent $mode!\nR1:$r1\nR2:$r2\nR3:$r3")
        } catch (e: Exception) {
            try { persistentFe01?.close() } catch (_: Exception) {}
            persistentFe01 = null
            report("E1 FE01 Error: ${e.message}")
        }
    }

    // ENGINE 2: Interactive HFP State-Machine on Ch4 + Apple AT+XAPL & Siri/ANC Commands
    private fun runEngine2StepByStepHfp(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E2: Starting Interactive HFP Dialog on Ch4...")
        var sock: BluetoothSocket? = null
        try {
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            sock = m.invoke(device, 4) as BluetoothSocket
            sock.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            val logBuilder = StringBuilder()

            // Step-by-step respond to up to 6 AT commands from the earbuds
            for (step in 1..6) {
                val raw = readAvail(inp, 220)
                if (raw.isEmpty()) break
                val cmd = String(raw).trim()
                logBuilder.append("[${cmd.replace("\r", "").replace("\n", " ")}] ")

                val reply = when {
                    cmd.contains("AT+BRSF") -> "\r\n+BRSF: 4095\r\n\r\nOK\r\n"
                    cmd.contains("AT+BAC") -> "\r\nOK\r\n"
                    cmd.contains("AT+CIND=?") -> "\r\n+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2)),(\"signal\",(0-5)),(\"roam\",(0,1)),(\"battchg\",(0-5))\r\n\r\nOK\r\n"
                    cmd.contains("AT+CIND?") -> "\r\n+CIND: 1,0,0,0,5,0,5\r\n\r\nOK\r\n"
                    cmd.contains("AT+CMER") -> "\r\nOK\r\n"
                    cmd.contains("AT+CHLD=?") -> "\r\n+CHLD: (0,1,2,3)\r\n\r\nOK\r\n"
                    cmd.contains("AT+XAPL") -> "\r\n+XAPL=iPhone,7\r\n\r\nOK\r\n"
                    else -> "\r\nOK\r\n"
                }
                out.write(reply.toByteArray())
                out.flush()
            }

            // Now send Apple Accessory & ANC commands over unlocked HFP session
            val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
            val m1 = m0 + 1

            val pushCmds = listOf(
                "\r\n+XAPL=iPhone,7\r\n",
                "AT+XANC=$m1\r\n",
                "\r\n+XANC: $m1\r\n",
                "\r\n+ANC: $m0\r\n",
                "AT+APANC=$m1\r\n"
            )
            var podResp = ""
            for (c in pushCmds) {
                out.write(c.toByteArray())
                out.flush()
                val r = readAvail(inp, 150)
                if (r.isNotEmpty()) podResp += String(r).trim() + " "
            }

            Thread.sleep(600)
            sock.close()
            report("E2 HFP Dialog: $logBuilder\nPodResp: ${if (podResp.isEmpty()) "Silent" else podResp}")
        } catch (e: Exception) {
            try { sock?.close() } catch (_: Exception) {}
            report("E2 Error: ${e.message}")
        }
    }

    // ENGINE 3: Inspect BluetoothSocket Constructors & Test Direct L2CAP (Type=3) + BLE L2CAP (Type=4)
    private fun runEngine3SocketInspector(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E3: Testing Direct BluetoothSocket L2CAP Constructors...")
        val outLogs = mutableListOf<String>()

        val m1: Byte = when (mode) { "Off" -> 0x01; "ANC" -> 0x02; "Transparency" -> 0x03; else -> 0x01 }
        val handshake = byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val ancPkt = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00)

        try {
            val ctors = BluetoothSocket::class.java.declaredConstructors
            outLogs.add("Ctors:${ctors.size}")

            val ctor7 = ctors.firstOrNull { it.parameterTypes.size == 7 }
            if (ctor7 != null) {
                ctor7.isAccessible = true
                // Try Type 3 (L2CAP Classic PSM 0x1001), Type 4 (L2CAP LE), Type 1 (RFCOMM AAP_UUID)
                val attempts = listOf(
                    Triple("L2CAP-1001", 3, 0x1001),
                    Triple("L2CAP-1003", 3, 0x1003),
                    Triple("RF-AAP", 1, -1)
                )
                for ((label, type, port) in attempts) {
                    try {
                        val uuidArg = if (type == 1) ParcelUuid(AAP_UUID) else null
                        val sock = ctor7.newInstance(type, -1, false, false, device, port, uuidArg) as BluetoothSocket
                        sock.connect()
                        val os = sock.outputStream
                        val ins = sock.inputStream
                        os.write(handshake); os.flush()
                        val r1 = toHex(readAvail(ins, 250))
                        os.write(ancPkt); os.flush()
                        val r2 = toHex(readAvail(ins, 250))
                        Thread.sleep(500)
                        sock.close()
                        report("E3 $label CONNECTED!\nR1:$r1\nR2:$r2")
                        return
                    } catch (e: Exception) {
                        val msg = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                        outLogs.add("$label:${msg.take(28)}")
                    }
                }
            }
        } catch (e: Exception) {
            outLogs.add("Err:${e.message}")
        }

        report("E3 Results:\n" + outLogs.joinToString("\n"))
    }
}
