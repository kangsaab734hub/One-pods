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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
object AirPodsController {
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    // Exact iPhone AAP Sequence for Model A3000 (Firmware 3A283)
    private val HANDSHAKE = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    private val SET_FEATURES = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xFF.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    private val REQ_NOTIFICATIONS = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
    )

    private var activeSocket: BluetoothSocket? = null

    private fun unlockHiddenApi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (_: Throwable) {}
        }
    }

    private fun getAapAncPacket(mode: String): ByteArray {
        val m: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x02
        }
        return byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m, 0x00, 0x00, 0x00)
    }

    private fun getClonePackets(mode: String): List<ByteArray> {
        val aapMode: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x02
        }
        val zeroMode: Byte = when (mode) {
            "Off" -> 0x00
            "ANC" -> 0x01
            "Transparency" -> 0x02
            else -> 0x01
        }
        return listOf(
            // Short 8-byte AAP
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, aapMode),
            // Airoha / TigerBuilder RACE ANC
            byteArrayOf(0x05, 0x5A, 0x06, 0x00, 0x06, 0x0E, 0x00, 0x0A, zeroMode, 0x01),
            // JieLi RCSP ANC
            byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x05, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x19, zeroMode, 0xEF.toByte()),
            // Bluetrum / Huilian ANC
            byteArrayOf(0xAA.toByte(), 0x55, 0x02, 0x01, aapMode, (0x03 + aapMode).toByte())
        )
    }

    private fun sendCleanAapSequence(out: OutputStream, inp: InputStream, mode: String): String {
        out.write(HANDSHAKE); out.flush()
        val r1 = toHex(readAvail(inp, 250))
        out.write(SET_FEATURES); out.flush()
        Thread.sleep(120)
        out.write(REQ_NOTIFICATIONS); out.flush()
        val r2 = toHex(readAvail(inp, 250))
        val anc11 = getAapAncPacket(mode)
        out.write(anc11); out.flush()
        Thread.sleep(150)
        out.write(anc11); out.flush()
        val r3 = toHex(readAvail(inp, 250))
        return "R1:${r1.take(10)}|R2:${r2.take(10)}|R3:${r3.take(10)}"
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
                unlockHiddenApi()
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter ?: return@Thread report("Error: No BT Adapter")
                val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
                    ?: return@Thread report("Error: BT Permission denied")

                val device = bonded.firstOrNull { d ->
                    try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch (e: Exception) { false }
                } ?: bonded.firstOrNull { d ->
                    val n = d.name?.lowercase() ?: ""
                    n.contains("kang") || n.contains("airpods")
                } ?: bonded.firstOrNull() ?: return@Thread report("Error: No Earbuds found")

                when (engine) {
                    1 -> runEngine1L2capConstructor(device, mode, ::report)
                    2 -> runEngine2RfcommAndChannels(device, mode, ::report)
                    3 -> runEngine3DirectGatt(context, device, mode, ::report)
                }
            } catch (t: Throwable) {
                report("Error: ${t.javaClass.simpleName}: ${t.message}")
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
        } catch (_: Throwable) {}
        return ByteArray(0)
    }

    private fun toHex(b: ByteArray): String =
        if (b.isEmpty()) "None" else b.joinToString("") { "%02X".format(it) }

    private fun buildRawSocket(
        device: BluetoothDevice,
        type: Int,
        secure: Boolean,
        port: Int,
        uuid: ParcelUuid?
    ): BluetoothSocket {
        val ctor = BluetoothSocket::class.java.declaredConstructors.firstOrNull { it.parameterTypes.size == 7 }
            ?: throw IllegalStateException("No 7-arg ctor")
        ctor.isAccessible = true
        return ctor.newInstance(type, -1, secure, secure, device, port, uuid) as BluetoothSocket
    }

    // ENGINE 1: Direct 7-Arg Constructor with AAP_UUID (74ec2172) & L2CAP 0x1001
    private fun runEngine1L2capConstructor(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        activeSocket?.let { s ->
            if (s.isConnected) {
                try {
                    val ancPkt = getAapAncPacket(mode)
                    s.outputStream.write(ancPkt)
                    s.outputStream.flush()
                    val r = toHex(readAvail(s.inputStream, 200))
                    report("E1 Active Sent $mode! (Rep:${r.take(16)})")
                    return
                } catch (_: Throwable) {
                    try { s.close() } catch (_: Throwable) {}
                    activeSocket = null
                }
            }
        }

        val logs = mutableListOf<String>()
        val aapParcel = ParcelUuid(AAP_UUID)

        val attempts = listOf(
            "L2_SDP_74ec" to { buildRawSocket(device, 3, false, -1, aapParcel) },
            "L2_SDP_74ec_S" to { buildRawSocket(device, 3, true, -1, aapParcel) },
            "L2_1001_UUID" to { buildRawSocket(device, 3, false, 0x1001, aapParcel) },
            "L2_1001_Sec" to { buildRawSocket(device, 3, true, 0x1001, null) },
            "L2_1001_Insec" to { buildRawSocket(device, 3, false, 0x1001, null) }
        )

        for ((label, builder) in attempts) {
            report("E1 Trying $label...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = builder()
                    sockRef = sock
                    sock.connect()
                    val rep = sendCleanAapSequence(sock.outputStream, sock.inputStream, mode)
                    activeSocket = sock
                    "OK!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${(t.cause?.message ?: t.message ?: t.javaClass.simpleName).take(22)}"
                }
            }
            try {
                val res = future.get(3800, TimeUnit.MILLISECONDS)
                logs.add("$label->$res")
                if (res.startsWith("OK!")) {
                    report("E1 SUCCESS!\n" + logs.joinToString("\n"))
                    return
                }
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("$label->TO")
                Thread.sleep(350)
            } finally {
                executor.shutdownNow()
            }
        }

        report("E1 Result:\n" + logs.joinToString("\n"))
    }

    // ENGINE 2: Clean RFCOMM Ch1, Ch2, Ch3 & fe010000 with Separate Timing
    private fun runEngine2RfcommAndChannels(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf<String>()
        val feUuid = try {
            device.uuids?.map { it.uuid }?.firstOrNull { it.toString().startsWith("fe01", true) }
        } catch (_: Throwable) { null }
        if (feUuid != null) logs.add("FE:$feUuid")

        for (ch in listOf(1, 2, 3)) {
            report("E2 Testing RFCOMM Channel $ch...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java, device, "createInsecureRfcommSocket", ch
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val out = sock.outputStream
                    val inp = sock.inputStream

                    // Step A: Clean Apple AAP first (wait 400ms to let DSP react)
                    val rep = sendCleanAapSequence(out, inp, mode)
                    Thread.sleep(400)

                    // Step B: Clone chip packets with 150ms gap
                    for (pkt in getClonePackets(mode)) {
                        out.write(pkt); out.flush()
                        Thread.sleep(150)
                    }
                    Thread.sleep(500)
                    sock.close()
                    "OPEN($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err"
                }
            }
            try {
                val res = future.get(4000, TimeUnit.MILLISECONDS)
                logs.add("Ch$ch->$res")
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("Ch$ch->TO")
            } finally {
                executor.shutdownNow()
            }
        }

        report("E2 Result:\n" + logs.joinToString("\n"))
    }

    // ENGINE 3: Direct BLE GATT on Paired Device
    private fun runEngine3DirectGatt(
        context: Context,
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        report("E3 Connecting Direct GATT to ${device.name}...")
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    report("E3 GATT Connected! Reading services...")
                    gatt.discoverServices()
                } else {
                    report("E3 GATT Disconnected (status=$status)")
                    gatt.close()
                }
            }

            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Thread {
                    try {
                        val srvs = mutableListOf<String>()
                        var wroteCount = 0
                        val pkts = listOf(HANDSHAKE, SET_FEATURES, REQ_NOTIFICATIONS, getAapAncPacket(mode)) + getClonePackets(mode)
                        for (s in gatt.services) {
                            val su = s.uuid.toString().substring(0, 8)
                            val chars = s.characteristics.map { c ->
                                val cu = c.uuid.toString().substring(0, 4)
                                val p = c.properties
                                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    for (pkt in pkts) {
                                        try {
                                            c.value = pkt
                                            gatt.writeCharacteristic(c)
                                            Thread.sleep(90)
                                        } catch (_: Throwable) {}
                                    }
                                    wroteCount++
                                    "$cu(W)"
                                } else cu
                            }
                            srvs.add("$su:[${chars.joinToString(",")}]")
                        }
                        report("E3 Wrote:$wroteCount ($mode)\n" + srvs.joinToString("\n"))
                        Thread.sleep(600)
                        gatt.close()
                    } catch (t: Throwable) {
                        report("E3 Err: ${t.message}")
                        gatt.close()
                    }
                }.start()
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}
