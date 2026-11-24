package bg.drone.receiver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import bg.drone.rid.Target
import bg.drone.rid.TargetKind
import java.time.Instant
import java.time.ZoneId

/** Един запис в историята: един обект за един ден. */
data class Detection(
    val key: String,
    val targetId: String,
    val kind: TargetKind,
    val title: String,
    val subtitle: String?,
    val source: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val lat: Double,
    val lon: Double,
    val maxAltM: Double?,
    val minDistanceM: Double?,
    val day: String,
)

data class DayStats(val day: String, val total: Int, val byKind: Map<TargetKind, Int>)

/**
 * История на засичанията в локална SQLite база (без външни библиотеки).
 * Ключ = обект + ден: един и същ дрон в различни дни е отделен запис.
 */
class DetectionHistory(context: Context) : SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE detections (
                key TEXT PRIMARY KEY, target_id TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT,
                source TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
                max_alt REAL, min_dist REAL, day TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX idx_day ON detections(day)")
        db.execSQL("CREATE INDEX idx_last ON detections(last_seen)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private val lastWrite = HashMap<String, Long>()

    /** Записва/обновява обектите. Всеки обект се пише най-често веднъж на 30 s, за да пазим батерията. */
    @Synchronized
    fun record(targets: List<Target>, me: Pair<Double, Double>?, now: Long = System.currentTimeMillis()) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val due = targets.filter { it.kind != TargetKind.AIRCRAFT && now - (lastWrite[it.id] ?: 0) >= 30_000 }
        if (due.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in due) {
                val key = "${t.id}|$day"
                val dist = me?.let { t.distanceFrom(it.first, it.second) }
                db.rawQuery("SELECT max_alt, min_dist FROM detections WHERE key = ?", arrayOf(key)).use { c ->
                    if (c.moveToFirst()) {
                        val maxAlt = listOfNotNull(if (c.isNull(0)) null else c.getDouble(0), t.altitudeM).maxOrNull()
                        val minDist = listOfNotNull(if (c.isNull(1)) null else c.getDouble(1), dist).minOrNull()
                        db.update("detections", ContentValues().apply {
                            put("last_seen", now)
                            put("title", t.title)
                            put("subtitle", t.subtitle)
                            if (maxAlt != null) put("max_alt", maxAlt)
                            if (minDist != null) put("min_dist", minDist)
                        }, "key = ?", arrayOf(key))
                    } else {
                        db.insert("detections", null, ContentValues().apply {
                            put("key", key); put("target_id", t.id); put("kind", t.kind.name); put("title", t.title)
                            put("subtitle", t.subtitle); put("source", t.source.label); put("first_seen", now); put("last_seen", now)
                            put("lat", t.lat); put("lon", t.lon); t.altitudeM?.let { put("max_alt", it) }; dist?.let { put("min_dist", it) }
                            put("day", day)
                        })
                    }
                }
                lastWrite[t.id] = now
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (lastWrite.size > 5000) lastWrite.clear()
    }

    fun recent(limit: Int = 300): List<Detection> = readableDatabase.rawQuery(
        "SELECT key,target_id,kind,title,subtitle,source,first_seen,last_seen,lat,lon,max_alt,min_dist,day FROM detections ORDER BY last_seen DESC LIMIT ?",
        arrayOf(limit.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Detection(
                        c.getString(0), c.getString(1), runCatching { TargetKind.valueOf(c.getString(2)) }.getOrDefault(TargetKind.DRONE),
                        c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getLong(7), c.getDouble(8), c.getDouble(9),
                        if (c.isNull(10)) null else c.getDouble(10), if (c.isNull(11)) null else c.getDouble(11), c.getString(12),
                    ),
                )
            }
        }
    }

    fun dailyStats(days: Int = 14): List<DayStats> = readableDatabase.rawQuery(
        "SELECT day, kind, COUNT(*) FROM detections GROUP BY day, kind ORDER BY day DESC",
        null,
    ).use { c ->
        val m = LinkedHashMap<String, MutableMap<TargetKind, Int>>()
        while (c.moveToNext()) {
            val kind = runCatching { TargetKind.valueOf(c.getString(1)) }.getOrNull() ?: continue
            m.getOrPut(c.getString(0)) { LinkedHashMap() }[kind] = c.getInt(2)
        }
        m.entries.take(days).map { (d, k) -> DayStats(d, k.values.sum(), k) }
    }

    fun clear() {
        writableDatabase.delete("detections", null, null)
        lastWrite.clear()
    }
}
