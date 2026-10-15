package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ParcelUuid
import android.util.Log
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

    private fun getAncPackets(mode: String): List<ByteArray> {
        val m1: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x01
        }
        val m0: Byte = (m1 - 1).toByte()
        return listOf(
            // 1. Standard Apple AAP (11-byte)
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00),
            // 2. Short Apple AAP (8-byte)
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1),
            // 3. Airoha 1562AE RACE ANC
            byteArrayOf(0x05, 0x5A, 0x05, 0x00, 0x06, 0x0E, 0x00, 0x0A, m0),
            // 4. JieLi RCSP ANC
            byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x02, 0x01, m0, 0xEF.toByte())
        )
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
                    1 -> runEngine1ChannelRadar(device, mode, ::report)
                    2 -> runEngine2DeepL2cap(device, mode, ::report)
                    3 -> runEngine3XrayUuids(device, mode, ::report)
                }
            } catch (e: Exception) {
                report("Error: ${e.message}")
            }
        }.start()
    }

    private fun readReply(inp: InputStream, waitMs: Long = 220): String {
        try {
            Thread.sleep(waitMs)
            val avail = inp.available()
            if (avail > 0) {
                val buf = ByteArray(minOf(avail, 24))
                val len = inp.read(buf)
                return buf.take(len).joinToString("") { "%02X".format(it) }
            }
        } catch (_: Exception) {}
        return ""
    }

    // ENGINE 1: Scan EVERY RFCOMM Channel (1..30) & Fire All Protocols on EVERY Open Port
    private fun runEngine1ChannelRadar(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        val openChannels = mutableListOf<String>()
        val repliedChannels = mutableListOf<String>()

        for (ch in 1..30) {
            try {
                report("E1 Scanning RFCOMM Ch $ch/30...")
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                val sock = m.invoke(device, ch) as BluetoothSocket
                sock.connect()
                openChannels.add("Ch$ch")

                val out = sock.outputStream
                val inp = sock.inputStream

                // Check if pod speaks first or after Handshake
                out.write(HANDSHAKE)
                out.flush()
                var rep = readReply(inp, 200)

                out.write(SET_FEATURES)
                out.write(REQ_NOTIFICATIONS)
                out.flush()
                if (rep.isEmpty()) rep = readReply(inp, 150)

                // Send all ANC formats
                for (pkt in getAncPackets(mode)) {
                    out.write(pkt)
                    out.flush()
                    Thread.sleep(90)
                }
                val rep2 = readReply(inp, 200)
                val fullRep = (rep + rep2).take(16)
                if (fullRep.isNotEmpty()) {
                    repliedChannels.add("Ch$ch:$fullRep")
                }

                // Keep socket open briefly so chip executes command
                Thread.sleep(300)
                sock.close()
            } catch (_: Exception) {}
        }

        if (repliedChannels.isNotEmpty()) {
            report("E1 GOT REPLY! ${repliedChannels.joinToString(", ")}")
        } else if (openChannels.isNotEmpty()) {
            report("E1 Sent on Open Ports: ${openChannels.joinToString(", ")} (No Reply)")
        } else {
            report("E1: All 1-30 RFCOMM ports closed.")
        }
    }

    // ENGINE 2: Deep L2CAP Reflection (Bypass hidden Android L2CAP restrictions for PSM 0x1001)
    private fun runEngine2DeepL2cap(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        val psms = listOf(0x1001, 0x1003, 0x0019) // Apple AAP (4097) & Control PSMs
        val results = mutableListOf<String>()

        for (psm in psms) {
            val hexPsm = "0x%04X".format(psm)
            report("E2 Trying L2CAP $hexPsm...")

            // Method A: BluetoothDevice hidden methods
            for (mName in listOf("createInsecureL2capSocket", "createL2capSocket", "createInsecureL2capChannel", "createL2capChannel")) {
                try {
                    val m = device.javaClass.getMethod(mName, Int::class.javaPrimitiveType)
                    val res = m.invoke(device, psm)
                    if (res is BluetoothSocket) {
                        res.connect()
                        val out = res.outputStream
                        val inp = res.inputStream
                        out.write(HANDSHAKE)
                        out.flush()
                        val r1 = readReply(inp, 250)
                        out.write(SET_FEATURES)
                        out.write(REQ_NOTIFICATIONS)
                        for (pkt in getAncPackets(mode)) {
                            out.write(pkt)
                            out.flush()
                            Thread.sleep(100)
                        }
                        val r2 = readReply(inp, 250)
                        Thread.sleep(400)
                        res.close()
                        report("E2 L2CAP $hexPsm CONNECTED! Reply: ${if (r1+r2 == "") "None" else r1+r2}")
                        return
                    }
                } catch (e: Exception) {
                    val err = e.cause?.message ?: e.message ?: ""
                    if (err.isNotEmpty() && results.size < 2) results.add("$mName:${err.take(18)}")
                }
            }

            // Method B: Direct BluetoothSocket Constructor Reflection (TYPE_L2CAP = 3)
            try {
                val ctor = BluetoothSocket::class.java.getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType,
                    ParcelUuid::class.java
                )
                ctor.isAccessible = true
                // TYPE_L2CAP = 3
                val sock = ctor.newInstance(3, -1, false, false, device, psm, null) as BluetoothSocket
                sock.connect()
                val out = sock.outputStream
                val inp = sock.inputStream
                out.write(HANDSHAKE)
                out.flush()
                val r1 = readReply(inp, 250)
                for (pkt in getAncPackets(mode)) {
                    out.write(pkt)
                    out.flush()
                    Thread.sleep(100)
                }
                val r2 = readReply(inp, 250)
                Thread.sleep(400)
                sock.close()
                report("E2 Direct-L2CAP $hexPsm SUCCESS! Reply: ${r1+r2}")
                return
            } catch (e: Exception) {
                val err = e.cause?.message ?: e.message ?: "blocked"
                results.add("Ctor:${err.take(22)}")
            }
        }
        report("E2 L2CAP Result: ${results.distinct().joinToString(" | ")}")
    }

    // ENGINE 3: X-Ray Device UUIDs + Test Non-Standard Device UUIDs
    private fun runEngine3XrayUuids(device: BluetoothDevice, mode: String, report: (String) -> Unit) {
        report("E3 Fetching Earbuds Secret UUIDs...")
        val uuids = device.uuids?.map { it.uuid } ?: emptyList()
        val shortList = uuids.map { it.toString().substring(0, 8) }

        val opened = mutableListOf<String>()
        // Try AAP_UUID + every custom UUID reported by the earbuds (skipping 00001101 dummy SPP if others exist)
        val targets = (listOf(AAP_UUID) + uuids.filter { !it.toString().startsWith("0000110") }).distinct()

        for (u in targets) {
            try {
                val s = device.createInsecureRfcommSocketToServiceRecord(u)
                s.connect()
                val out = s.outputStream
                val inp = s.inputStream
                out.write(HANDSHAKE)
                out.flush()
                val r1 = readReply(inp, 200)
                for (pkt in getAncPackets(mode)) {
                    out.write(pkt)
                    out.flush()
                    Thread.sleep(100)
                }
                val r2 = readReply(inp, 200)
                opened.add("${u.toString().take(8)}(${if (r1+r2=="") "NoRep" else r1+r2})")
                Thread.sleep(300)
                s.close()
            } catch (_: Exception) {}
        }

        report("UUIDs:[${shortList.joinToString(",")}] | Open:[${opened.joinToString(",")}]")
    }
}
