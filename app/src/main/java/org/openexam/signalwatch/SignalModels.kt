package org.openexam.signalwatch

/** Scan identifiers come from radio APIs and are never written in raw form. */
enum class SignalKind(val display: String) { BLE("블루투스 LE"), WIFI("와이파이 접속점") }

data class SignalReading(
    val kind: SignalKind,
    val rawIdentifier: String,
    val displayName: String,
    val rssiDbm: Int,
    val frequencyMhz: Int? = null,
    val observedAtMillis: Long = System.currentTimeMillis()
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
    var lastPersistMillis: Long = 0L
)
