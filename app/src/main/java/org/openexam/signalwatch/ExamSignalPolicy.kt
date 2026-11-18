package org.openexam.signalwatch

/**
 * Offline triage policy. The result is an observation priority, not a person/device verdict.
 * Baseline APs are *hidden from the default alert list*, never deleted or excluded from scanning.
 */
object ExamSignalPolicy {
    const val RECOMMENDED_BASELINE_SECONDS = 90
    const val MIN_FRESH_WIFI_ROUNDS = 2

    /** Conservative: known fixed AP by name, repeatedly seen in truly refreshed scans. */
    fun mayAutoMuteWifi(signal: ObservedSignal): Boolean =
        signal.kind == SignalKind.WIFI && !signal.discoveredDuringExam &&
            signal.baselineWifiRounds >= MIN_FRESH_WIFI_ROUNDS &&
            signal.category == DeviceCategory.HOME && !signal.manualAttention

    /** A BLE window means repeated receipt over 10s, not proof of periodic packet emission. */
    fun isRepeatedDuringExam(kind: SignalKind, windows: Int): Boolean =
        if (kind == SignalKind.BLE) windows >= 3 else windows >= 2

    fun needsAttention(signal: ObservedSignal): Boolean {
        if (signal.reviewed || signal.autoMuted && !signal.manualAttention) return false
        if (signal.manualAttention) return true
        if (signal.discoveredDuringExam) return true
        if (signal.category == DeviceCategory.SUSPECT) return true
        // Exam-period repeat observations: don't alert on ordinary fixed access points.
        if (signal.recurringInExam && signal.kind == SignalKind.BLE &&
            signal.category != DeviceCategory.HOME) return true
        return false
    }
}
