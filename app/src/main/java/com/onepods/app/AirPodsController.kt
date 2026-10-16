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
            else -> 0x01
        }
        val m0: Byte = (m1 - 1).toByte()
        return listOf(
            // Standard 11-byte Apple AAP ANC
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1, 0x00, 0x00, 0x00),
            // Short 8-byte Apple AAP ANC
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, m1),
            // JieLi RCSP ANC
            byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x05, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x19, m0, 0xEF.toByte())
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

    // ENGINE 1: Use HiddenApiBypass.newInstance() to construct BluetoothSocket(TYPE_L2CAP = 3)
    private fun runEngine1BypassNewInstance(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        // First check if active socket is already open
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

        // Inspect exact parameter types of all 7 constructors so we call the right signature
        val ctors = HiddenApiBypass.getDeclaredConstructors(BluetoothSocket::class.java)
        val sigs = ctors.map { c ->
            (c as java.lang.reflect.Constructor<*>).parameterTypes.joinToString(",") { it.simpleName.take(3) }
        }
        logs.add("Sigs:${sigs.size}")

        // Try creating socket via HiddenApiBypass.newInstance for L2CAP (type=3) & RFCOMM AAP (type=1)
        val attempts = listOf(
            Triple("L2_1001_Insec", 3, 0x1001),
            Triple("L2_1001_Sec", -3, 0x1001),
            Triple("L2_1003", 3, 0x1003),
            Triple("RF_AAP", 1, -1)
        )

        for ((label, typeCode, port) in attempts) {
            report("E1 Trying $label via Bypass.newInstance...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null

            val future = executor.submit<String> {
                try {
                    val isSec = typeCode < 0
                    val realType = kotlin.math.abs(typeCode)
                    val uuidArg = if (realType == 1) ParcelUuid(AAP_UUID) else null

                    val sock = HiddenApiBypass.newInstance(
                        BluetoothSocket::class.java,
                        realType,
                        -1,
                        isSec,
                        isSec,
                        device,
                        port,
                        uuidArg
                    ) as BluetoothSocket
                    sockRef = sock
                    sock.connect()

                    val out = sock.outputStream
                    val inp = sock.inputStream
                    out.write(HANDSHAKE); out.flush()
                    val r1 = toHex(readAvail(inp, 250))
                    out.write(SET_FEATURES); out.flush()
                    out.write(REQ_NOTIFICATIONS); out.flush()
                    Thread.sleep(100)
                    for (pkt in getAncPackets(mode)) {
                        out.write(pkt); out.flush()
                        Thread.sleep(80)
                    }
                    val r2 = toHex(readAvail(inp, 250))
                    activeL2capSocket = sock // keep alive!
                    "CONNECTED!(R1:$r1,R2:$r2)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    val msg = t.cause?.message ?: t.message ?: t.javaClass.simpleName
                    "Err:${msg.take(28)}"
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

    // ENGINE 2: Crash-Proof HiddenApiBypass.invoke() for createL2capSocket + AllBluetoothDevice Methods
    private fun runEngine2BypassInvokeMethod(
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf<String>()
        val methods = HiddenApiBypass.getDeclaredMethods(BluetoothDevice::class.java)
            .map { it as java.lang.reflect.Method }
        val l2capMethods = methods.filter { it.name.contains("L2cap", ignoreCase = true) || it.name.contains("Socket", ignoreCase = true) }
        logs.add("Found:${l2capMethods.map { it.name.replace("create", "") }.distinct().joinToString(",")}")

        for (mName in listOf("createInsecureL2capSocket", "createL2capSocket", "createInsecureL2capChannel", "createL2capChannel")) {
            report("E2 Invoking $mName(0x1001)...")
            val executor = Executors.newSingleThreadExecutor()
            var sockRef: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val sock = HiddenApiBypass.invoke(
                        BluetoothDevice::class.java,
                        device,
                        mName,
                        0x1001
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
                    "OPEN!(R1:$r1,R2:$r2)"
                } catch (t: Throwable) {
                    try { sockRef?.close() } catch (_: Throwable) {}
                    val msg = t.cause?.message ?: t.message ?: t.javaClass.simpleName
                    "Err:${msg.take(26)}"
                }
            }
            try {
                val res = future.get(2800, TimeUnit.MILLISECONDS)
                logs.add("${mName.replace("create", "")}->$res")
                if (res.startsWith("OPEN")) break
            } catch (_: Throwable) {
                try { sockRef?.close() } catch (_: Throwable) {}
                future.cancel(true)
                logs.add("${mName.replace("create", "")}->TIMEOUT")
            } finally {
                executor.shutdownNow()
            }
        }

        report("E2 Result:\n" + logs.joinToString("\n"))
    }

    // ENGINE 3: Connect GATT Directly to the Scanned BLE Device (Mr-kang Mfg:0x004C) & Dump Apple Bytes!
    private fun runEngine3BleScanAndConnectGatt(
        context: Context,
        adapter: BluetoothAdapter,
        mode: String,
        report: (String) -> Unit
    ) {
        val scanner = adapter.bluetoothLeScanner ?: return report("E3: No BLE Scanner")
        report("E3: Scanning for Mr-kang BLE (0x004C)...")

        var matchedResult: ScanResult? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: ""
                val mfg = result.scanRecord?.manufacturerSpecificData
                val hasApple = mfg != null && mfg.get(0x004C) != null
                if (name.contains("kang", ignoreCase = true) || result.device?.address?.endsWith("C8:8B") == true || (hasApple && result.rssi > -65)) {
                    if (matchedResult == null) matchedResult = result
                }
            }
        }

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        for (i in 1..25) {
            if (matchedResult != null) break
            Thread.sleep(100)
        }
        scanner.stopScan(callback)

        val res = matchedResult ?: return report("E3: Mr-kang BLE not found in scan")
        val appleBytes = res.scanRecord?.manufacturerSpecificData?.get(0x004C)
        val appleHex = if (appleBytes != null) toHex(appleBytes).take(28) else "None"

        report("E3 Found BLE! AppleHex:$appleHex\nConnecting LE GATT...")

        // Connect GATT specifically to the SCANNED BLE device instance using TRANSPORT_LE (2)
        res.device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    report("E3 BLE GATT Connected! Discovering services...")
                    gatt.discoverServices()
                } else {
                    report("E3 AppleHex:$appleHex\nGATT Disconnected (status=$status)")
                    gatt.close()
                }
            }

            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Thread {
                    try {
                        val srvs = mutableListOf<String>()
                        var wroteCount = 0
                        for (s in gatt.services) {
                            val su = s.uuid.toString().substring(0, 8)
                            val chars = s.characteristics.map { c ->
                                val cu = c.uuid.toString().substring(0, 4)
                                val p = c.properties
                                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    for (pkt in listOf(HANDSHAKE) + getAncPackets(mode)) {
                                        try {
                                            c.value = pkt
                                            gatt.writeCharacteristic(c)
                                            Thread.sleep(70)
                                        } catch (_: Throwable) {}
                                    }
                                    wroteCount++
                                    "$cu(W)"
                                } else cu
                            }
                            srvs.add("$su:[${chars.joinToString(",")}]")
                        }
                        report("E3 BLE Wrote:$wroteCount | Hex:${appleHex.take(16)}\n" + srvs.joinToString("\n"))
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
