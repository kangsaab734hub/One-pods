package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.InputStream
import java.io.OutputStream
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

    private var activeL2capSocket: BluetoothSocket? = null
    private var activeHfpSocket: BluetoothSocket? = null
    private var bypassInitialized = false

    private fun ensureHiddenApiUnlocked() {
        if (!bypassInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                bypassInitialized = true
            } catch (e: Exception) {
                Log.e("AirPodsController", "Bypass err: ${e.message}")
            }
        }
    }

    private fun getAncPacket(mode: String): ByteArray {
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
                ensureHiddenApiUnlocked()

                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter ?: return@Thread report("Error: No BT Adapter")
                val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
                    ?: return@Thread report("Error: BT Permission denied")

                val device = bonded.firstOrNull { d ->
                    try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch (e: Exception) { false }
                } ?: bonded.firstOrNull() ?: return@Thread report("Error: No Earbuds found")

                when (engine) {
                    1 -> runEngine1UnlockedL2cap(device, mode, ::report)
                    2 -> runEngine2HfpWaitXapl(device, mode, ::report)
                    3 -> runEngine3BleAutoGatt(context, device, mode, ::report)
                }
            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }

    private fun readAvail(inp: InputStream, waitMs: Long = 220): ByteArray {
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

    // ENGINE 1: HiddenApiBypass Unlocked Classic L2CAP Socket (0x1001 / 4097)
    private fun runEngine1UnlockedL2cap(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        val ancPkt = getAncPacket(mode)

        // Reuse active L2CAP socket if already connected
        activeL2capSocket?.let { s ->
            if (s.isConnected) {
                try {
                    s.outputStream.write(ancPkt)
                    s.outputStream.flush()
                    val r = toHex(readAvail(s.inputStream, 250))
                    report("E1 Active L2CAP Sent $mode!\nReply: $r")
                    return
                } catch (_: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    activeL2capSocket = null
                }
            }
        }

        report("E1: Bypassing Android 16 & Opening Classic L2CAP 0x1001...")
        val logs = mutableListOf<String>()

        // Check how many constructors are visible now after HiddenApiBypass!
        val ctors = BluetoothSocket::class.java.declaredConstructors
        logs.add("UnlockedCtors:${ctors.size}")

        // 1. Try unlocked createInsecureL2capSocket & createL2capSocket on BluetoothDevice
        for (psm in listOf(0x1001, 0x1003, 0x0019)) {
            val hexPsm = "0x%04X".format(psm)
            for (mName in listOf("createInsecureL2capSocket", "createL2capSocket")) {
                try {
                    val m = BluetoothDevice::class.java.getDeclaredMethod(mName, Int::class.javaPrimitiveType)
                    m.isAccessible = true
                    val sock = m.invoke(device, psm) as BluetoothSocket
                    sock.connect()
                    activeL2capSocket = sock

                    val out = sock.outputStream
                    val inp = sock.inputStream
                    out.write(HANDSHAKE); out.flush()
                    val r1 = toHex(readAvail(inp, 300))
                    out.write(SET_FEATURES); out.flush()
                    Thread.sleep(100)
                    out.write(REQ_NOTIFICATIONS); out.flush()
                    Thread.sleep(100)
                    out.write(ancPkt); out.flush()
                    val r2 = toHex(readAvail(inp, 300))

                    report("E1 $mName($hexPsm) CONNECTED!\nR1:$r1\nR2:$r2")
                    return
                } catch (e: Exception) {
                    val msg = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                    if (psm == 0x1001) logs.add("$mName:${msg.take(26)}")
                }
            }
        }

        // 2. Try unlocked BluetoothSocket Constructor directly with TYPE_L2CAP (3) + AAP_UUID
        try {
            val ctor = ctors.firstOrNull { it.parameterTypes.size == 7 }
            if (ctor != null) {
                ctor.isAccessible = true
                val sock = ctor.newInstance(3, -1, false, false, device, 0x1001, ParcelUuid(AAP_UUID)) as BluetoothSocket
                sock.connect()
                activeL2capSocket = sock
                val out = sock.outputStream
                val inp = sock.inputStream
                out.write(HANDSHAKE); out.flush()
                val r1 = toHex(readAvail(inp, 300))
                out.write(ancPkt); out.flush()
                val r2 = toHex(readAvail(inp, 300))
                report("E1 Ctor(L2CAP+UUID) CONNECTED!\nR1:$r1\nR2:$r2")
                return
            }
        } catch (e: Exception) {
            val msg = e.cause?.message ?: e.message ?: "err"
            logs.add("Ctor3:${msg.take(26)}")
        }

        report("E1 Result:\n" + logs.distinct().joinToString("\n"))
    }

    // ENGINE 2: Wait for Earbuds' AT+XAPL & AT+VDSF on Ch4, Unlock Apple Mode, then Switch ANC
    private fun runEngine2HfpWaitXapl(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E2: Connecting Ch4 & Waiting for XAPL...")
        try {
            var sock = activeHfpSocket
            val dialog = StringBuilder()

            if (sock == null || !sock.isConnected) {
                val m = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                sock = m.invoke(device, 4) as BluetoothSocket
                sock.connect()
                activeHfpSocket = sock

                val out = sock.outputStream
                val inp = sock.inputStream

                for (step in 1..20) {
                    val raw = readAvail(inp, 180)
                    if (raw.isEmpty()) break
                    val lines = String(raw).split("\r", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                    for (cmd in lines) {
                        dialog.append("${cmd.replace("AT+", "")} ")
                        val rep = when {
                            cmd.startsWith("AT+BRSF") -> "\r\n+BRSF: 4095\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+BAC") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CIND=?") -> "\r\n+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2)),(\"signal\",(0-5)),(\"roam\",(0,1)),(\"battchg\",(0-5))\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+CIND?") -> "\r\n+CIND: 1,0,0,0,5,0,5\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+CMER") -> "\r\nOK\r\n"
                            cmd.startsWith("AT+CHLD=?") -> "\r\n+CHLD: (0,1,2,3)\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+XAPL") -> "\r\n+XAPL=iPhone,7\r\n\r\nOK\r\n"
                            cmd.startsWith("AT+VDSF") -> "\r\n+VDSF: 1\r\n\r\nOK\r\n"
                            else -> "\r\nOK\r\n"
                        }
                        out.write(rep.toByteArray())
                        out.flush()
                    }
                }
            } else {
                dialog.append("[OpenCh4] ")
            }

            val out = sock!!.outputStream
            val inp = sock.inputStream

            val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
            val m1 = m0 + 1

            // Try Clone Vendor ATs + Raw AAP over unlocked Ch4
            val testCmds = listOf(
                "\r\n+VDSF: $m1\r\n",
                "\r\n+XANC: $m1\r\n",
                "\r\n+ANC: $m0\r\n",
                "\r\n+IPHONEACCEV: 1,9,$m0\r\n",
                "AT+VDSF=$m1\r\n",
                "AT+XANC=$m1\r\n"
            )
            val replies = mutableListOf<String>()
            for (c in testCmds) {
                out.write(c.toByteArray())
                out.flush()
                val r = readAvail(inp, 100)
                if (r.isNotEmpty()) replies.add(String(r).trim())
            }

            // Also send binary AAP ANC packet on Ch1 & Ch10 while Ch4 is unlocked!
            for (ch in listOf(1, 10)) {
                try {
                    val m = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    val s = m.invoke(device, ch) as BluetoothSocket
                    s.connect()
                    s.outputStream.write(HANDSHAKE); s.outputStream.flush(); Thread.sleep(100)
                    s.outputStream.write(SET_FEATURES); s.outputStream.flush(); Thread.sleep(100)
                    s.outputStream.write(getAncPacket(mode)); s.outputStream.flush(); Thread.sleep(150)
                    s.close()
                } catch (_: Exception) {}
            }

            report("E2 Unlocked Ch4+Ch1+Ch10 Sent $mode!\nDialog:${dialog.take(60)}\nRep:${replies.joinToString(",")}")
        } catch (e: Exception) {
            try { activeHfpSocket?.close() } catch (_: Exception) {}
            activeHfpSocket = null
            report("E2 Err: ${e.message}")
        }
    }

    // ENGINE 3: BLE GATT with Default Transport (Fixes 'Connecting BLE...' freeze)
    private fun runEngine3BleAutoGatt(context: Context, device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E3: Connecting GATT (AUTO Transport)...")
        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        report("E3 GATT Connected! Discovering services...")
                        gatt.discoverServices()
                    } else {
                        report("E3 GATT State=$newState Status=$status")
                        gatt.close()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    Thread {
                        try {
                            val srvList = mutableListOf<String>()
                            var written = 0
                            val m1: Byte = when (mode) { "Off" -> 0x01; "ANC" -> 0x02; "Transparency" -> 0x03; else -> 0x01 }
                            val m0: Byte = (m1 - 1).toByte()
                            val aapPkt = getAncPacket(mode)
                            val jlPkt = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x05, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x19, m0, 0xEF.toByte())

                            for (s in gatt.services) {
                                val sUuid = s.uuid.toString().substring(0, 8)
                                srvList.add(sUuid)
                                for (c in s.characteristics) {
                                    val props = c.properties
                                    if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                        for (pkt in listOf(HANDSHAKE, aapPkt, jlPkt)) {
                                            try {
                                                c.value = pkt
                                                gatt.writeCharacteristic(c)
                                                Thread.sleep(80)
                                            } catch (_: Exception) {}
                                        }
                                        written++
                                    }
                                }
                            }
                            report("E3 GATT Srvs:[${srvList.joinToString(",")}] Wrote:$written")
                            Thread.sleep(400)
                            gatt.close()
                        } catch (e: Exception) {
                            report("E3 Write Err: ${e.message}")
                            gatt.close()
                        }
                    }.start()
                }
            })
        } catch (e: Exception) {
            report("E3 Err: ${e.message}")
        }
    }
}
