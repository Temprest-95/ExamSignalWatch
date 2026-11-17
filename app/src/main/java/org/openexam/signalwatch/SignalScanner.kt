package org.openexam.signalwatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Foreground-only observation engine. It neither pairs nor connects to nearby devices. */
class SignalScanner(
    private val context: Context,
    private val onSignal: (SignalReading) -> Unit,
    private val onStatus: (String, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var active = false
    private var bleScanning = false
    private var wifiRegistered = false
    private var wifiStatus = "대기"
    private var bleStatus = "대기"
    var lastBleAt: Long = 0L
        private set
    var lastWifiAt: Long = 0L
        private set

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addBleResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::addBleResult)
        }
        override fun onScanFailed(errorCode: Int) {
            bleScanning = false
            bleStatus = "오류($errorCode): BLE 검색 실패"
            dispatchStatus()
        }
    }
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active || intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (updated) {
                collectWifiResults()
            } else {
                wifiStatus = "검색 제한 또는 새 검색 결과 없음"
                dispatchStatus()
            }
        }
    }
    private val bleCycle = object : Runnable {
        override fun run() {
            if (!active) return
            if (bleScanning) {
                stopBle()
                handler.postDelayed(this, 8_000)
            } else {
                startBle()
                handler.postDelayed(this, 12_000)
            }
        }
    }
    private val wifiCycle = object : Runnable {
        override fun run() {
            if (!active) return
            requestWifiScan()
            handler.postDelayed(this, 45_000)
        }
    }

    @SuppressLint("MissingPermission")
    fun begin() {
        if (active) return
        active = true
        lastBleAt = 0L
        lastWifiAt = 0L
        bleStatus = "시작 중"
        wifiStatus = "시작 중"
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(wifiReceiver, filter)
            }
            wifiRegistered = true
        } catch (_: Exception) {
            wifiStatus = "시스템 와이파이 알림 등록 실패"
        }
        handler.post(bleCycle)
        handler.post(wifiCycle)
        dispatchStatus()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(bleCycle)
        handler.removeCallbacks(wifiCycle)
        stopBle()
        if (wifiRegistered) {
            try { context.unregisterReceiver(wifiReceiver) } catch (_: Exception) { }
            wifiRegistered = false
        }
        bleStatus = "중지"
        wifiStatus = "중지"
        dispatchStatus()
    }

    fun bleAvailable(): Boolean {
        val adapter = bluetoothManager?.adapter ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) false else adapter.isEnabled
        } catch (_: SecurityException) { false }
    }

    fun wifiAvailable(): Boolean = wifi?.isWifiEnabled == true

    @SuppressLint("MissingPermission")
    private fun startBle() {
        if (!active) return
        val hasPermission = if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) {
            bleStatus = "권한 없음"
            dispatchStatus()
            return
        }
        if (!bleAvailable()) {
            bleStatus = "블루투스 꺼짐 / 미지원"
            dispatchStatus()
            return
        }
        try {
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                bleStatus = "BLE 수신기 사용 불가"
            } else {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, bleCallback)
                bleScanning = true
                bleStatus = "BLE 신호 탐색 중"
            }
        } catch (e: Exception) {
            bleStatus = "BLE 시작 실패: ${e.javaClass.simpleName}"
        }
        dispatchStatus()
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        if (!bleScanning) return
        try { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        catch (_: Exception) { }
        bleScanning = false
        if (active) {
            bleStatus = "BLE 간헐적 검색 대기(8초)"
            dispatchStatus()
        }
    }

    @SuppressLint("MissingPermission")
    private fun addBleResult(result: ScanResult) {
        if (!active) return
        try {
            val mac = result.device.address ?: return
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: "이름 미표시 BLE 신호"
            lastBleAt = System.currentTimeMillis()
            onSignal(SignalReading(SignalKind.BLE, mac, name, result.rssi))
        } catch (_: SecurityException) {
            bleStatus = "BLE 결과 접근 권한 없음"
            dispatchStatus()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiScan() {
        if (!active) return
        val lm = context.getSystemService(LocationManager::class.java)
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true else true
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            wifiStatus = "정밀 위치 권한 필요 (주변 와이파이 검색용)"
        } else if (!locationEnabled) {
            wifiStatus = "시스템 위치 서비스 꺼짐"
        } else if (!wifiAvailable()) {
            wifiStatus = "와이파이 꺼짐 / 미지원"
        } else {
            try {
                @Suppress("DEPRECATION")
                val requested = wifi?.startScan() ?: false
                wifiStatus = if (requested) "와이파이 검색 요청 완료" else "운영체제 검색 제한·대기"
            } catch (e: Exception) {
                wifiStatus = "와이파이 검색 오류: ${e.javaClass.simpleName}"
            }
        }
        dispatchStatus()
    }

    @SuppressLint("MissingPermission")
    private fun collectWifiResults() {
        if (!active) return
        try {
            @Suppress("DEPRECATION")
            val results = wifi?.scanResults.orEmpty()
            val observed = System.currentTimeMillis()
            results.forEach { result ->
                val id = result.BSSID?.takeIf { it.isNotBlank() } ?: return@forEach
                @Suppress("DEPRECATION")
                val name = result.SSID?.takeIf { it.isNotBlank() } ?: "이름 숨김 접속점"
                onSignal(SignalReading(SignalKind.WIFI, id, name, result.level, result.frequency, observed))
            }
            lastWifiAt = observed
            wifiStatus = "와이파이 접속점 ${results.size}개 · 결과 갱신됨"
        } catch (e: Exception) {
            wifiStatus = "결과 접근 실패: ${e.javaClass.simpleName}"
        }
        dispatchStatus()
    }

    private fun dispatchStatus() = onStatus(bleStatus, wifiStatus)
}
