package org.openexam.signalwatch

/** Scan identifiers come from radio APIs and are never written in raw form. */
enum class SignalKind(val display: String) { BLE("블루투스 LE"), WIFI("와이파이 접속점") }

data class SignalReading(
    val kind: SignalKind,
    val rawIdentifier: String,
    val displayName: String,
    val rssiDbm: Int,
    val frequencyMhz: Int? = null,
    val observedAtMillis: Long = System.currentTimeMillis(),
    val metadata: String = "",
    val wifiFreshRound: Long = 0L
)

data class ObservedSignal(
    val stableKey: String,
    val kind: SignalKind,
    var displayName: String,
    var rssiDbm: Int,
    var firstSeenMillis: Long,
    var lastSeenMillis: Long,
    var count: Int,
    var frequencyMhz: Int?,
    val discoveredDuringExam: Boolean,
    var lastPersistMillis: Long = 0L,
    var inferredCategory: DeviceCategory = DeviceCategory.UNKNOWN,
    var guessedType: String = "종류 확인 불가",
    var classificationEvidence: String = "",
    var classificationConfidence: String = "판별 불가",
    var manualCategory: DeviceCategory? = null,
    var reviewed: Boolean = false,
    var metadata: String = "",
    var baselineWifiRounds: Int = 0,
    var autoMuted: Boolean = false,
    var manualAttention: Boolean = false,
    var examWindows: Int = 0,
    var lastExamWindow: Long = -1L,
    var lastWifiBaselineRound: Long = -1L,
    var lastExamWifiRound: Long = -1L
) {
    val category: DeviceCategory get() = manualCategory ?: inferredCategory
    val recurringInExam: Boolean get() = ExamSignalPolicy.isRepeatedDuringExam(kind, examWindows)
    val needsAttention: Boolean get() = ExamSignalPolicy.needsAttention(this)
}
