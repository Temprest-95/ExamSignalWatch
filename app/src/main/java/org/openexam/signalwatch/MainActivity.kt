package org.openexam.signalwatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private enum class Phase { IDLE, BASELINE, EXAM, FINISHED }
    private enum class FilterScope { PRIORITY, NEW, REPEATED, MUTED, ALL, CATEGORY }
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
    private lateinit var categorySummary: TextView
    private lateinit var alertBanner: TextView
    private lateinit var autoMuteSummary: TextView
    private lateinit var abortSession: Button
    private lateinit var reviewSummary: TextView
    private lateinit var filterButtons: LinearLayout
    private var chosenCategory: DeviceCategory? = null
    private var scope = FilterScope.PRIORITY
    private var showAllRows = false
    private var listDirty = true
    private var lastListRefresh = 0L
    private val observations = linkedMapOf<String, ObservedSignal>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)

    private var active = false
    private var phase = Phase.IDLE
    private var examStartedAtMillis = 0L
    private var successfulBaselineWifiScans = 0
    private var lastAlertMessage = ""
    private var lastAlertAtMillis = 0L
    private var trackingKey: String? = null
    private var trackingDialog: AlertDialog? = null
    private var trackingBody: TextView? = null
    private val trackingSamples = ArrayDeque<Pair<Long, Int>>()
    private var sessionId = 0L
    private var startedElapsed = 0L
    private var sessionSalt = ""
    private var scannerBleState = "대기"
    private var scannerWifiState = "대기"
    private var exportId: Long? = null
    private val baselineSeconds = ExamSignalPolicy.RECOMMENDED_BASELINE_SECONDS
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
        categorySummary = findViewById(R.id.categorySummary)
        alertBanner = findViewById(R.id.alertBanner)
        autoMuteSummary = findViewById(R.id.autoMuteSummary)
        abortSession = findViewById(R.id.abortSession)
        reviewSummary = findViewById(R.id.reviewSummary)
        filterButtons = findViewById(R.id.filterButtons)
        store = SignalStore(this)
        scanner = SignalScanner(
            this,
            onSignal = { reading -> runOnUiThread { observe(reading) } },
            onStatus = { ble, wifi -> runOnUiThread {
                scannerBleState = ble
                scannerWifiState = wifi
            } },
            onFreshWifiRound = { _, count -> runOnUiThread {
                if (phase == Phase.BASELINE && count > 0) successfulBaselineWifiScans++
                listDirty = true
            } }
        )
        startStop.setOnClickListener {
            when (phase) {
                Phase.BASELINE -> requestExamStart()
                Phase.EXAM -> confirmStopSession()
                else -> requestThenStart()
            }
        }
        abortSession.setOnClickListener { confirmStopSession() }
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
            if (missingPermissions().isEmpty()) startSession()
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
        chosenCategory = null
        scope = FilterScope.PRIORITY
        showAllRows = false
        examStartedAtMillis = 0L
        successfulBaselineWifiScans = 0
        lastAlertMessage = ""
        lastAlertAtMillis = 0L
        trackingKey = null
        trackingDialog?.dismiss()
        trackingDialog = null
        trackingBody = null
        trackingSamples.clear()
        listDirty = true
        sessionId = store.beginSession(baselineSeconds)
        startedElapsed = SystemClock.elapsedRealtime()
        active = true
        phase = Phase.BASELINE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scanner.begin()
        startStop.text = "시험 시작"
        abortSession.visibility = View.VISIBLE
        redraw()
    }

    private fun requestExamStart() {
        if (phase != Phase.BASELINE) return
        val elapsedSec = ((SystemClock.elapsedRealtime() - startedElapsed) / 1_000L)
        if (successfulBaselineWifiScans < ExamSignalPolicy.MIN_FRESH_WIFI_ROUNDS || elapsedSec < baselineSeconds) {
            AlertDialog.Builder(this)
                .setTitle("기준 수집이 충분하지 않습니다")
                .setMessage("권장 기준 수집은 ${baselineSeconds}초 및 새로운 Wi-Fi 검색 결과 2회 이상입니다.\n" +
                    "현재 ${elapsedSec}초 / Wi-Fi 검색 갱신 ${successfulBaselineWifiScans}회입니다.\n\n" +
                    "지금 시험을 시작하면 기존 공유기 자동 알림 제외가 제한될 수 있습니다. 계속하시겠습니까?")
                .setPositiveButton("지금 시험 시작") { _, _ -> markExamStarted() }
                .setNegativeButton("기준 더 수집", null).show()
        } else markExamStarted()
    }

    private fun markExamStarted() {
        if (phase != Phase.BASELINE) return
        examStartedAtMillis = System.currentTimeMillis()
        phase = Phase.EXAM
        store.markExamStarted(sessionId, examStartedAtMillis)
        observations.values.forEach { signal ->
            signal.autoMuted = ExamSignalPolicy.mayAutoMuteWifi(signal)
            signal.examWindows = 0
            signal.lastExamWindow = -1L
            signal.lastExamWifiRound = -1L
            store.save(sessionId, signal)
        }
        scope = FilterScope.PRIORITY
        chosenCategory = null
        listDirty = true
        startStop.text = "검사 종료 및 기록 저장"
        abortSession.visibility = View.GONE
        redraw()
    }

    private fun confirmStopSession() {
        if (!active) return
        AlertDialog.Builder(this).setTitle("검사를 종료할까요?")
            .setMessage("현재까지 관측된 신호와 감독관의 확인 기록을 기기에 저장합니다.")
            .setPositiveButton("종료 및 저장") { _, _ -> stopSession() }
            .setNegativeButton("계속 검사", null).show()
    }

    private fun stopSession() {
        if (!active) return
        active = false
        phase = Phase.FINISHED
        trackingDialog?.dismiss()
        trackingDialog = null
        trackingKey = null
        scanner.stop()
        observations.values.forEach { store.save(sessionId, it) }
        store.endSession(sessionId)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startStop.text = "새 검사 시작"
        abortSession.visibility = View.GONE
        listDirty = true
        redraw()
    }

    private fun observe(reading: SignalReading) {
        if (!active) return
        val id = hashId(reading.kind.name + ":" + reading.rawIdentifier)
        val existing = observations[id]
        val duringExam = phase == Phase.EXAM
        val item: ObservedSignal
        if (existing == null) {
            val guess = DeviceClassifier.classify(reading.kind, reading.displayName)
            item = ObservedSignal(
                stableKey = id,
                kind = reading.kind,
                displayName = reading.displayName.take(75),
                rssiDbm = reading.rssiDbm,
                firstSeenMillis = reading.observedAtMillis,
                lastSeenMillis = reading.observedAtMillis,
                count = 1,
                frequencyMhz = reading.frequencyMhz,
                discoveredDuringExam = duringExam,
                lastPersistMillis = reading.observedAtMillis,
                inferredCategory = guess.category,
                guessedType = guess.guessedType,
                classificationEvidence = guess.evidence,
                classificationConfidence = guess.confidence,
                metadata = reading.metadata
            )
            observations[id] = item
            listDirty = true
            if (duringExam) alertOnNew(item)
        } else {
            item = existing
            item.lastSeenMillis = reading.observedAtMillis
            item.rssiDbm = reading.rssiDbm
            item.frequencyMhz = reading.frequencyMhz
            if (reading.displayName != "이름 미표시 BLE 신호" &&
                reading.displayName != "이름 숨김 접속점" &&
                item.displayName != reading.displayName.take(75)) {
                item.displayName = reading.displayName.take(75)
                val guess = DeviceClassifier.classify(item.kind, item.displayName)
                item.inferredCategory = guess.category
                item.guessedType = guess.guessedType
                item.classificationEvidence = guess.evidence
                item.classificationConfidence = guess.confidence
                listDirty = true
            }
            if (reading.metadata.isNotBlank() && item.metadata != reading.metadata) {
                item.metadata = reading.metadata
                listDirty = true
            }
            item.count++
        }
        if (phase == Phase.BASELINE && reading.kind == SignalKind.WIFI && reading.wifiFreshRound > 0L &&
            item.lastWifiBaselineRound != reading.wifiFreshRound) {
            item.baselineWifiRounds++
            item.lastWifiBaselineRound = reading.wifiFreshRound
            listDirty = true
        }
        if (duringExam) {
            val wasRecurring = item.recurringInExam
            if (reading.kind == SignalKind.WIFI) {
                // Round number only changes on a real, refreshed AP observation.
                if (reading.wifiFreshRound > 0L && item.lastExamWifiRound != reading.wifiFreshRound) {
                    item.lastExamWifiRound = reading.wifiFreshRound
                    item.examWindows++
                }
            } else {
                // BLE callback count alone is not evidence of repeated transmission.
                val window = reading.observedAtMillis / 10_000L
                if (item.lastExamWindow != window) {
                    item.lastExamWindow = window
                    item.examWindows++
                }
            }
            if (!wasRecurring && item.recurringInExam && item.needsAttention) {
                lastAlertMessage = "시험 중 재관측: ${item.displayName}"
                lastAlertAtMillis = System.currentTimeMillis()
                listDirty = true
            }
        }
        if (trackingKey == id) addTrackingPoint(reading)
        // Limit database writes; selection, category and exam transition save immediately.
        if (existing == null || reading.observedAtMillis - item.lastPersistMillis >= 10_000L) {
            store.save(sessionId, item)
            item.lastPersistMillis = reading.observedAtMillis
        }
        if (SystemClock.elapsedRealtime() - lastListRefresh > 3_000L) listDirty = true
    }

    private fun alertOnNew(item: ObservedSignal) {
        lastAlertMessage = "시험 시작 후 신규: ${item.displayName}"
        lastAlertAtMillis = System.currentTimeMillis()
        listDirty = true
    }

    private fun addTrackingPoint(reading: SignalReading) {
        val now = reading.observedAtMillis
        val previousAt = trackingSamples.lastOrNull()?.first ?: 0L
        val interval = if (reading.kind == SignalKind.WIFI) 1_000L else 2_000L
        if (now - previousAt < interval) return
        trackingSamples.addLast(now to reading.rssiDbm)
        while (trackingSamples.size > 36) trackingSamples.removeFirst()
    }

    private fun hashId(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sessionSalt + ":" + raw).toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun redraw() {
        val elapsedSeconds = if (active) ((SystemClock.elapsedRealtime() - startedElapsed) / 1_000L).coerceAtLeast(0L) else 0L
        when (phase) {
            Phase.BASELINE -> {
                status.text = "시험 전 기준 수집 중"
                elapsed.text = "수집 ${elapsedSeconds / 60}분 ${elapsedSeconds % 60}초 · Wi-Fi 갱신 ${successfulBaselineWifiScans}회 (2회 이상 권장)\n시험이 시작되면 아래의 '시험 시작' 버튼을 누르세요"
                status.setTextColor(Color.parseColor("#145E66"))
            }
            Phase.EXAM -> {
                val examSeconds = ((System.currentTimeMillis() - examStartedAtMillis) / 1_000L).coerceAtLeast(0L)
                status.text = "시험 중 신호 감시"
                elapsed.text = "시험 시작 후 ${examSeconds / 60}분 ${examSeconds % 60}초 · 화면을 켜 두세요"
                status.setTextColor(Color.parseColor("#B14935"))
            }
            Phase.FINISHED -> {
                status.text = "검사 종료됨"
                elapsed.text = "기록이 기기 내부에 저장되었습니다"
                status.setTextColor(Color.parseColor("#526B72"))
            }
            Phase.IDLE -> {
                status.text = "대기 중"
                elapsed.text = "시험 전 기준 수집을 시작하세요. 시험 시작 버튼은 별도로 표시됩니다."
                status.setTextColor(Color.parseColor("#526B72"))
            }
        }
        bleStatus.text = "블루투스 LE  ·  " + if (active) scannerBleState else if (scanner.bleAvailable()) "준비됨" else "꺼짐 / 권한 확인"
        wifiStatus.text = "와이파이 AP  ·  " + if (active) scannerWifiState else if (scanner.wifiAvailable()) "준비됨 (위치 서비스 필요)" else "꺼짐 / 미지원"
        sdrStatus.text = UsbSdrStatus.getDescription(this)
        bleCount.text = observations.values.count { it.kind == SignalKind.BLE }.toString()
        wifiCount.text = observations.values.count { it.kind == SignalKind.WIFI }.toString()
        newCount.text = "${observations.values.count { it.discoveredDuringExam }}건"
        fun t(ms: Long): String = if (ms == 0L) "아직 없음" else dateFormat.format(Date(ms))
        lastScan.text = "BLE 최근 수신: ${t(scanner.lastBleAt)}  ·  와이파이 갱신: ${t(scanner.lastWifiAt)}"
        val grouped = observations.values.groupingBy { it.category }.eachCount()
        categorySummary.text = "의심 기기 ${grouped[DeviceCategory.SUSPECT] ?: 0}  ·  스마트폰 ${grouped[DeviceCategory.PHONE] ?: 0}\n" +
            "일반 가전 ${grouped[DeviceCategory.HOME] ?: 0}  ·  분류 불명 ${grouped[DeviceCategory.UNKNOWN] ?: 0}"
        val muted = observations.values.count { it.autoMuted && !it.manualAttention }
        autoMuteSummary.text = when (phase) {
            Phase.BASELINE -> "기존 공유기 자동 제외 후보: ${observations.values.count { ExamSignalPolicy.mayAutoMuteWifi(it) }}건 · Wi-Fi 갱신 ${successfulBaselineWifiScans}회"
            Phase.EXAM, Phase.FINISHED -> "알림에서 접어 둔 기존 AP: ${muted}건 (검사는 계속 진행)"
            Phase.IDLE -> "기준 수집에서 반복 확인된 기존 공유기는 시험 중 알림에서 접어 둡니다."
        }
        val attention = observations.values.count { it.needsAttention }
        alertBanner.text = when (phase) {
            Phase.EXAM -> if (attention > 0) "확인할 신호 ${attention}건 · ${lastAlertMessage.ifEmpty { "우선 확인 목록을 점검하세요" }}"
                else "현재 확인할 신규·반복 신호 없음 · 마지막 검색 시각도 확인하세요"
            Phase.BASELINE -> "기준 신호를 수집하는 단계입니다. 시험 시작 후 신규·반복 신호를 알려줍니다."
            else -> "신호를 관측해도 부정행위 확정은 아닙니다."
        }
        alertBanner.setTextColor(Color.parseColor(if (phase == Phase.EXAM && attention > 0) "#A53E2C" else "#526B72"))
        reviewSummary.text = "확인 완료 ${observations.values.count { it.reviewed }} / ${observations.size}개 관측 항목 · 자동 분류는 이름 기반 추정"
        if (listDirty && SystemClock.elapsedRealtime() - lastListRefresh > 1_200L) {
            updateFilters()
            renderSignals()
            listDirty = false
            lastListRefresh = SystemClock.elapsedRealtime()
        }
        updateTrackingDialog()
    }

    private fun categoryColor(c: DeviceCategory): Int = Color.parseColor(when (c) {
        DeviceCategory.SUSPECT -> "#AC4B31"
        DeviceCategory.PHONE -> "#256D97"
        DeviceCategory.HOME -> "#3D7A62"
        DeviceCategory.UNKNOWN -> "#6C6A7B"
    })

    private fun chip(text: String, selected: Boolean, color: Int, onClick: () -> Unit): TextView {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) color else Color.parseColor("#E8ECEF"))
        }
        return label(text, 12, if (selected) "#FFFFFF" else "#344D56").apply {
            background = shape
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { onClick() }
        }
    }

    private fun updateFilters() {
        filterButtons.removeAllViews()
        val entries = listOf(
            "우선 확인" to FilterScope.PRIORITY,
            "시험 중 신규" to FilterScope.NEW,
            "반복 관측" to FilterScope.REPEATED,
            "기존 AP 제외" to FilterScope.MUTED,
            "전체" to FilterScope.ALL
        )
        entries.forEach { (title, mode) ->
            filterButtons.addView(chip(title, scope == mode, Color.parseColor("#2A626A")) {
                scope = mode
                chosenCategory = null
                showAllRows = false
                updateFilters(); renderSignals()
                listDirty = false; lastListRefresh = SystemClock.elapsedRealtime()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        DeviceCategory.values().forEach { cat ->
            filterButtons.addView(chip(cat.display, scope == FilterScope.CATEGORY && chosenCategory == cat, categoryColor(cat)) {
                scope = FilterScope.CATEGORY
                chosenCategory = cat
                showAllRows = false
                updateFilters(); renderSignals()
                listDirty = false; lastListRefresh = SystemClock.elapsedRealtime()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
    }

    private fun renderSignals() {
        signalList.removeAllViews()
        val matching = observations.values.filter {
            when (scope) {
                FilterScope.PRIORITY -> if (phase == Phase.BASELINE) !it.reviewed else it.needsAttention
                FilterScope.NEW -> it.discoveredDuringExam
                FilterScope.REPEATED -> it.recurringInExam
                FilterScope.MUTED -> it.autoMuted && !it.manualAttention
                FilterScope.ALL -> true
                FilterScope.CATEGORY -> it.category == chosenCategory
            }
        }.sortedWith(compareByDescending<ObservedSignal> { it.needsAttention }
            .thenByDescending { it.discoveredDuringExam }
            .thenByDescending { it.recurringInExam }
            .thenByDescending { it.lastSeenMillis })
        if (matching.isEmpty()) {
            signalList.addView(label(
                if (observations.isEmpty()) "아직 관측된 신호가 없습니다.\n검색 상태와 권한을 확인하세요."
                else if (scope == FilterScope.PRIORITY && phase == Phase.EXAM) "우선 확인할 신호가 없습니다. 새 탐지 시 여기에 표시됩니다."
                else "이 분류에 해당하는 관측 항목이 없습니다.", 13, "#70838B").apply {
                setPadding(dp(7), dp(12), dp(7), dp(12))
            })
            return
        }
        val limit = if (showAllRows) matching.size else 30
        matching.take(limit).forEachIndexed { idx, item ->
            if (idx > 0) {
                signalList.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E9EEF0")) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val prefix = when {
                item.discoveredDuringExam -> "[시험 후 신규] "
                item.autoMuted && !item.manualAttention -> "[기존 AP · 알림 제외] "
                item.recurringInExam -> "[시험 중 반복] "
                else -> ""
            }
            val title = label("$prefix${item.displayName}", 14, "#203840")
            title.setTypeface(null, android.graphics.Typeface.BOLD)
            title.setOnLongClickListener {
                toggleAttention(item)
                true
            }
            val typeLabel = label("${item.category.display}  ·  ${item.guessedType}" +
                (if (item.manualCategory != null) " (수동 분류)" else " (자동 추정)"), 12, "#405960")
            typeLabel.setPadding(0, dp(5), 0, 0)
            val measures = label("${item.kind.display} · ${item.rssiDbm} dBm · ${item.count}회 · 최근 ${t(item.lastSeenMillis)}", 11, "#7A8B90")
            measures.setPadding(0, dp(5), 0, 0)
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val review = CheckBox(this).apply {
                text = "확인 완료"
                textSize = 12f
                setTextColor(Color.parseColor("#3E5E65"))
                isChecked = item.reviewed
                setOnCheckedChangeListener { _, checked ->
                    item.reviewed = checked
                    if (sessionId > 0L) store.save(sessionId, item)
                    listDirty = true
                }
            }
            controls.addView(review)
            val details = chip("상세·분류", false, categoryColor(item.category)) { showSignalDetail(item) }
            controls.addView(details, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) })
            row.addView(title)
            row.addView(typeLabel)
            row.addView(measures)
            row.addView(controls)
            val tracker = chip("근접 신호 검사 · 변화 보기", false, Color.parseColor("#22636B")) {
                showSignalTracker(item)
            }
            row.addView(tracker, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val attentionChip = chip(if (item.manualAttention) "관심 지정 해제" else "관심 신호로 지정 / 알림 제외 해제", false, Color.parseColor("#8B5B36")) {
                toggleAttention(item)
            }
            row.addView(attentionChip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val marker = View(this).apply { setBackgroundColor(categoryColor(item.category)) }
            val itemLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            itemLine.addView(marker, LinearLayout.LayoutParams(dp(4), -1).apply { rightMargin = dp(10) })
            itemLine.addView(row, LinearLayout.LayoutParams(0, -2, 1f))
            signalList.addView(itemLine)
        }
        if (!showAllRows && matching.size > limit) {
            val more = chip("나머지 ${matching.size - limit}건 모두 보기", false, Color.parseColor("#22636B")) {
                showAllRows = true
                renderSignals()
            }
            signalList.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
    }

    private fun showSignalTracker(item: ObservedSignal) {
        if (!active) {
            AlertDialog.Builder(this).setMessage("근접 검사는 신호를 관측하는 동안에만 사용할 수 있습니다.")
                .setPositiveButton("확인", null).show()
            return
        }
        trackingDialog?.dismiss()
        trackingKey = item.stableKey
        trackingSamples.clear()
        trackingBody = label("새 관측값을 기다리는 중...", 15, "#243E46").apply {
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        trackingDialog = AlertDialog.Builder(this)
            .setTitle("근접 검사 · ${item.displayName}")
            .setView(trackingBody)
            .setPositiveButton("닫기", null)
            .create().apply {
                setOnDismissListener {
                    trackingDialog = null
                    trackingBody = null
                    trackingKey = null
                    trackingSamples.clear()
                }
                show()
            }
        updateTrackingDialog()
    }

    private fun updateTrackingDialog() {
        val key = trackingKey ?: return
        val item = observations[key] ?: return
        val body = trackingBody ?: return
        val samples = trackingSamples.toList()
        val staleThreshold = if (item.kind == SignalKind.WIFI) 90_000L else 25_000L
        val age = (System.currentTimeMillis() - item.lastSeenMillis).coerceAtLeast(0L)
        val fresh = age <= staleThreshold
        val trend = if (!fresh) "최근 새 측정이 없습니다"
            else if (samples.size < 5 || samples.last().first - samples.first().first < 8_000L) "변화 판단을 위한 측정값 수집 중"
            else {
                val start = samples.take(3).map { it.second }.average()
                val end = samples.takeLast(3).map { it.second }.average()
                when {
                    end - start >= 7.0 -> "신호가 강해지는 추세 (+${(end-start).toInt()} dB)"
                    start - end >= 7.0 -> "신호가 약해지는 추세 (${(end-start).toInt()} dB)"
                    else -> "뚜렷한 변화 없음 (±7 dB 미만)"
                }
            }
        val lines = samples.takeLast(8).joinToString("  ") { "${it.second}" }
        body.text = "${item.kind.display} · ${item.frequencyMhz?.let { "$it MHz" } ?: "주파수 정보 없음"}\n" +
            "관측 신호: ${item.rssiDbm} dBm\n" +
            "마지막 관측: ${age / 1_000L}초 전${if (!fresh) " (오래된 값)" else ""}\n" +
            "\n$trend\n" +
            "최근 신호 강도(dBm):\n${if (lines.isEmpty()) "수집 대기 중" else lines}\n\n" +
            "이동하면서 변화를 비교하세요. 신호 강도로 정확한 거리나 좌석을 판정할 수 없습니다.\n" +
            (if (item.kind == SignalKind.WIFI) "와이파이는 안드로이드 검색 간격 제한으로 실시간 추적이 아닙니다." else "BLE 탐지는 기기가 송출하는 광고 신호에 한정됩니다.")
    }

    private fun toggleAttention(item: ObservedSignal) {
        item.manualAttention = !item.manualAttention
        store.save(sessionId, item)
        listDirty = true
        updateFilters(); renderSignals()
        listDirty = false
        lastListRefresh = SystemClock.elapsedRealtime()
    }

    private fun saveReview(item: ObservedSignal) {
        if (sessionId > 0L) store.save(sessionId, item)
        listDirty = true
        updateFilters()
        renderSignals()
        listDirty = false
        lastListRefresh = SystemClock.elapsedRealtime()
        reviewSummary.text = "확인 완료 ${observations.values.count { it.reviewed }} / ${observations.size}개 관측 항목 · 자동 분류는 이름 기반 추정"
    }

    private fun editCategory(item: ObservedSignal) {
        val names = arrayOf("자동 분류 사용", DeviceCategory.HOME.display, DeviceCategory.PHONE.display,
            DeviceCategory.SUSPECT.display, DeviceCategory.UNKNOWN.display)
        val categories = arrayOf(null, DeviceCategory.HOME, DeviceCategory.PHONE, DeviceCategory.SUSPECT, DeviceCategory.UNKNOWN)
        AlertDialog.Builder(this)
            .setTitle("감독관 분류 수정")
            .setSingleChoiceItems(names, categories.indexOf(item.manualCategory)) { dialog, which ->
                item.manualCategory = categories[which]
                if (item.category != DeviceCategory.HOME) item.autoMuted = false
                saveReview(item)
                dialog.dismiss()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun showSignalDetail(item: ObservedSignal) {
        val source = if (item.manualCategory == null) "자동 추정" else "감독관이 수동 변경"
        AlertDialog.Builder(this)
            .setTitle(item.displayName)
            .setMessage("현재 분류: ${item.category.display} ($source)\n" +
                "추정 종류: ${item.guessedType}\n추정 신뢰도: ${item.classificationConfidence}\n" +
                "분류 근거: ${item.classificationEvidence}\n" +
                "검토: ${if (item.reviewed) "확인 완료" else "미확인"}\n" +
                "기존 AP 자동 알림 제외: ${if (item.autoMuted && !item.manualAttention) "적용" else "미적용"}\n" +
                "시험 중 반복 관측: ${if (item.recurringInExam) "예" else "아직 확인 안 됨"}\n\n" +
                "신호 유형: ${item.kind.display}\n" +
                "세션 가명 ID: ${item.stableKey}\n" +
                "최초 관측: ${t(item.firstSeenMillis)}\n최근 관측: ${t(item.lastSeenMillis)}\n" +
                "마지막 강도: ${item.rssiDbm} dBm\n관측 횟수: ${item.count}\n" +
                "주파수: ${item.frequencyMhz?.let { "$it MHz" } ?: "정보 없음"}\n" +
                (if (item.metadata.isNotBlank()) "메타데이터: ${item.metadata}\n\n" else "\n") +
                "※ 송출 이름은 임의 설정될 수 있으며 기기 식별, 위치, 부정행위를 확정하지 않습니다.")
            .setNeutralButton("분류 수정") { _, _ -> editCategory(item) }
            .setPositiveButton(if (item.reviewed) "미확인으로" else "확인 완료") { _, _ ->
                item.reviewed = !item.reviewed
                saveReview(item)
            }
            .setNegativeButton("닫기", null).show()
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
        AlertDialog.Builder(this).setTitle("사용설명서 · v0.3")
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
