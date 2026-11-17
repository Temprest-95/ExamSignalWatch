package org.openexam.signalwatch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device SQLite only. No network/API usage. */
class SignalStore(context: Context) : SQLiteOpenHelper(context, "exam_observations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                baseline_seconds INTEGER NOT NULL
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
                PRIMARY KEY (session_id, signal_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_observations_session ON observations(session_id, first_seen)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations must be added when schema changes; never silently drop exam history.
        throw IllegalStateException("미지원 데이터베이스 버전: $oldVersion → $newVersion")
    }

    fun beginSession(baselineSeconds: Int): Long {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO sessions (started_at, baseline_seconds) VALUES (?,?)",
            arrayOf<Any>(System.currentTimeMillis(), baselineSeconds)
        )
        return db.compileStatement("SELECT last_insert_rowid()").simpleQueryForLong()
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
        fun csv(v: String): String = "\"" + v.replace("\"", "\"\"").replace("\u0000", "") + "\""
        val sb = StringBuilder("\uFEFF")
        sb.append("유형,표시명,세션 내 가명 ID,최초 관측,마지막 관측,마지막 신호(dBm),관측 횟수,주파수(MHz),시험 시작 후 신규 관측\r\n")
        readableDatabase.rawQuery(
            "SELECT kind,label,signal_key,first_seen,last_seen,last_rssi,sightings,frequency_mhz,exam_new FROM observations WHERE session_id=? ORDER BY first_seen ASC",
            arrayOf(sessionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val kind = if (c.getString(0) == "BLE") "블루투스 LE" else "와이파이 접속점"
                val cells = listOf(
                    kind, c.getString(1), c.getString(2), dt(c.getLong(3)), dt(c.getLong(4)),
                    c.getInt(5).toString(), c.getInt(6).toString(),
                    if (c.isNull(7)) "" else c.getInt(7).toString(),
                    if (c.getInt(8) == 1) "예" else "아니요(기준 측정)"
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
