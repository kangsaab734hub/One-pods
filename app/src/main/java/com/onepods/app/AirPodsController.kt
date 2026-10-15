package com.onepods.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.InputStream
import java.lang.reflect.Constructor
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
object AirPodsController {
    private val AAP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private val JIELI_SERVICE_UUID: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")

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
    private var bypassDone = false

    private fun unlockHiddenApi(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                bypassDone = true
                return "OK"
            } catch (e: Exception) {
                return "Err:${e.message}"
            }
        }
        return "OldSDK"
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
                val bypassStatus = unlockHiddenApi()
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter ?: return@Thread report("Error: No BT Adapter")
                val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
                    ?: return@Thread report("Error: BT Permission denied")

                val device = bonded.firstOrNull { d ->
                    try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true } catch (e: Exception) { false }
                } ?: bonded.firstOrNull() ?: return@Thread report("Error: No Earbuds found")

                when (engine) {
                    1 -> runEngine1TimedL2cap(device, mode, bypassStatus, ::report)
                    2 -> runEngine2Ch1AndSystemHfp(context, adapter, device, mode, ::report)
                    3 -> runEngine3BleScannerAndGatt(context, adapter, device, mode, ::report)
                }
            } catch (e: Exception) {
                report("Fatal: ${e.javaClass.simpleName}: ${e.message}")
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

    // ENGINE 1: Non-Blocking Timed L2CAP Tester (Tests 0x1001, 0x0110, 0x1003 via HiddenApiBypass)
    private fun runEngine1TimedL2cap(
        device: BluetoothDevice,
        mode: String,
        bypassStatus: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf("Bypass:$bypassStatus")
        val ancPkt = getAncPacket(mode)

        // Check if HiddenApiBypass exposed BluetoothSocket constructors
        val ctors = try {
            BluetoothSocket::class.java.declaredConstructors
        } catch (e: Throwable) {
            emptyArray<Constructor<*>>()
        }
        logs.add("Ctors:${ctors.size}")

        val ctor7 = ctors.firstOrNull { it.parameterTypes.size == 7 }?.apply { isAccessible = true }
        if (ctor7 == null) {
            report("E1: Ctors still 0! Trying HiddenApiBypass.getDeclaredMethods...")
        }

        // Test candidates with strict 2.5-second timeout per attempt so it NEVER hangs!
        val candidates = listOf(
            Triple("L2CAP_1001_Sec", 3, 0x1001),
            Triple("L2CAP_1001_Insec", -3, 0x1001),
            Triple("L2CAP_0110_Swap", 3, 0x0110),
            Triple("RFCOMM_AAP", 1, -1)
        )

        for ((name, typeCode, psm) in candidates) {
            report("E1 Testing $name...")
            val executor = Executors.newSingleThreadExecutor()
            var tempSock: BluetoothSocket? = null
            val future = executor.submit<String> {
                try {
                    val isSec = typeCode > 0
                    val realType = kotlin.math.abs(typeCode)
                    val uuidArg = if (realType == 1) ParcelUuid(AAP_UUID) else null

                    val sock: BluetoothSocket = if (ctor7 != null) {
                        ctor7.newInstance(realType, -1, isSec, isSec, device, psm, uuidArg) as BluetoothSocket
                    } else {
                        val mName = if (isSec) "createL2capSocket" else "createInsecureL2capSocket"
                        val m = HiddenApiBypass.getDeclaredMethod(
                            BluetoothDevice::class.java, mName, Int::class.javaPrimitiveType
                        )
                        m.isAccessible = true
                        m.invoke(device, psm) as BluetoothSocket
                    }
                    tempSock = sock
                    sock.connect()

                    val out = sock.outputStream
                    val inp = sock.inputStream
                    out.write(HANDSHAKE); out.flush()
                    val r1 = toHex(readAvail(inp, 250))
                    out.write(SET_FEATURES); out.flush()
                    out.write(REQ_NOTIFICATIONS); out.flush()
                    Thread.sleep(100)
                    out.write(ancPkt); out.flush()
                    val r2 = toHex(readAvail(inp, 250))
                    Thread.sleep(300)
                    sock.close()
                    "OPEN(R1:$r1,R2:$r2)"
                } catch (t: Throwable) {
                    try { tempSock?.close() } catch (_: Exception) {}
                    val msg = t.cause?.message ?: t.message ?: t.javaClass.simpleName
                    "Err:${msg.take(22)}"
                }
            }

            try {
                val res = future.get(2500, TimeUnit.MILLISECONDS)
                logs.add("$name->$res")
                if (res.startsWith("OPEN")) break
            } catch (_: Exception) {
                try { tempSock?.close() } catch (_: Exception) {}
                future.cancel(true)
                logs.add("$name->TIMEOUT(2.5s)")
            } finally {
                executor.shutdownNow()
            }
        }

        report("E1 Done:\n" + logs.joinToString("\n"))
    }

    // ENGINE 2: Deep Ch1 (Non-Echo Port) Protocol Blaster + System BluetoothHeadset Vendor Command
    private fun runEngine2Ch1AndSystemHfp(
        context: Context,
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val logs = mutableListOf<String>()
        val m0 = when (mode) { "Off" -> 0; "ANC" -> 1; "Transparency" -> 2; else -> 0 }
        val m1 = m0 + 1

        // Part A: Send via Android's ALREADY CONNECTED System BluetoothHeadset Profile!
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    val headset = proxy as BluetoothHeadset
                    // Try reflection methods on BluetoothHeadset to send AT/Vendor commands over existing system HFP
                    val methods = HiddenApiBypass.getDeclaredMethods(BluetoothHeadset::class.java)
                    val vendorM = methods.firstOrNull { it.name == "sendVendorSpecificResultCode" }
                    if (vendorM != null) {
                        val ok1 = headset.sendVendorSpecificResultCode(device, "+XAPL", "iPhone,7")
                        val ok2 = headset.sendVendorSpecificResultCode(device, "+XANC", "$m1")
                        val ok3 = headset.sendVendorSpecificResultCode(device, "+ANC", "$m0")
                        logs.add("SysHFP:XAPL=$ok1,XANC=$ok2")
                    }
                } catch (t: Throwable) {
                    logs.add("SysHFP:${t.javaClass.simpleName}")
                } finally {
                    adapter.closeProfileProxy(profile, proxy)
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)

        // Part B: Deep Test on Open RFCOMM Channel 1 (Which was REAL(None), NOT Echo!)
        try {
            report("E2: Connecting to REAL Port Ch1...")
            val m = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            val sock = m.invoke(device, 1) as BluetoothSocket
            sock.connect()
            val out = sock.outputStream
            val inp = sock.inputStream

            // Check if Ch1 sends anything on connect
            val initBytes = readAvail(inp, 200)
            if (initBytes.isNotEmpty()) logs.add("Ch1Init:${toHex(initBytes)}")

            // Send JieLi RCSP Handshake + ANC on Ch1
            val jlMode = m0.toByte()
            val jlPkts = listOf(
                // JieLi RCSP Get Target Feature (Handshake)
                byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x02, 0x00, 0x05, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xEF.toByte()),
                // JieLi RCSP Set ANC Mode (Cmd 0x08, Attr 0x19)
                byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), 0x08, 0x00, 0x05, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0x19, jlMode, 0xEF.toByte()),
                // Apple AAP Handshake + ANC on Ch1
                HANDSHAKE,
                SET_FEATURES,
                getAncPacket(mode)
            )

            val ch1Replies = mutableListOf<String>()
            for ((idx, p) in jlPkts.withIndex()) {
                out.write(p)
                out.flush()
                val r = readAvail(inp, 180)
                if (r.isNotEmpty()) ch1Replies.add("P$idx:${toHex(r).take(16)}")
            }
            logs.add("Ch1Rep:${if (ch1Replies.isEmpty()) "Silent" else ch1Replies.joinToString(",")}")
            Thread.sleep(300)
            sock.close()
        } catch (e: Exception) {
            logs.add("Ch1Err:${(e.message ?: "").take(20)}")
        }

        report("E2 Done:\n" + logs.joinToString("\n"))
    }

    // ENGINE 3: Live BLE Scanner to Find Hidden Companion BLE MAC + Write ANC
    private fun runEngine3BleScannerAndGatt(
        context: Context,
        adapter: BluetoothAdapter,
        classicDevice: BluetoothDevice,
        mode: String,
        report: (String) -> Unit
    ) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            report("E3: BLE Scanner not available")
            return
        }

        report("E3: Scanning BLE airwaves for 3s (Keep earbuds out of case)...")
        val foundDevices = mutableMapOf<String, ScanResult>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val rssi = result.rssi
                if (rssi > -80) { // Nearby devices only
                    foundDevices[dev.address] = result
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            Thread.sleep(3000)
            scanner.stopScan(callback)
        } catch (e: Exception) {
            report("E3 Scan Err: ${e.message}")
            return
        }

        if (foundDevices.isEmpty()) {
            report("E3: 0 BLE devices found nearby! (Check Location/BLE Scan permission)")
            return
        }

        val summary = foundDevices.values.sortedByDescending { it.rssi }.take(4).map { res ->
            val name = res.device.name ?: res.scanRecord?.deviceName ?: "NoName"
            val mfg = res.scanRecord?.manufacturerSpecificData
            val mfgId = if (mfg != null && mfg.size() > 0) "0x%04X".format(mfg.keyAt(0)) else "None"
            "${name.take(8)}(${res.device.address.takeLast(5)},Mfg:$mfgId,${res.rssi}dBm)"
        }

        report("E3 BLE Found ${foundDevices.size}:\n" + summary.joinToString("\n"))
    }
}
