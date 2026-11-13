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
import java.lang.reflect.Constructor
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

    private fun sendAapSequence(out: OutputStream, inp: InputStream, mode: String): String {
        out.write(HANDSHAKE); out.flush()
        val r1 = toHex(readAvail(inp, 250))
        out.write(SET_FEATURES); out.flush()
        Thread.sleep(120)
        out.write(REQ_NOTIFICATIONS); out.flush()
        val r2 = toHex(readAvail(inp, 250))
        val ancPkt = getAapAncPacket(mode)
        out.write(ancPkt); out.flush()
        Thread.sleep(150)
        // Send second time to ensure mode switch registers
        out.write(ancPkt); out.flush()
        val r3 = toHex(readAvail(inp, 250))
        return "R1:${r1.take(12)}|R2:${r2.take(12)}|R3:${r3.take(12)}"
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
                    2 -> runEngine2MethodsAndChannels(device, mode, ::report)
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

    @Suppress("UNCHECKED_CAST")
    private fun buildRawSocket(
        device: BluetoothDevice,
        type: Int,
        secure: Boolean,
        port: Int,
        uuid: ParcelUuid?
    ): BluetoothSocket {
        val ctors = HiddenApiBypass.getDeclaredConstructors(BluetoothSocket::class.java) as List<Constructor<*>>
        val ctor7 = ctors.firstOrNull { it.parameterTypes.size == 7 }
            ?: BluetoothSocket::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
                ParcelUuid::class.java
            )
        ctor7.isAccessible = true
        return ctor7.newInstance(type, -1, secure, secure, device, port, uuid) as BluetoothSocket
    }

    // ENGINE 1: Direct 7-Arg Constructor with AAP_UUID + L2CAP 0x1001
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

        // Test L2CAP (type=3) with AAP_UUID and with null UUID
        val attempts = listOf(
             { buildRawSocket(device, 3, true, 0x1001, aapParcel) } to "L2_1001_UUID_Sec",
            { buildRawSocket(device, 3, false, 0x1001, aapParcel) } to "L2_1001_UUID_Insec",
            { buildRawSocket(device, 3, true, 0x1001, null) } to "L2_1001_Null_Sec",
            { buildRawSocket(device, 3, false, 0x1001, null) } to "L2_1001_Null_Insec",
            { buildRawSocket(device, 3, false, -1, aapParcel) } to "L2_SDP_AAP"
        )

        for ((builder, label) in attempts) {
            report("E1 Trying $label...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = builder()
                    sockRef = sock
                    sock.connect()
                    val rep = sendAapSequence(sock.outputStream, sock.inputStream, mode)
                    activeSocket = sock
                    "OK!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${(t.cause?.message ?: t.message ?: t.javaClass.simpleName).take(22)}"
                }
            }
            try {
                val res = future.get(4000, TimeUnit.MILLISECONDS)
                logs.add("$label->$res")
                if (res.startsWith("OK!")) {
                    report("E1 SUCCESS!\n" + logs.joinToString("\n"))
                    return
                }
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("$label->TIMEOUT")
                Thread.sleep(300)
            } finally {
                executor.shutdownNow()
            }
        }

        report("E1 Result:\n" + logs.joinToString("\n"))
    }

    // ENGINE 2: Longer-Timeout L2capSocket(0x1001) + Clean RFCOMM Ch1, Ch2, Ch3
    private fun runEngine2MethodsAndChannels(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf<String>()

        // 1. Try Secure L2CAP 0x1001 first with 4.5s timeout
        for (mName in listOf("createL2capSocket", "createInsecureL2capSocket")) {
            report("E2 Waiting 4.5s for $mName(0x1001)...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java, device, mName, 0x1001
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val rep = sendAapSequence(sock.outputStream, sock.inputStream, mode)
                    activeSocket = sock
                    "OPEN!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${(t.cause?.message ?: t.message ?: "").take(20)}"
                }
            }
            try {
                val res = future.get(4500, TimeUnit.MILLISECONDS)
                logs.add("${mName.replace("create", "")}->$res")
                if (res.startsWith("OPEN!")) {
                    report("E2 Result:\n" + logs.joinToString("\n"))
                    return
                }
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("${mName.replace("create", "")}->TO")
                Thread.sleep(400)
            } finally {
                executor.shutdownNow()
            }
        }

        // 2. Test RFCOMM Ch 1, 2, 3, 5 (Skip Ch4 HFP AT port, Skip Echo loopback)
        for (ch in listOf(1, 2, 3, 5)) {
            report("E2 Trying RFCOMM Ch$ch...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java, device, "createInsecureRfcommSocket", ch
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val rep = sendAapSequence(sock.outputStream, sock.inputStream, mode)
                    // Keep socket open for 600ms so firmware executes before close
                    Thread.sleep(600)
                    sock.close()
                    "OPEN($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err"
                }
            }
            try {
                val res = future.get(3000, TimeUnit.MILLISECONDS)
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

    // ENGINE 3: Direct BLE GATT on Paired Device (No Scanner Required!)
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
                        val pkts = listOf(HANDSHAKE, SET_FEATURES, REQ_NOTIFICATIONS, getAapAncPacket(mode))
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
