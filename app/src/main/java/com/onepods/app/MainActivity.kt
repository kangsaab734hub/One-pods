package com.onepods.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF080A10)
private val CardColor = Color(0xFF151923)
private val Accent = Color(0xFF9B8CFF)

class MainActivity : ComponentActivity() {
    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private var deviceName by mutableStateOf("Your AirPods")
    private var connectionStatus by mutableStateOf("Tap GET STARTED to check")
    private var batteryText by mutableStateOf("--%")
    private var started by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasBluetoothPermission()) refreshBluetooth()
            else connectionStatus = "Bluetooth permission required"
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (hasBluetoothPermission()) refreshBluetooth()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioEngine.initAudioEffects()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothReceiver, filter)
        }

        setContent {
            MaterialTheme {
                OnePodsScreen(
                    deviceName = deviceName,
                    connectionStatus = connectionStatus,
                    batteryText = batteryText,
                    onStart = { startBluetoothCheck() },
                    onRefresh = { if (hasBluetoothPermission()) refreshBluetooth() }
                )
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun startBluetoothCheck() {
        if (!hasBluetoothPermission()) {
            permissionLauncher.launch(bluetoothPermissions)
        } else {
            started = true
            refreshBluetooth()
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun refreshBluetooth() {
        started = true
        val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = manager.adapter

        if (adapter == null || !adapter.isEnabled) {
            connectionStatus = if (adapter == null) "Bluetooth not available" else "Bluetooth is turned off"
            deviceName = "Your Earbuds"
            return
        }

        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            connectionStatus = "Bluetooth permission required"
            return
        }

        // Find EXACT currently connected device (ignores name check)
        val airPods = bonded.firstOrNull { device ->
            try {
                device.javaClass.getMethod("isConnected").invoke(device) as? Boolean == true
            } catch(e: Exception) { false }
        } ?: bonded.firstOrNull()

        if (airPods == null) {
            deviceName = "No devices found"
            connectionStatus = "Pair your earbuds in Bluetooth settings"
            batteryText = "--%"
            return
        }

        deviceName = airPods.name ?: "Your Earbuds"
        checkAirPodsConnection(adapter, airPods)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val level = try {
                airPods.javaClass.getMethod("getBatteryLevel").invoke(airPods) as? Int
            } catch (_: Exception) { null }
            batteryText = if (level != null && level in 0..100) "$level%" else "--%"
        } else {
            batteryText = "--%"
        }
    }
    @Suppress("MissingPermission")
    private fun checkAirPodsConnection(adapter: BluetoothAdapter, device: BluetoothDevice) {
        try {
            val isConnected = device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
            connectionStatus = if (isConnected) "Connected" else "Not Connected"
        } catch (e: Exception) {
            connectionStatus = "Not Connected"
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        AudioEngine.release()
        super.onDestroy()
    }
}

@Composable
fun OnePodsScreen(
    deviceName: String,
    connectionStatus: String,
    batteryText: String,
    onStart: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF17172A), Background)))
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text("ONE PODS", color = Color.White, fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            Text("Your earbuds, reimagined.", color = Color.LightGray, fontSize = 14.sp)
            Spacer(Modifier.height(30.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = CardColor)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("AIRPODS", color = Accent, fontSize = 12.sp,
                        letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    Text("◉  ♫  ◉", color = Color.White, fontSize = 42.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(deviceName, color = Color.White, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold)
                    Text(connectionStatus, color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(22.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BatteryCard(Modifier.weight(1f), "LEFT", batteryText)
                        BatteryCard(Modifier.weight(1f), "RIGHT", batteryText)
                        BatteryCard(Modifier.weight(1f), "CASE", "--%")
                    }

                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text("GET STARTED", color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 5.dp))
                    }
                    TextButton(onClick = onRefresh) {
                        Text("Refresh status", color = Accent)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("QUICK CONTROLS", color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FeatureCard(Modifier.weight(1f), "Connection", connectionStatus)
                FeatureCard(Modifier.weight(1f), "Sound", "Audio controls")
            }
            AudioControls()
            Spacer(Modifier.height(60.dp))
            Text("Made for your listening experience", color = Color.DarkGray,
                fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun BatteryCard(modifier: Modifier = Modifier, title: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF222735))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FeatureCard(modifier: Modifier = Modifier, title: String, subtitle: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
    }
}
