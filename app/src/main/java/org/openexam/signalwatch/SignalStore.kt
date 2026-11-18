package org.openexam.signalwatch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device SQLite only. No network/API usage. */
class SignalStore(context: Context) : SQLiteOpenHelper(context, "exam_observations.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                baseline_seconds INTEGER NOT NULL,
                exam_started_at INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE observations (
                session_id INTEGER NOT NULL,
                signal_key TEXT NOT NULL,
                kind TEXT NOT NULL,
                label TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                last_rssi INTEGER NOT NULL,
                sightings INTEGER NOT NULL,
                frequency_mhz INTEGER,
                exam_new INTEGER NOT NULL,
                inferred_category TEXT NOT NULL DEFAULT 'UNKNOWN',
                type_guess TEXT NOT NULL DEFAULT '',
                classification_basis TEXT NOT NULL DEFAULT '',
                confidence TEXT NOT NULL DEFAULT '',
                manual_category TEXT,
                reviewed INTEGER NOT NULL DEFAULT 0,
                metadata TEXT NOT NULL DEFAULT '',
                baseline_wifi_rounds INTEGER NOT NULL DEFAULT 0,
                auto_muted INTEGER NOT NULL DEFAULT 0,
                manual_attention INTEGER NOT NULL DEFAULT 0,
                exam_windows INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (session_id, signal_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_observations_session ON observations(session_id, first_seen)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Additive migration: v0.1 history remains available.
            db.execSQL("ALTER TABLE observations ADD COLUMN inferred_category TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE observations ADD COLUMN type_guess TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE observations ADD COLUMN classification_basis TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE observations ADD COLUMN confidence TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE observations ADD COLUMN manual_category TEXT")
            db.execSQL("ALTER TABLE observations ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN metadata TEXT NOT NULL DEFAULT ''")
            // Reclassify earlier observations as well, without deleting exam history.
            val rows = mutableListOf<Triple<Long, String, DeviceGuess>>()
            db.rawQuery("SELECT session_id, signal_key, kind, label FROM observations", null).use { c ->
                while (c.moveToNext()) {
                    val kind = if (c.getString(2) == "BLE") SignalKind.BLE else SignalKind.WIFI
                    rows.add(Triple(c.getLong(0), c.getString(1), DeviceClassifier.classify(kind, c.getString(3))))
                }
            }
            rows.forEach { (session, key, guess) ->
                db.execSQL("UPDATE observations SET inferred_category=?,type_guess=?,classification_basis=?,confidence=? WHERE session_id=? AND signal_key=?",
                    arrayOf(guess.category.name, guess.guessedType, guess.evidence, guess.confidence, session, key))
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN exam_started_at INTEGER")
            db.execSQL("ALTER TABLE observations ADD COLUMN baseline_wifi_rounds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN auto_muted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN manual_attention INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN exam_windows INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun beginSession(baselineSeconds: Int): Long {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO sessions (started_at, baseline_seconds) VALUES (?,?)",
            arrayOf<Any>(System.currentTimeMillis(), baselineSeconds)
        )
        return db.compileStatement("SELECT last_insert_rowid()").simpleQueryForLong()
    }

    fun markExamStarted(sessionId: Long, atMillis: Long) {
        writableDatabase.execSQL(
            "UPDATE sessions SET exam_started_at=? WHERE id=?",
            arrayOf<Any>(atMillis, sessionId)
        )
    }

    fun endSession(sessionId: Long) {
        writableDatabase.execSQL(
            "UPDATE sessions SET ended_at = ? WHERE id = ? AND ended_at IS NULL",
            arrayOf<Any>(System.currentTimeMillis(), sessionId)
        )
    }

    fun save(sessionId: Long, signal: ObservedSignal) {
        val values = android.content.ContentValues().apply {
            put("session_id", sessionId)
            put("signal_key", signal.stableKey)
            put("kind", signal.kind.name)
            put("label", signal.displayName)
            put("first_seen", signal.firstSeenMillis)
            put("last_seen", signal.lastSeenMillis)
            put("last_rssi", signal.rssiDbm)
            put("sightings", signal.count)
            if (signal.frequencyMhz == null) putNull("frequency_mhz") else put("frequency_mhz", signal.frequencyMhz)
            put("exam_new", if (signal.discoveredDuringExam) 1 else 0)
            put("inferred_category", signal.inferredCategory.name)
            put("type_guess", signal.guessedType)
            put("classification_basis", signal.classificationEvidence)
            put("confidence", signal.classificationConfidence)
            if (signal.manualCategory == null) putNull("manual_category") else put("manual_category", signal.manualCategory!!.name)
            put("reviewed", if (signal.reviewed) 1 else 0)
            put("metadata", signal.metadata)
            put("baseline_wifi_rounds", signal.baselineWifiRounds)
            put("auto_muted", if (signal.autoMuted) 1 else 0)
            put("manual_attention", if (signal.manualAttention) 1 else 0)
            put("exam_windows", signal.examWindows)
        }
        writableDatabase.insertWithOnConflict("observations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class SessionInfo(val id: Long, val started: Long, val ended: Long?, val signals: Int)

    fun sessions(): List<SessionInfo> {
        val items = mutableListOf<SessionInfo>()
        readableDatabase.rawQuery("""
            SELECT s.id,s.started_at,s.ended_at,COUNT(o.signal_key)
            FROM sessions s LEFT JOIN observations o ON o.session_id=s.id
            GROUP BY s.id ORDER BY s.started_at DESC LIMIT 50
        """.trimIndent(), null).use { c ->
            while (c.moveToNext()) {
                items.add(SessionInfo(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3)))
            }
        }
        return items
    }

    fun exportSessionCsv(sessionId: Long): String {
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        fun dt(ms: Long) = dateTime.format(Date(ms))
        fun csv(v: String): String {
            val clean = v.replace("\u0000", "")
            // Prevent untrusted BLE names/SSIDs from being treated as spreadsheet formulas.
            val formulaLike = clean.startsWith("=") || clean.startsWith("+") || clean.startsWith("@") ||
                (clean.startsWith("-") && clean.toDoubleOrNull() == null) ||
                clean.startsWith("\t") || clean.startsWith("\r")
            val safe = if (formulaLike) "'" + clean else clean
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        val sb = StringBuilder("\uFEFF")
        sb.append("유형,표시명,세션 내 가명 ID,최초 관측,마지막 관측,마지막 신호(dBm),관측 횟수,주파수(MHz),시험 시작 후 신규 관측,분류,추정 종류,분류 근거,추정 신뢰도,수동 분류 여부,검토 완료,무선 메타데이터,시험 시작 시간,시험 전 갱신된 Wi-Fi 검색 횟수,기존 AP 자동 알림 제외,감독관 관심 지정,시험 중 서로 다른 관측 구간 수\r\n")
        val examStartedAt = readableDatabase.rawQuery(
            "SELECT exam_started_at FROM sessions WHERE id=?", arrayOf(sessionId.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) dt(c.getLong(0)) else "" }
        readableDatabase.rawQuery(
            "SELECT kind,label,signal_key,first_seen,last_seen,last_rssi,sightings,frequency_mhz,exam_new,inferred_category,type_guess,classification_basis,confidence,manual_category,reviewed,metadata,baseline_wifi_rounds,auto_muted,manual_attention,exam_windows FROM observations WHERE session_id=? ORDER BY first_seen ASC",
            arrayOf(sessionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val kind = if (c.getString(0) == "BLE") "블루투스 LE" else "와이파이 접속점"
                val cells = listOf(
                    kind, c.getString(1), c.getString(2), dt(c.getLong(3)), dt(c.getLong(4)),
                    c.getInt(5).toString(), c.getInt(6).toString(),
                    if (c.isNull(7)) "" else c.getInt(7).toString(),
                    if (c.getInt(8) == 1) "예" else "아니요(기준 측정)",
                    if (c.isNull(13)) DeviceCategory.valueOf(c.getString(9)).display else DeviceCategory.valueOf(c.getString(13)).display,
                    c.getString(10) ?: "", c.getString(11) ?: "", c.getString(12) ?: "",
                    if (c.isNull(13)) "아니요" else "예",
                    if (c.getInt(14) == 1) "예" else "아니요",
                    c.getString(15) ?: "",
                    examStartedAt, c.getInt(16).toString(),
                    if (c.getInt(17) == 1) "예" else "아니요",
                    if (c.getInt(18) == 1) "예" else "아니요",
                    c.getInt(19).toString()
                )
                sb.append(cells.joinToString(",") { csv(it) }).append("\r\n")
            }
        }
        return sb.toString()
    }

    fun deleteAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("observations", null, null)
            writableDatabase.delete("sessions", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
