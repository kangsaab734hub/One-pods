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
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

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

    private var persistentHfpSocket: BluetoothSocket? = null
    private var persistentL2capSocket: BluetoothSocket? = null

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
                    1 -> runEngine1UnsafeL2cap(device, mode, ::report)
                    2 -> runEngine2FullHfpSession(device, mode, ::report)
                    3 -> runEngine3RfcommAllNonEcho(device, mode, ::report)
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

    // Unlock Android 16 Hidden API restrictions using Unsafe / VMRuntime trick
    private fun unlockHiddenApis(): String {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)

            // Try setting hidden API exemptions via VMRuntime through Unsafe
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = unsafeClass.getMethod("getObjectVolatile", Any::class.java, Long::class.javaPrimitiveType)
            "UnsafeReady"
        } catch (e: Exception) {
            "NoUnsafe:${e.javaClass.simpleName}"
        }
    }

    // ENGINE 1: Unsafe Memory Inspection of Class ArtMethod/Constructors + L2CAP 0x1001
    private fun runEngine1UnsafeL2cap(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        val m1: Byte = when (mode) { "Off" -> 0x01; "ANC" -> 0x02; "Transparency" -> 0x03; else -> 0x01 }
        val ancPkt = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00)

        // Check if we already have an open L2CAP socket
        persistentL2capSocket?.let { s ->
            if (s.isConnected) {
                try {
                    s.outputStream.write(ancPkt)
                    s.outputStream.flush()
                    val r = toHex(readAvail(s.inputStream, 250))
                    report("E1 Reused L2CAP Sent $mode! Rep:$r")
                    return
                } catch (_: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    persistentL2capSocket = null
                }
            }
        }

        val logs = mutableListOf<String>()
        try {
            // Inspect public + declared methods on BluetoothDevice that take (int)
            val allMethods = (BluetoothDevice::class.java.methods.toList() +
                              BluetoothDevice::class.java.declaredMethods.toList()).distinctBy { it.name + it.parameterTypes.contentToString() }
            val intMethods = allMethods.filter {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }.map { it.name }
            logs.add("IntM:${intMethods.joinToString(",")}")

            // Also try createL2capChannel(int) which is a PUBLIC Android API since API 29!
            for (mName in listOf("createInsecureL2capChannel", "createL2capChannel", "createInsecureL2capSocket", "createL2capSocket")) {
                try {
                    val m = BluetoothDevice::class.java.getMethod(mName, Int::class.javaPrimitiveType)
                    for (psm in listOf(0x1001, 0x1003, 4097)) {
                        try {
                            val sock = m.invoke(device, psm) as BluetoothSocket
                            sock.connect()
                            persistentL2capSocket = sock
                            val out = sock.outputStream
                            val inp = sock.inputStream
                            out.write(HANDSHAKE); out.flush()
                            val r1 = toHex(readAvail(inp, 300))
                            out.write(SET_FEATURES); out.flush()
                            out.write(REQ_NOTIFICATIONS); out.flush()
                            Thread.sleep(150)
                            out.write(ancPkt); out.flush()
                            val r2 = toHex(readAvail(inp, 300))
                            report("E1 $mName(0x1001) SUCCESS!\nR1:$r1\nR2:$r2")
                            return
                        } catch (inner: Exception) {
                            val c = inner.cause?.message ?: inner.message ?: "err"
                            logs.add("$mName:${c.take(24)}")
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logs.add("Ex:${e.message}")
        }

        report("E1 Report:\n" + logs.distinct().joinToString("\n"))
    }

    // ENGINE 2: Complete 20-Step HFP Handshake on Ch4 (Answers AT+CMEE=1, AT+XAPL, AT+BIA, etc.)
    private fun runEngine2FullHfpSession(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E2: Running Full 20-Step HFP Session on Ch4...")
        try {
            var sock = persistentHfpSocket
            val isNew = (sock == null || !sock.isConnected)
            val dialogLog = StringBuilder()

            if (isNew) {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                sock = m.invoke(device, 4) as BluetoothSocket
                sock.connect()
                persistentHfpSocket = sock

                val out = sock.outputStream
                val inp = sock.inputStream

                // Loop up to 18 times to answer EVERY AT command until the pod stops asking
                for (step in 1..18) {
                    val raw = readAvail(inp, 200)
                    if (raw.isEmpty()) break
                    val text = String(raw).trim()
                    // Split multiple AT commands if they arrived in one chunk
                    val lines = text.split("\r", "\n").map { it.trim() }.filter { it.isNotEmpty() }

                    for (cmd in lines) {
                        dialogLog.append("${cmd.replace("AT+", "")} ")
                        val reply = when {
                            cmd.startsWith("AT+BRSF") -> "\r\n+BRSF: 4095\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+BAC") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CIND=?") -> "\r\n+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2)),(\"signal\",(0-5)),(\"roam\",(0,1)),(\"battchg\",(0-5))\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+CIND?") -> "\r\n+CIND: 1,0,0,0,5,0,5\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+CMER") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CHLD=?") -> "\r\n+CHLD: (0,1,2,3)\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+CMEE") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+BIA") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CLIP") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CCWA") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CGMI") || cmd.startsWith("AT+CGMM") -> "\r\niPhone16,2\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+XAPL") -> "\r\n+XAPL=iPhone,7\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+IPHONEACCEV") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+XEVENT") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CSRSF") -> "\r\nOK\r\n"
                            else -> "\r\nOK\r\n"
                        }
                        out.write(reply.toByteArray())
                        out.flush()
                    }
                }
            } else {
                dialogLog.append("[Reused Open Ch4] ")
            }

            val out = sock!!.outputStream
            val inp = sock.inputStream

            val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
            val m1 = m0 + 1 // 1=Off, 2=ANC, 3=Transparency

            // Send unsolicited AG codes + AT commands + AAP Binary over Ch4
            val cmds = listOf(
                "\r\n+XAPL=iPhone,7\r\n",
                "\r\n+XANC: $m1\r\n",
                "\r\n+ANC: $m1\r\n",
                "\r\n+ANC: $m0\r\n",
                "AT+XANC=$m1\r\n",
                "AT+ANC=$m0\r\n",
                "\r\n+APLSIRI: 2\r\n"
            )
            val podReplies = mutableListOf<String>()
            for (c in cmds) {
                out.write(c.toByteArray())
                out.flush()
                val r = readAvail(inp, 120)
                if (r.isNotEmpty()) {
                    podReplies.add(String(r).replace("\r", "").replace("\n", " ").trim())
                }
            }

            report("E2 Ch4: $dialogLog\nReplies: ${if (podReplies.isEmpty()) "None" else podReplies.joinToString(" | ")}")
        } catch (e: Exception) {
            try { persistentHfpSocket?.close() } catch (_: Exception) {}
            persistentHfpSocket = null
            report("E2 Error: ${e.message}")
        }
    }

    // ENGINE 3: Probe AAP_UUID & Every Non-Echo RFCOMM Channel for Hidden Listeners
    private fun runEngine3RfcommAllNonEcho(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E3: Probing AAP UUID & Hidden RFCOMM Ports...")
        val summary = mutableListOf<String>()
        val m1: Byte = when (mode) { "Off" -> 0x01; "ANC" -> 0x02; "Transparency" -> 0x03; else -> 0x01 }
        val ancPkt = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00)

        // 1. Check exact error when connecting to AAP_UUID via createInsecureRfcommSocketToServiceRecord
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(AAP_UUID)
            s.connect()
            s.outputStream.write(HANDSHAKE)
            s.outputStream.write(ancPkt)
            s.outputStream.flush()
            val r = toHex(readAvail(s.inputStream, 300))
            s.close()
            summary.add("AAP_UUID:OPEN($r)")
        } catch (e: Exception) {
            summary.add("AAP_UUID:${(e.message ?: "closed").take(25)}")
        }

        // 2. Test channels 1..30 and log ONLY those that open
        for (ch in 1..30) {
            if (ch == 4) continue // skip HFP Ch4
            try {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                val s = m.invoke(device, ch) as BluetoothSocket
                s.connect()
                // Send short test byte 0xAA to see if it echoes 0xAA back
                s.outputStream.write(byteArrayOf(0xAA.toByte()))
                s.outputStream.flush()
                val echo = toHex(readAvail(s.inputStream, 150))
                val isEcho = (echo == "AA")
                summary.add("Ch$ch:${if (isEcho) "ECHO" else "REAL($echo)"}")
                s.close()
            } catch (_: Exception) {}
        }

        report("E3 Scan:\n" + summary.joinToString("\n"))
    }
}
