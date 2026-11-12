package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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

    // Standard Audio UUIDs to skip when scanning custom control channels
    private val AUDIO_UUID_PREFIXES = setOf(
        "0000110b", "0000110a", "0000110e", "0000110c",
        "0000111e", "00001108", "00001200", "00001800", "00001801"
    )

    // Complete 3A283 / A3000 iPhone Handshake Sequence
    private val HANDSHAKE = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    private val REQ_NOTIFICATIONS = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
    )
    private val SET_FEATURES = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xFF.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    private var activeSocket: BluetoothSocket? = null

    private fun unlockHiddenApi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (_: Throwable) {}
        }
    }

    private fun getAncPackets(mode: String): List<ByteArray> {
        val aapMode: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x02
        }
        val jlMode: Byte = when (mode) {
            "Off" -> 0x00
            "ANC" -> 0x01
            "Transparency" -> 0x02
            else -> 0x01
        }
        val airohaMode: Byte = when (mode) {
            "Off" -> 0x00
            "ANC" -> 0x01
            "Transparency" -> 0x02
            else -> 0x01
        }

        return listOf(
            // 1. Standard 11-byte Apple AAP (3A283 / A3000)
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, aapMode, 0x00, 0x00, 0x00),
            // 2. Short 8-byte Apple AAP
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, aapMode),
            // 3. Airoha / TigerBuilder RACE ANC Command
            byteArrayOf(0x05, 0x5A, 0x06, 0x00, 0x06, 0x0E, 0x00, 0x0A, airohaMode, 0x01),
            // 4. JieLi RCSP ANC Command
            byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x05, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x19, jlMode, 0xEF.toByte()),
            // 5. Huilian ANC Command
            byteArrayOf(0xAA.toByte(), 0x55, 0x02, 0x01, aapMode, (0x03 + aapMode).toByte())
        )
    }

    private fun sendFullSequence(out: OutputStream, inp: InputStream, mode: String): String {
        out.write(HANDSHAKE); out.flush()
        val r1 = toHex(readAvail(inp, 180))
        out.write(REQ_NOTIFICATIONS); out.flush()
        Thread.sleep(100)
        out.write(SET_FEATURES); out.flush()
        Thread.sleep(100)
        for (pkt in getAncPackets(mode)) {
            out.write(pkt); out.flush()
            Thread.sleep(90)
        }
        val r2 = toHex(readAvail(inp, 220))
        return "R1:$r1,R2:$r2"
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
                    1 -> runEngine1BypassNewInstance(device, mode, ::report)
                    2 -> runEngine2BypassInvokeMethod(device, mode, ::report)
                    3 -> runEngine3BleScanAndConnectGatt(context, adapter, mode, ::report)
                }
            } catch (t: Throwable) {
                report("Error: ${t.javaClass.simpleName}: ${t.message}")
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
        } catch (_: Throwable) {}
        return ByteArray(0)
    }

    private fun toHex(b: ByteArray): String =
        if (b.isEmpty()) "None" else b.joinToString("") { "%02X".format(it) }

    // ENGINE 1: L2CAP (0x1001) + Discovered Custom UUIDs + SPP RFCOMM
    private fun runEngine1BypassNewInstance(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        activeSocket?.let { s ->
            if (s.isConnected) {
                try {
                    val rep = sendFullSequence(s.outputStream, s.inputStream, mode)
                    report("E1 Active Socket Sent $mode! ($rep)")
                    return
                } catch (_: Throwable) {
                    try { s.close() } catch (_: Throwable) {}
                    activeSocket = null
                }
            }
        }

        val logs = mutableListOf<String>()
        val devUuids = try { device.uuids?.map { it.uuid } ?: emptyList() } catch (_: Throwable) { emptyList() }
        val customUuids = devUuids.filter { u ->
            val prefix = u.toString().substring(0, 8).lowercase()
            !AUDIO_UUID_PREFIXES.contains(prefix)
        }
        logs.add("UUIDs:${customUuids.map { it.toString().substring(0, 8) }}")

        // Part A: L2CAP 0x1001 & 0x1003 via HiddenApiBypass
        val l2Attempts = listOf(
            Triple("L2_1001_Insec", 3, 0x1001),
            Triple("L2_1001_Sec", -3, 0x1001),
            Triple("L2_1003_Insec", 3, 0x1003)
        )

        for ((label, typeCode, port) in l2Attempts) {
            report("E1 Trying $label...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val isSec = typeCode < 0
                    val realType = kotlin.math.abs(typeCode)
                    val sock = HiddenApiBypass.newInstance(
                        BluetoothSocket::class.java,
                        realType, -1, isSec, isSec, device, port, null
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val rep = sendFullSequence(sock.outputStream, sock.inputStream, mode)
                    activeSocket = sock
                    "OK!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${(t.cause?.message ?: t.message ?: "").take(22)}"
                }
            }
            try {
                val res = future.get(2500, TimeUnit.MILLISECONDS)
                logs.add("$label->$res")
                if (res.startsWith("OK!")) {
                    report("E1 Connected!\n" + logs.joinToString("\n"))
                    return
                }
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("$label->TIMEOUT")
            } finally {
                executor.shutdownNow()
            }
        }

        // Part B: Try all Custom UUIDs + AAP_UUID + SPP_UUID via RFCOMM
        val rfcommTargets = (customUuids + listOf(AAP_UUID, SPP_UUID)).distinct()
        for (uuid in rfcommTargets) {
            val shortU = uuid.toString().substring(0, 8)
            report("E1 Trying RFCOMM $shortU...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    sockRef = sock
                    sock.connect()
                    val rep = sendFullSequence(sock.outputStream, sock.inputStream, mode)
                    activeSocket = sock
                    "OK!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${(t.message ?: "").take(20)}"
                }
            }
            try {
                val res = future.get(2500, TimeUnit.MILLISECONDS)
                logs.add("RF_$shortU->$res")
                if (res.startsWith("OK!")) break
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("RF_$shortU->TIMEOUT")
            } finally {
                executor.shutdownNow()
            }
        }

        report("E1 Result:\n" + logs.joinToString("\n"))
    }

    // ENGINE 2: Direct RFCOMM Channel Scan (Ports 1..6) + Hidden L2cap Methods
    private fun runEngine2BypassInvokeMethod(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf<String>()

        // Try hidden L2CAP methods first
        for (mName in listOf("createInsecureL2capSocket", "createL2capSocket")) {
            report("E2 Invoking $mName(0x1001)...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java, device, mName, 0x1001
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val rep = sendFullSequence(sock.outputStream, sock.inputStream, mode)
                    activeSocket = sock
                    "OPEN!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${(t.cause?.message ?: t.message ?: "").take(22)}"
                }
            }
            try {
                val res = future.get(2500, TimeUnit.MILLISECONDS)
                logs.add("${mName.replace("create", "")}->$res")
                if (res.startsWith("OPEN!")) {
                    report("E2 Result:\n" + logs.joinToString("\n"))
                    return
                }
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("${mName.replace("create", "")}->TIMEOUT")
            } finally {
                executor.shutdownNow()
            }
        }

        // Scan Direct RFCOMM Channels 1 to 6 (Bypasses SDP UUID lookup for A3000/3A283 chips)
        for (ch in 1..6) {
            report("E2 Trying Direct RFCOMM Channel $ch...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java, device, "createInsecureRfcommSocket", ch
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val rep = sendFullSequence(sock.outputStream, sock.inputStream, mode)
                    "CH$ch:OPEN!($rep)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err"
                }
            }
            try {
                val res = future.get(2200, TimeUnit.MILLISECONDS)
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

    // ENGINE 3: BLE GATT All-Characteristic Command Writer
    private fun runEngine3BleScanAndConnectGatt(
        context: Context,
        adapter: BluetoothAdapter,
        mode: String,
        report: (String) -> Unit
    ) {
        val scanner = adapter.bluetoothLeScanner ?: return report("E3: No BLE Scanner")
        report("E3: Scanning for Mr-kang BLE...")

        var matchedResult: ScanResult? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: ""
                val mfg = result.scanRecord?.manufacturerSpecificData
                val hasApple = mfg != null && mfg.get(0x004C) != null
                if (name.contains("kang", true) || name.contains("airpods", true) || (hasApple && result.rssi > -70)) {
                    if (matchedResult == null) matchedResult = result
                }
            }
        }

        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        for (i in 1..25) { if (matchedResult != null) break; Thread.sleep(100) }
        scanner.stopScan(callback)

        val res = matchedResult ?: return report("E3: Mr-kang BLE not found (Keep Case Open!)")
        res.device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    report("E3 BLE Connected! Discovering services...")
                    gatt.discoverServices()
                } else {
                    gatt.close()
                }
            }

            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Thread {
                    try {
                        val srvs = mutableListOf<String>()
                        var wroteCount = 0
                        val allPackets = listOf(HANDSHAKE, REQ_NOTIFICATIONS, SET_FEATURES) + getAncPackets(mode)
                        for (s in gatt.services) {
                            val su = s.uuid.toString().substring(0, 8)
                            val chars = s.characteristics.map { c ->
                                val cu = c.uuid.toString().substring(0, 4)
                                val p = c.properties
                                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    for (pkt in allPackets) {
                                        try {
                                            c.value = pkt
                                            gatt.writeCharacteristic(c)
                                            Thread.sleep(75)
                                        } catch (_: Throwable) {}
                                    }
                                    wroteCount++
                                    "$cu(W)"
                                } else cu
                            }
                            srvs.add("$su:[${chars.joinToString(",")}]")
                        }
                        report("E3 Wrote:$wroteCount chars ($mode)\n" + srvs.joinToString("\n"))
                        Thread.sleep(500)
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
