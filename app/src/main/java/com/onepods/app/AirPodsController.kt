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
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val HFP_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    private val FE01_UUID: UUID = UUID.fromString("fe010000-0000-1000-8000-00805f9b34fb")

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

    private fun getCleanAapAnc(mode: String): ByteArray {
        val m1: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x01
        }
        return byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00)
    }

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
                    1 -> runEngine1MetaL2cap(device, mode, ::report)
                    2 -> runEngine2HfpAtChannel(device, mode, ::report)
                    3 -> runEngine3CleanFe01(device, mode, ::report)
                }
            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }

    private fun readReplyAsciiOrHex(inp: InputStream, waitMs: Long = 250): String {
        try {
            Thread.sleep(waitMs)
            val avail = inp.available()
            if (avail > 0) {
                val buf = ByteArray(minOf(avail, 32))
                val len = inp.read(buf)
                val slice = buf.take(len).toByteArray()
                val isPrintable = slice.all { it in 32..126 || it == 13.toByte() || it == 10.toByte() }
                return if (isPrintable) {
                    String(slice).replace("\r", "").replace("\n", " ").trim()
                } else {
                    slice.joinToString("") { "%02X".format(it) }
                }
            }
        } catch (_: Exception) {}
        return "None"
    }

    // ENGINE 1: Meta-Reflection Bypass to force L2CAP 0x1001 & 74ec2172 Socket
    private fun runEngine1MetaL2cap(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E1: Bypassing Android 16 Hidden API for L2CAP 0x1001...")
        val errors = mutableListOf<String>()

        // Trick 1: Use Class.getDeclaredMethods via meta-reflection to find any socket creator
        try {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            for (mName in listOf("createL2capSocket", "createInsecureL2capSocket")) {
                try {
                    val m = getDeclaredMethod.invoke(
                        BluetoothDevice::class.java,
                        mName,
                        arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)
                    ) as Method
                    m.isAccessible = true
                    val sock = m.invoke(device, 0x1001) as BluetoothSocket
                    sock.connect()
                    val out = sock.outputStream
                    val inp = sock.inputStream
                    out.write(HANDSHAKE); out.flush()
                    val r1 = readReplyAsciiOrHex(inp, 300)
                    out.write(SET_FEATURES); out.flush()
                    Thread.sleep(100)
                    out.write(REQ_NOTIFICATIONS); out.flush()
                    Thread.sleep(100)
                    out.write(getCleanAapAnc(mode)); out.flush()
                    val r2 = readReplyAsciiOrHex(inp, 300)
                    Thread.sleep(500)
                    sock.close()
                    report("E1 L2CAP 0x1001 SUCCESS! Rep: $r1 / $r2")
                    return
                } catch (e: Exception) {
                    val cause = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
                    errors.add("$mName:$cause")
                }
            }
        } catch (e: Exception) {
            errors.add("Meta:${e.javaClass.simpleName}")
        }

        // Trick 2: Meta-reflect BluetoothSocket all constructors & try TYPE_L2CAP (3) and TYPE_L2CAP_LE (4)
        try {
            val getCtors = Class::class.java.getDeclaredMethod("getDeclaredConstructors")
            val ctors = getCtors.invoke(BluetoothSocket::class.java) as Array<*>
            for (c in ctors) {
                val ctor = c as Constructor<*>
                ctor.isAccessible = true
                val pTypes = ctor.parameterTypes
                if (pTypes.size == 7) {
                    for (type in listOf(3, 1)) { // 3 = L2CAP, 1 = RFCOMM with AAP_UUID
                        try {
                            val psmOrCh = if (type == 3) 0x1001 else -1
                            val uuidArg = if (type == 3) null else ParcelUuid(AAP_UUID)
                            val sock = ctor.newInstance(type, -1, false, false, device, psmOrCh, uuidArg) as BluetoothSocket
                            sock.connect()
                            val out = sock.outputStream
                            val inp = sock.inputStream
                            out.write(HANDSHAKE); out.flush()
                            val r1 = readReplyAsciiOrHex(inp, 300)
                            out.write(getCleanAapAnc(mode)); out.flush()
                            val r2 = readReplyAsciiOrHex(inp, 300)
                            Thread.sleep(500)
                            sock.close()
                            report("E1 Ctor(type=$type) SUCCESS! Rep: $r1 / $r2")
                            return
                        } catch (e: Exception) {
                            val msg = (e.cause?.message ?: e.message ?: "err").take(20)
                            errors.add("T$type:$msg")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("CtorMeta:${e.javaClass.simpleName}")
        }

        report("E1 Blocked: ${errors.distinct().joinToString(" | ")}")
    }

    // ENGINE 2: Ch4 (0000111e) Interactive HFP Handshake + Apple/Clone AT ANC Commands
    private fun runEngine2HfpAtChannel(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E2: Connecting to Ch4 (HFP AT Channel)...")
        var sock: BluetoothSocket? = null
        try {
            sock = try {
                device.createInsecureRfcommSocketToServiceRecord(HFP_UUID)
            } catch (_: Exception) {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 4) as BluetoothSocket
            }
            sock!!.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            // 1. Read initial AT+BRSF=... from Earbuds
            val initReq = readReplyAsciiOrHex(inp, 250)
            Log.d("AirPodsController", "Ch4 Init: $initReq")

            // 2. Complete HFP Handshake so Earbuds accept vendor commands!
            val hfpReplies = listOf(
                "\r\n+BRSF: 1023\r\n\r\nOK\r\n",
                "\r\n+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3))\r\n\r\nOK\r\n",
                "\r\n+CIND: 1,0,0\r\n\r\nOK\r\n",
                "\r\nOK\r\n"
            )
            for (rep in hfpReplies) {
                out.write(rep.toByteArray())
                out.flush()
                readReplyAsciiOrHex(inp, 120)
            }

            // 3. Now send Apple & Clone AT ANC Commands!
            val mNum = when (mode) {
                "Off" -> 0
                "ANC" -> 1
                "Transparency" -> 2
                else -> 0
            }
            val mAap = mNum + 1 // 1=Off, 2=ANC, 3=Trans

            val atCommands = listOf(
                "AT+XANC=$mNum\r\n",
                "AT+XANC=$mAap\r\n",
                "AT+ANC=$mNum\r\n",
                "AT+ANC=$mAap\r\n",
                "AT+APANC=$mAap\r\n",
                "\r\n+XANC: $mNum\r\n",
                "\r\n+ANC: $mNum\r\n"
            )

            var lastRep = ""
            for (cmd in atCommands) {
                out.write(cmd.toByteArray())
                out.flush()
                val r = readReplyAsciiOrHex(inp, 120)
                if (r != "None") lastRep = r
            }

            // Also send clean AAP binary packet after AT handshake just in case
            out.write(HANDSHAKE); out.flush(); Thread.sleep(100)
            out.write(getCleanAapAnc(mode)); out.flush()
            val rBin = readReplyAsciiOrHex(inp, 200)

            Thread.sleep(400)
            sock.close()
            report("E2 Ch4 Done! Init:[$initReq] LastRep:[$lastRep / $rBin]")
        } catch (e: Exception) {
            try { sock?.close() } catch (_: Exception) {}
            report("E2 Ch4 Error: ${e.message}")
        }
    }

    // ENGINE 3: Clean Single-Protocol Send on fe010000 (Ch10) with Proper Delays
    private fun runEngine3CleanFe01(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E3: Connecting to fe010000 (Ch10) cleanly...")
        var sock: BluetoothSocket? = null
        try {
            sock = try {
                device.createInsecureRfcommSocketToServiceRecord(FE01_UUID)
            } catch (_: Exception) {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 10) as BluetoothSocket
            }
            sock!!.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            // Clear any existing bytes
            readReplyAsciiOrHex(inp, 100)

            // Step 1: Send ONLY Handshake and wait 400ms
            out.write(HANDSHAKE)
            out.flush()
            val r1 = readReplyAsciiOrHex(inp, 400)

            // Step 2: Send ONLY Set Features and wait 300ms
            out.write(SET_FEATURES)
            out.flush()
            val r2 = readReplyAsciiOrHex(inp, 300)

            // Step 3: Send ONLY Clean 11-byte AAP ANC packet (no mixed protocols!)
            val ancPkt = getCleanAapAnc(mode)
            out.write(ancPkt)
            out.flush()
            val r3 = readReplyAsciiOrHex(inp, 500)

            // Keep socket open for 1.5 seconds so earbuds don't reset state
            Thread.sleep(1500)
            sock.close()
            report("E3 Clean fe01 Sent! R1:${r1.take(10)} R3:${r3.take(16)}")
        } catch (e: Exception) {
            try { sock?.close() } catch (_: Exception) {}
            report("E3 fe01 Error: ${e.message}")
        }
    }
}
