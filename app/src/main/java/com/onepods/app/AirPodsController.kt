package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
object AirPodsController {
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    // Airoha / Clone SPP UUID
    private val AIROHA_UUID: UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")

    private var activeSocket: BluetoothSocket? = null
    private var activeChannelInfo: String = ""

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

                // Ensure we have an open, persistent RFCOMM/L2CAP connection
                val sock = getOrOpenSocket(device, ::report)
                if (sock == null) {
                    report("Failed to open any channel on ${device.name}")
                    return@Thread
                }

                val out = sock.outputStream
                val inp = sock.inputStream

                when (engine) {
                    1 -> sendAppleAAP(out, inp, mode, ::report)
                    2 -> sendAirohaRace(out, inp, mode, ::report)
                    3 -> sendCloneMultiPack(out, inp, mode, ::report)
                }

            } catch (e: Exception) {
                // Socket might have died, reset it for next tap
                try { activeSocket?.close() } catch (_: Exception) {}
                activeSocket = null
                report("Socket Reset (${e.message}). Tap again!")
            }
        }.start()
    }

    private fun getOrOpenSocket(device: BluetoothDevice, report: (String) -> Unit): BluetoothSocket? {
        activeSocket?.let {
            if (it.isConnected) return it
        }

        report("Opening persistent channel to ${device.name}...")

        // 1. Try L2CAP 0x1001 first
        for (mName in listOf("createInsecureL2capSocket", "createL2capSocket")) {
            try {
                val m = device.javaClass.getMethod(mName, Int::class.javaPrimitiveType)
                val s = m.invoke(device, 0x1001) as BluetoothSocket
                s.connect()
                activeSocket = s
                activeChannelInfo = "L2CAP-0x1001"
                performInitialHandshake(s.outputStream)
                return s
            } catch (_: Exception) {}
        }

        // 2. Try UUIDs (AAP, SPP, Airoha)
        for (uuid in listOf(AAP_UUID, SPP_UUID, AIROHA_UUID)) {
            try {
                val s = device.createInsecureRfcommSocketToServiceRecord(uuid)
                s.connect()
                activeSocket = s
                activeChannelInfo = "UUID-${uuid.toString().take(4)}"
                performInitialHandshake(s.outputStream)
                return s
            } catch (_: Exception) {}
        }

        // 3. Scan RFCOMM channels 1..15
        for (ch in 1..15) {
            try {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                val s = m.invoke(device, ch) as BluetoothSocket
                s.connect()
                activeSocket = s
                activeChannelInfo = "RFCOMM-Ch$ch"
                performInitialHandshake(s.outputStream)
                return s
            } catch (_: Exception) {}
        }

        return null
    }

    private fun performInitialHandshake(out: OutputStream) {
        try {
            out.write(HANDSHAKE)
            out.flush()
            Thread.sleep(250)
            out.write(SET_FEATURES)
            out.flush()
            Thread.sleep(150)
            out.write(REQ_NOTIFICATIONS)
            out.flush()
            Thread.sleep(200)
        } catch (_: Exception) {}
    }

    private fun readAvailableHex(inp: InputStream): String {
        return try {
            if (inp.available() > 0) {
                val buf = ByteArray(minOf(inp.available(), 32))
                val len = inp.read(buf)
                buf.take(len).joinToString(" ") { "%02X".format(it) }
            } else "No-Reply"
        } catch (e: Exception) { "Err" }
    }

    // ENGINE 1: Full Apple AAP (Keep-Alive + Multiple AAP variants)
    private fun sendAppleAAP(out: OutputStream, inp: InputStream, mode: String, report: (String) -> Unit) {
        val mByte: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x01
        }
        // Standard 11-byte AAP ANC packet
        val pkt1 = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, mByte, 0x00, 0x00, 0x00)
        // Alternate 8-byte AAP ANC packet (used by some Pro 2 firmwares)
        val pkt2 = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, mByte)

        out.write(pkt1)
        out.flush()
        Thread.sleep(150)
        out.write(pkt2)
        out.flush()
        Thread.sleep(200)

        val reply = readAvailableHex(inp)
        report("E1 [$activeChannelInfo] Sent $mode! Pod Reply: $reply")
    }

    // ENGINE 2: Airoha 1562AE / Tiger Chip RACE Protocol
    private fun sendAirohaRace(out: OutputStream, inp: InputStream, mode: String, report: (String) -> Unit) {
        // Airoha RACE ANC modes: 0x00 = Off, 0x01 = ANC, 0x02 = PassThrough (Transparency)
        val airohaMode: Byte = when (mode) {
            "Off" -> 0x00
            "ANC" -> 0x01
            "Transparency" -> 0x02
            else -> 0x00
        }
        // RACE Command format: 05 5A [LenL] [LenH] [CmdIdL] [CmdIdH] [Payload...]
        val raceAnc1 = byteArrayOf(0x05, 0x5A, 0x05, 0x00, 0x06, 0x0E, 0x00, 0x0A, airohaMode)
        val raceAnc2 = byteArrayOf(0x05, 0x5A, 0x04, 0x00, 0x06, 0x0E, 0x01, airohaMode)
        // Also send AAP with 1-based index just in case
        val aapByte: Byte = (airohaMode + 1).toByte()
        val aapPkt = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, aapByte, 0x00, 0x00, 0x00)

        for (pkt in listOf(raceAnc1, raceAnc2, aapPkt)) {
            out.write(pkt)
            out.flush()
            Thread.sleep(150)
        }

        val reply = readAvailableHex(inp)
        report("E2 [$activeChannelInfo] Airoha $mode Sent! Reply: $reply")
    }

    // ENGINE 3: JieLi / BES / Huilian Clone Command Combo
    private fun sendCloneMultiPack(out: OutputStream, inp: InputStream, mode: String, report: (String) -> Unit) {
        val m1: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x01
        }
        val m0: Byte = (m1 - 1).toByte()

        val packets = listOf(
            // Huilian / Short AAP
            byteArrayOf(0x04, 0x00, 0x03, 0x00, 0x09, 0x0D, m1),
            // JieLi RCSP ANC command
            byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x02, 0x01, m0, 0xEF.toByte()),
            // BES Vendor ANC command
            byteArrayOf(0xAA.toByte(), 0x55, 0x01, 0x03, 0x09, 0x0D, m1),
            // Re-handshake + Standard AAP
            HANDSHAKE,
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00)
        )

        for (pkt in packets) {
            out.write(pkt)
            out.flush()
            Thread.sleep(120)
        }

        val reply = readAvailableHex(inp)
        report("E3 [$activeChannelInfo] Clone Combo $mode Sent! Reply: $reply")
    }
}
