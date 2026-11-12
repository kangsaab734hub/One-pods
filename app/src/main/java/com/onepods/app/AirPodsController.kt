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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private fun unlockHiddenApi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (_: Throwable) {}
        }
    }

    private fun getAncPackets(mode: String): List<ByteArray> {
        val m1: Byte = when (mode) {
            "Off" -> 0x01
            "ANC" -> 0x02
            "Transparency" -> 0x03
            else -> 0x02
        }
        return listOf(
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00),
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1)
        )
    }

    fun saveLastMode(context: Context, mode: String) {
        context.getSharedPreferences("airpods_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_mode", mode).apply()
    }

    fun getLastMode(context: Context): String {
        return context.getSharedPreferences("airpods_prefs", Context.MODE_PRIVATE)
            .getString("last_mode", "ANC") ?: "ANC"
    }

    fun sendNoiseControlCommand(
        context: Context,
        mode: String,
        engine: Int,
        onStatus: (String) -> Unit
    ) {
        saveLastMode(context, mode)
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

    private fun runEngine1BypassNewInstance(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        activeL2capSocket?.let { s ->
            if (s.isConnected) {
                try {
                    for (pkt in getAncPackets(mode)) {
                        s.outputStream.write(pkt)
                        s.outputStream.flush()
                        Thread.sleep(80)
                    }
                    val r = toHex(readAvail(s.inputStream, 250))
                    report("E1 Active Socket Sent $mode! Rep:$r")
                    return
                } catch (_: Throwable) {
                    try { s.close() } catch (_: Throwable) {}
                    activeL2capSocket = null
                }
            }
        }

        val logs = mutableListOf<String>()
        val attempts = listOf(
            Triple("L2_1001_Insec", 3, 0x1001),
            Triple("L2_1001_Sec", -3, 0x1001),
            Triple("RF_AAP", 1, -1)
        )

        for ((label, typeCode, port) in attempts) {
            report("E1 Trying $label...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null

            val future = executor.submit<String> {
                try {
                    val isSec = typeCode < 0
                    val realType = kotlin.math.abs(typeCode)
                    val uuidArg = if (realType == 1) ParcelUuid(AAP_UUID) else null

                    val sock = HiddenApiBypass.newInstance(
                        BluetoothSocket::class.java,
                        realType, -1, isSec, isSec, device, port, uuidArg
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()

                    val out = sock.outputStream
                    val inp = sock.inputStream
                    out.write(HANDSHAKE); out.flush()
                    val r1 = toHex(readAvail(inp, 250))
                    out.write(SET_FEATURES); out.flush()
                    for (pkt in getAncPackets(mode)) {
                        out.write(pkt); out.flush()
                        Thread.sleep(80)
                    }
                    val r2 = toHex(readAvail(inp, 250))
                    activeL2capSocket = sock
                    "CONNECTED!(R1:$r1,R2:$r2)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${t.message?.take(28)}"
                }
            }

            try {
                val res = future.get(3000, TimeUnit.MILLISECONDS)
                logs.add("$label->$res")
                if (res.startsWith("CONNECTED")) break
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("$label->TIMEOUT")
            } finally {
                executor.shutdownNow()
            }
        }
        report("E1 Result:\n" + logs.joinToString("\n"))
    }

    private fun runEngine2BypassInvokeMethod(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf<String>()
        for (mName in listOf("createInsecureL2capSocket", "createL2capSocket")) {
            report("E2 Invoking $mName...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java, device, mName, 0x1001
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()
                    val out = sock.outputStream
                    val inp = sock.inputStream
                    out.write(HANDSHAKE); out.flush()
                    for (pkt in getAncPackets(mode)) {
                        out.write(pkt); out.flush()
                        Thread.sleep(80)
                    }
                    activeL2capSocket = sock
                    "OPEN!"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    "Err:${t.message?.take(26)}"
                }
            }
            try {
                val res = future.get(2800, TimeUnit.MILLISECONDS)
                logs.add("$mName->$res")
                if (res.startsWith("OPEN")) break
            } catch (_: Throwable) {
                future.cancel(true)
                logs.add("$mName->TIMEOUT")
            } finally {
                executor.shutdownNow()
            }
        }
        report("E2 Result:\n" + logs.joinToString("\n"))
    }

    private fun runEngine3BleScanAndConnectGatt(
        context: Context,
        adapter: BluetoothAdapter,
        mode: String,
        report: (String) -> Unit
    ) {
        val scanner = adapter.bluetoothLeScanner ?: return report("E3: No BLE Scanner")
        report("E3: Scanning for BLE...")

        var matchedResult: ScanResult? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: ""
                if (name.contains("airpods", true) || name.contains("kang", true) || matchedResult == null) {
                    if (matchedResult == null) matchedResult = result
                }
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        for (i in 1..20) { if (matchedResult != null) break; Thread.sleep(100) }
        scanner.stopScan(callback)

        val res = matchedResult ?: return report("E3: BLE device not found")
        res.device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else gatt.close()
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Thread {
                    try {
                        for (s in gatt.services) {
                            for (c in s.characteristics) {
                                val p = c.properties
                                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    for (pkt in getAncPackets(mode)) {
                                        try { c.value = pkt; gatt.writeCharacteristic(c); Thread.sleep(60) } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                        report("E3 BLE Command Sent ($mode)!")
                        Thread.sleep(400); gatt.close()
                    } catch (t: Throwable) { report("E3 Err: ${t.message}"); gatt.close() }
                }.start()
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}
