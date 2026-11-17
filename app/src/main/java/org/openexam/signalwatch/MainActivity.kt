package org.openexam.signalwatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SignalStore
    private lateinit var scanner: SignalScanner
    private lateinit var status: TextView
    private lateinit var elapsed: TextView
    private lateinit var bleStatus: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var sdrStatus: TextView
    private lateinit var bleCount: TextView
    private lateinit var wifiCount: TextView
    private lateinit var newCount: TextView
    private lateinit var lastScan: TextView
    private lateinit var signalList: LinearLayout
    private lateinit var startStop: Button
    private val observations = linkedMapOf<String, ObservedSignal>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)

    private var active = false
    private var sessionId = 0L
    private var startedElapsed = 0L
    private var sessionSalt = ""
    private var scannerBleState = "대기"
    private var scannerWifiState = "대기"
    private var exportId: Long? = null
    private val baselineSeconds = 20
    private var destroyed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            redraw()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        elapsed = findViewById(R.id.elapsed)
        bleStatus = findViewById(R.id.bleStatus)
        wifiStatus = findViewById(R.id.wifiStatus)
        sdrStatus = findViewById(R.id.sdrStatus)
        bleCount = findViewById(R.id.bleCount)
        wifiCount = findViewById(R.id.wifiCount)
        newCount = findViewById(R.id.newCount)
        lastScan = findViewById(R.id.lastScan)
        signalList = findViewById(R.id.signalList)
        startStop = findViewById(R.id.startStop)
        store = SignalStore(this)
        scanner = SignalScanner(
            this,
            onSignal = { reading -> runOnUiThread { observe(reading) } },
            onStatus = { ble, wifi -> runOnUiThread {
                scannerBleState = ble
                scannerWifiState = wifi
            } }
        )
        startStop.setOnClickListener { if (active) stopSession() else requestThenStart() }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.helpButton).setOnClickListener { showGuide() }
        if (Build.VERSION.SDK_INT >= 35) {
            val root = findViewById<ScrollView>(R.id.rootScroll)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        handler.post(ticker)
    }

    private fun missingPermissions(): Array<String> {
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT
        }
        return required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun requestThenStart() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("검사 권한 안내")
                .setMessage("블루투스 탐색과 와이파이 접속점 검색에 권한이 필요합니다. 와이파이 검색은 Android 정책상 정밀 위치 권한과 시스템 위치 서비스가 필요합니다. 위치 좌표는 저장하거나 전송하지 않습니다.")
                .setPositiveButton("권한 요청") { _, _ -> requestPermissions(missing, 501) }
                .setNegativeButton("취소", null)
                .show()
        } else {
            startSession()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 501) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startSession()
            else AlertDialog.Builder(this)
                .setTitle("검사 시작 불가")
                .setMessage("검색 권한이 허용되지 않았습니다. Android 설정에서 필요한 권한을 허용한 다음 다시 시도하세요.")
                .setPositiveButton("확인", null).show()
        }
    }

    private fun startSession() {
        if (active) return
        if (!scanner.bleAvailable() && !scanner.wifiAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("무선 기능 확인")
                .setMessage("블루투스와 와이파이가 모두 꺼졌거나 사용 불가한 상태입니다. 시스템 설정에서 기능을 켜고 시작하세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        sessionSalt = UUID.randomUUID().toString()
        observations.clear()
        sessionId = store.beginSession(baselineSeconds)
        startedElapsed = SystemClock.elapsedRealtime()
        active = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scanner.begin()
        startStop.text = "검사 종료 및 기록 저장"
        redraw()
    }

    private fun stopSession() {
        if (!active) return
        active = false
        scanner.stop()
        observations.values.forEach { store.save(sessionId, it) }
        store.endSession(sessionId)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startStop.text = "새 검사 시작"
        redraw()
    }

    private fun observe(reading: SignalReading) {
        if (!active) return
        val id = hashId(reading.kind.name + ":" + reading.rawIdentifier)
        val existing = observations[id]
        if (existing == null) {
            val isNew = SystemClock.elapsedRealtime() - startedElapsed >= baselineSeconds * 1_000L
            val item = ObservedSignal(
                stableKey = id,
                kind = reading.kind,
                displayName = reading.displayName.take(75),
                rssiDbm = reading.rssiDbm,
                firstSeenMillis = reading.observedAtMillis,
                lastSeenMillis = reading.observedAtMillis,
                count = 1,
                frequencyMhz = reading.frequencyMhz,
                discoveredDuringExam = isNew,
                lastPersistMillis = reading.observedAtMillis
            )
            observations[id] = item
            store.save(sessionId, item)
        } else {
            existing.lastSeenMillis = reading.observedAtMillis
            existing.rssiDbm = reading.rssiDbm
            existing.frequencyMhz = reading.frequencyMhz
            if (reading.displayName != "이름 미표시 BLE 신호" && reading.displayName != "이름 숨김 접속점") {
                existing.displayName = reading.displayName.take(75)
            }
            existing.count++
            if (reading.observedAtMillis - existing.lastPersistMillis > 10_000L) {
                store.save(sessionId, existing)
                existing.lastPersistMillis = reading.observedAtMillis
            }
        }
    }

    private fun hashId(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sessionSalt + ":" + raw).toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun redraw() {
        if (active) {
            val seconds = ((SystemClock.elapsedRealtime() - startedElapsed) / 1_000L).coerceAtLeast(0L)
            val remaining = baselineSeconds - seconds
            status.text = if (remaining > 0) "기준 신호 측정 중" else "무선 신호 관측 중"
            status.setTextColor(Color.parseColor("#145E66"))
            elapsed.text = if (remaining > 0) "${remaining}초 후 본 검사로 전환 · 기준 신호도 기록됩니다"
                else "검사 경과 ${seconds / 60}분 ${seconds % 60}초 · 화면을 켜 두세요"
        } else {
            status.text = if (sessionId == 0L) "대기 중" else "검사 종료됨"
            elapsed.text = if (sessionId == 0L) "검사를 시작하면 20초간 기준 신호를 기록합니다"
                else "기록이 기기 내부에 저장되었습니다"
            status.setTextColor(Color.parseColor("#526B72"))
        }
        bleStatus.text = "블루투스 LE  ·  " + if (active) scannerBleState else if (scanner.bleAvailable()) "준비됨" else "꺼짐 / 권한 확인"
        wifiStatus.text = "와이파이 AP  ·  " + if (active) scannerWifiState else if (scanner.wifiAvailable()) "준비됨 (위치 서비스 필요)" else "꺼짐 / 미지원"
        sdrStatus.text = UsbSdrStatus.getDescription(this)
        bleCount.text = observations.values.count { it.kind == SignalKind.BLE }.toString()
        wifiCount.text = observations.values.count { it.kind == SignalKind.WIFI }.toString()
        newCount.text = "${observations.values.count { it.discoveredDuringExam }}건"
        fun t(ms: Long): String = if (ms == 0L) "아직 없음" else dateFormat.format(Date(ms))
        lastScan.text = "BLE 최근 관측: ${t(scanner.lastBleAt)}  ·  와이파이 결과: ${t(scanner.lastWifiAt)}"
        renderSignals()
    }

    private fun renderSignals() {
        signalList.removeAllViews()
        val top = observations.values.sortedByDescending { it.lastSeenMillis }.take(8)
        if (top.isEmpty()) {
            val empty = label("아직 관측된 신호가 없습니다.\n권한과 무선 기능 상태를 확인하세요.", 13, "#70838B")
            empty.setPadding(dp(5), dp(10), dp(5), dp(10))
            signalList.addView(empty)
            return
        }
        top.forEachIndexed { idx, item ->
            if (idx > 0) {
                val line = View(this).apply { setBackgroundColor(Color.parseColor("#E9EEF0")) }
                signalList.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(9) })
            }
            val title = label(
                "${if (item.discoveredDuringExam) "● 신규 · " else "○ 기준 · "}${item.displayName}",
                13, if (item.discoveredDuringExam) "#145E66" else "#25424A"
            )
            title.setTypeface(null, android.graphics.Typeface.BOLD)
            signalList.addView(title)
            val details = "${item.kind.display} · ${item.rssiDbm} dBm" +
                (item.frequencyMhz?.let { " · ${it}MHz" } ?: "") +
                " · ${t(item.lastSeenMillis)}"
            signalList.addView(label(details, 11, "#788A90"))
            title.setOnClickListener { showSignalDetail(item) }
        }
        if (observations.size > top.size) {
            signalList.addView(label("외 ${observations.size - top.size}건 · 전체 목록은 CSV 내보내기", 11, "#72878E"))
        }
    }

    private fun showSignalDetail(item: ObservedSignal) {
        AlertDialog.Builder(this)
            .setTitle(item.displayName)
            .setMessage("유형: ${item.kind.display}\n세션 내 가명 식별자: ${item.stableKey}\n최초 관측: ${t(item.firstSeenMillis)}\n최근 관측: ${t(item.lastSeenMillis)}\n마지막 수신 강도: ${item.rssiDbm} dBm\n관측 횟수: ${item.count}\n주파수: ${item.frequencyMhz?.let { "$it MHz" } ?: "해당 없음"}\n\n수신 신호는 특정 좌석이나 기기의 소유자를 확정하지 않습니다.")
            .setPositiveButton("닫기", null).show()
    }

    private fun showHistory() {
        if (active) observations.values.forEach { store.save(sessionId, it) }
        val sessions = store.sessions()
        if (sessions.isEmpty()) {
            AlertDialog.Builder(this).setTitle("검사 기록").setMessage("아직 저장된 검사 기록이 없습니다.")
                .setPositiveButton("닫기", null).show()
            return
        }
        val display = sessions.map {
            "${SimpleDateFormat("MM월 dd일 HH:mm", Locale.KOREA).format(Date(it.started))} · ${it.signals}개 신호${if (it.ended == null) " (미종료)" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("검사 기록 선택 (CSV 저장)")
            .setItems(display) { _, which -> beginExport(sessions[which].id) }
            .setNeutralButton("전체 기록 삭제") { _, _ -> confirmDelete() }
            .setNegativeButton("취소", null).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("모든 검사 기록 삭제")
            .setMessage("기기 내부에 저장된 모든 검사 기록을 영구 삭제합니다. 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                if (active) stopSession()
                store.deleteAll()
                observations.clear()
                sessionId = 0
                redraw()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun beginExport(id: Long) {
        exportId = id
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "exam_signal_${fileDate.format(Date())}.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 701)
    }

    @Deprecated("Uses framework Activity result API to avoid external runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == RESULT_OK && data?.data != null) {
            val id = exportId ?: return
            try {
                val csv = store.exportSessionCsv(id)
                contentResolver.openOutputStream(data.data!!)?.use {
                    it.write(csv.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("파일 열기 실패")
                AlertDialog.Builder(this).setMessage("CSV 파일이 저장되었습니다. 기록에는 실제 MAC 주소 대신 세션별 가명 ID가 포함됩니다.")
                    .setPositiveButton("확인", null).show()
            } catch (e: Exception) {
                AlertDialog.Builder(this).setTitle("내보내기 실패").setMessage(e.message ?: "알 수 없는 오류")
                    .setPositiveButton("닫기", null).show()
            } finally { exportId = null }
        }
    }

    private fun showGuide() {
        val text = assets.open("guide_ko.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val box = ScrollView(this).apply {
            val content = label(text, 14, "#263F46")
            content.setPadding(dp(24), dp(20), dp(24), dp(24))
            content.setLineSpacing(dp(4).toFloat(), 1f)
            addView(content)
        }
        AlertDialog.Builder(this).setTitle("사용설명서 · v0.1")
            .setView(box).setPositiveButton("닫기", null).show()
    }

    private fun label(text: String, sp: Int, hex: String): TextView = TextView(this).apply {
        this.text = text
        textSize = sp.toFloat()
        setTextColor(Color.parseColor(hex))
        gravity = Gravity.START
    }

    private fun t(ms: Long): String = dateFormat.format(Date(ms))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        super.onStop()
        // No background acquisition: foreground-only v0.1, prevents misleading 'monitoring' state.
        if (active) stopSession()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(ticker)
        if (active) stopSession()
        scanner.stop()
        store.close()
        super.onDestroy()
    }
}
